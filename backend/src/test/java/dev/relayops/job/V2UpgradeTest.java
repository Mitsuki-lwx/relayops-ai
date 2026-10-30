package dev.relayops.job;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RELAYOPS_DATABASE_TESTS", matches = "true")
class V2UpgradeTest {
    @Test
    void upgradePreservesAcceptedJobAndBackfillsItsOutbox() throws Exception {
        var source = new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:3307/relayops_upgrade_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                "relayops", System.getenv("DB_PASSWORD"));
        try (var connection = source.getConnection()) {
            assertEquals("relayops_upgrade_test", connection.getCatalog(), "Refuse to clean a non-test schema");
        }
        var v1 = Flyway.configure().dataSource(source).locations("classpath:db/migration").target("1").cleanDisabled(false).load();
        // This explicitly named schema is used only for the upgrade fixture; demo/test application schemas are untouched.
        v1.clean(); v1.migrate();
        var jdbc = new JdbcTemplate(source);
        String user = UUID.randomUUID().toString(), space = UUID.randomUUID().toString();
        String app = UUID.randomUUID().toString(), job = UUID.randomUUID().toString();
        var date = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        jdbc.update("INSERT INTO user_account(id,username,password_hash) VALUES (?,'migration.owner','unused-fixture-hash')", user);
        jdbc.update("INSERT INTO workspace(id,code,name) VALUES (?,'migration','迁移验证')", space);
        jdbc.update("INSERT INTO workspace_member(workspace_id,user_id,role) VALUES (?,?,'OWNER')",space,user);
        jdbc.update("INSERT INTO application(id,workspace_id,name) VALUES (?,?,'迁移应用')", app, space);
        jdbc.update("INSERT INTO budget_account(workspace_id,accepted_date,reserved) VALUES (?,?,1)", space, date);
        // Opaque fixture bytes are never executed or presented as a real extraction result.
        jdbc.update("""
                INSERT INTO ai_job(id,workspace_id,application_id,actor_id,title,status,input_cipher,input_hash,accepted_date,output_language,queue_expires_at,payload_expires_at)
                VALUES (?,?,?,?,'V1 已受理任务','QUEUED',?,?,?,'zh-CN',DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 10 MINUTE),DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 7 DAY))
                """, job, space, app, user, new byte[]{1, 2, 3}, "0".repeat(64), date);
        jdbc.update("INSERT INTO budget_entry(job_id,workspace_id,accepted_date,state) VALUES (?,?,?,'RESERVED')", job, space, date);
        Integer originalChecksum = jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version = '1'", Integer.class);
        var v2 = Flyway.configure().dataSource(source).locations("classpath:db/migration").target("2").load();
        v2.migrate(); v2.migrate();
        assertEquals(originalChecksum, jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version = '1'", Integer.class));
        assertEquals("QUEUED", jdbc.queryForObject("SELECT status FROM ai_job WHERE id = ?", String.class, job));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ? AND workspace_id = ? AND dispatch_generation = 1 AND status = 'NEW'", Integer.class, job, space));
        assertEquals("RESERVED", jdbc.queryForObject("SELECT state FROM budget_entry WHERE job_id = ?", String.class, job));
        jdbc.update("INSERT INTO idempotency_record(workspace_id,actor_id,operation,idempotency_key,request_hash,job_id,expires_at) VALUES (?,?,'CREATE_JOB','legacy',?,?,DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 24 HOUR))", space, user, "a".repeat(64), job);
        var v3 = Flyway.configure().dataSource(source).locations("classpath:db/migration").target("3").load();
        v3.migrate();
        assertEquals(job, jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(response_json, '$.jobId')) FROM idempotency_record WHERE job_id = ?", String.class, job));
        assertEquals("QUEUED", jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(response_json, '$.status')) FROM idempotency_record WHERE job_id = ?", String.class, job));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record WHERE response_json IS NULL", Integer.class));
        var v4=Flyway.configure().dataSource(source).locations("classpath:db/migration").target("4").load();
        v4.migrate(); v4.migrate();
        assertEquals(space,jdbc.queryForObject("SELECT tenant_id FROM workspace WHERE id=?",String.class,space));
        assertEquals("ADMIN",jdbc.queryForObject("SELECT role FROM tenant_member WHERE tenant_id=? AND user_id=?",String.class,space,user));
        assertEquals("OWNER",jdbc.queryForObject("SELECT role FROM workspace_member WHERE workspace_id=? AND user_id=?",String.class,space,user));
        assertEquals("QUEUED",jdbc.queryForObject("SELECT status FROM ai_job WHERE id=?",String.class,job));
        assertArrayEquals(new byte[]{1,2,3},jdbc.queryForObject("SELECT input_cipher FROM ai_job WHERE id=?",byte[].class,job));
        assertEquals(1,jdbc.queryForObject("SELECT reserved FROM budget_account WHERE workspace_id=?",Integer.class,space));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",Integer.class,job));
        var v5=Flyway.configure().dataSource(source).locations("classpath:db/migration").target("5").load();
        v5.migrate(); v5.migrate();
        assertEquals("QUEUED",jdbc.queryForObject("SELECT status FROM ai_job WHERE id=?",String.class,job));
        assertArrayEquals(new byte[]{1,2,3},jdbc.queryForObject("SELECT input_cipher FROM ai_job WHERE id=?",byte[].class,job));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",Integer.class,job));
        for(var table:java.util.List.of("destination","job_destination","delivery","delivery_run","delivery_attempt","delivery_outbox"))
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
        // The same current schema must also support a new installation's local seed, without duplicates.
        var seed=new dev.relayops.identity.LocalSeed(jdbc,new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4),"local-fixture-password-only");
        seed.run(null); seed.run(null);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM workspace w JOIN tenant_member m ON m.tenant_id=w.tenant_id WHERE w.code IN ('a','b') AND m.role='ADMIN'",Integer.class));
        assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE username IN ('owner.a','operator.a','viewer.a','owner.b','operator.b','viewer.b')",Integer.class));
        assertEquals(1,((java.util.List<?>)new dev.relayops.identity.Access(jdbc).me("owner.a").get("workspaces")).size());
    }
}
