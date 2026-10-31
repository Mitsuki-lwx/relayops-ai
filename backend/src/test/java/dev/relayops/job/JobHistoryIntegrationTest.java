package dev.relayops.job;

import dev.relayops.common.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RELAYOPS_DATABASE_TESTS",matches="true")
@SpringBootTest(properties={"relayops.model.mode=fake","relayops.consumer-enabled=false","relayops.relay-enabled=false","relayops.recovery-enabled=false","relayops.retention-enabled=false"})
class JobHistoryIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired JobService jobs;
    @Autowired FakeModel fake;
    @Autowired JsonMapper json;
    String space,other,seed,actor;
    String id(){return UUID.randomUUID().toString();}
    @BeforeEach void fixtures() throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()){assertEquals("relayops_test",connection.getCatalog());}
        jdbc.update("UPDATE ai_job SET parent_job_id=NULL");
        for(var table:List.of("delivery_outbox","delivery_attempt","delivery_run","replay_plan","delivery","job_destination","destination","quarantine_message","outbox_event","audit_event","idempotency_record","ai_result","budget_entry","ai_attempt","ai_job","budget_account"))jdbc.update("DELETE FROM "+table);
        space=jdbc.queryForObject("SELECT id FROM workspace WHERE code='a'",String.class);
        other=jdbc.queryForObject("SELECT id FROM workspace WHERE code='b'",String.class);
        actor=jdbc.queryForObject("SELECT id FROM user_account WHERE username='owner.a'",String.class);
        String app=jdbc.queryForObject("SELECT id FROM application WHERE workspace_id=? LIMIT 1",String.class,space);
        seed=jobs.create("owner.a",space,id(),new JobService.CreateJob(app,"种子元数据","HISTORY_BODY_NEVER_RETURN",List.of(),"zh-CN")).jobId();
        jdbc.update("UPDATE ai_job SET created_at='2026-01-01 00:00:00.000' WHERE id=?",seed);
    }
    void row(String id,String title,String state,String time) {
        // Metadata-only fixtures never execute or decrypt cloned payloads.
        jdbc.update("""
            INSERT INTO ai_job(id,workspace_id,application_id,actor_id,title,status,input_cipher,input_hash,accepted_date,output_language,created_at,queue_expires_at,payload_expires_at)
            SELECT ?,workspace_id,application_id,actor_id,?,?,input_cipher,input_hash,accepted_date,output_language,?,queue_expires_at,payload_expires_at FROM ai_job WHERE id=?
            """,id,title,state,time,seed);
    }
    JobService.HistoryPage page(String q,String state,JobService.HistoryCursor cursor,int limit) {
        return jobs.history("owner.a",space,q,state,cursor==null?null:cursor.createdAt().toString(),cursor==null?null:cursor.id(),limit);
    }
    @Test void moreThanFiftyRowsWithEqualTimesPageExactlyAndNewRowsDoNotShiftCursor() {
        var expected=new ArrayList<String>();
        for(int n=1;n<=60;n++){String key=String.format("%08x-1111-4111-8111-111111111111",n);row(key,"历史条目","SUCCEEDED","2026-10-30 00:00:00.123");expected.addFirst(key);}
        var first=page("历史条目",null,null,25);assertEquals(expected.subList(0,25),first.items().stream().map(JobService.Job::jobId).toList());
        row(id(),"历史条目","QUEUED","2026-10-31 00:00:00.000");
        var second=page("历史条目",null,first.nextCursor(),25);
        var third=page("历史条目",null,second.nextCursor(),25);
        assertEquals(expected.subList(25,50),second.items().stream().map(JobService.Job::jobId).toList());
        assertEquals(expected.subList(50,60),third.items().stream().map(JobService.Job::jobId).toList());assertNull(third.nextCursor());
        assertEquals(50,jobs.list("owner.a",space).size());
        assertEquals(25,page("历史条目",null,null,25).items().size());
    }
    @Test void literalSearchAndStatusGroupsAreWorkspaceQueries() {
        String literal=id();row(literal,"Literal %_\\ Café","FAILED","2026-10-30 01:00:00.000");
        row(id(),"普通标题","UNKNOWN","2026-10-30 02:00:00.000");
        row(id(),"运行中","RUNNING","2026-10-30 03:00:00.000");
        assertEquals(List.of(literal),page("%_\\",null,null,25).items().stream().map(JobService.Job::jobId).toList());
        assertEquals(1,page(" Cafe\u0301 ","FAILED",null,25).items().size());
        assertEquals(literal,page(literal.substring(0,8),null,null,25).items().getFirst().jobId());
        assertEquals(2,page(null,"ATTENTION",null,25).items().size());assertEquals(2,page(" ","ACTIVE",null,25).items().size());
        assertEquals(1,page(null,"RUNNING",null,25).items().size());
        assertTrue(page("' OR 1=1 --",null,null,25).items().isEmpty());
        assertTrue(page("没有此任务",null,null,25).items().isEmpty());assertNull(page("没有此任务",null,null,25).nextCursor());
    }
    @Test void rolesCrossWorkspaceAndRevocationAreCheckedBeforeFilters() {
        for(String user:List.of("owner.a","operator.a","viewer.a"))assertEquals(seed,jobs.history(user,space,null,null,null,null,25).items().getFirst().jobId());
        assertEquals(404,assertThrows(ApiException.class,()->jobs.history("owner.b",space,null,"INVALID",null,null,0)).status);
        assertTrue(jobs.history("owner.b",other,null,null,null,null,25).items().isEmpty());
        jdbc.update("UPDATE tenant_member tm JOIN workspace w ON w.tenant_id=tm.tenant_id SET tm.active=FALSE WHERE w.id=? AND tm.user_id=?",space,actor);
        try{assertEquals(404,assertThrows(ApiException.class,()->page(null,null,null,25)).status);}
        finally{jdbc.update("UPDATE tenant_member tm JOIN workspace w ON w.tenant_id=tm.tenant_id SET tm.active=TRUE WHERE w.id=? AND tm.user_id=?",space,actor);}
    }
    @Test void rejectsMalformedOrUnboundedQueriesWithoutSqlErrors() {
        for(int limit:List.of(0,101))assertEquals(422,assertThrows(ApiException.class,()->page(null,null,null,limit)).status);
        assertThrows(ApiException.class,()->page("文".repeat(101),null,null,25));
        assertDoesNotThrow(()->page("😀".repeat(100),null,null,25));
        for(String state:List.of("all","QUEUED') OR 1=1 --",""))assertThrows(ApiException.class,()->page(null,state,null,25));
        for(String time:List.of("bad","0999-01-01T00:00:00Z","+10000-01-01T00:00:00Z","2026-10-31T00:00:00.000001Z"))
            assertEquals(422,assertThrows(ApiException.class,()->jobs.history("owner.a",space,null,null,time,id(),25)).status);
        assertThrows(ApiException.class,()->jobs.history("owner.a",space,null,null,null,id(),25));
        assertThrows(ApiException.class,()->jobs.history("owner.a",space,null,null,"2026-10-31T00:00:00Z",null,25));
        assertThrows(ApiException.class,()->jobs.history("owner.a",space,null,null,"2026-10-31T00:00:00Z","not-uuid",25));
    }
    @Test void foreignOrDeletedAnchorsAreOnlyLocalSortPointsAndExpiredMetadataRemains() {
        row(id(),"旧任务","SUCCEEDED","2026-01-02 00:00:00.000");
        var first=page(null,null,null,1);String anchor=first.items().getFirst().jobId();
        jdbc.update("DELETE FROM ai_job WHERE id=?",anchor);
        assertEquals(seed,page(null,null,first.nextCursor(),1).items().getFirst().jobId());
        jdbc.update("UPDATE ai_job SET input_cipher=NULL,payload_deleted_at=CURRENT_TIMESTAMP(3),payload_expires_at='2026-01-03 00:00:00' WHERE id=?",seed);
        assertEquals(seed,jobs.history("viewer.a",space,null,null,"2026-01-04T00:00:00Z",id(),25).items().getFirst().jobId());
    }
    @Test void metadataSerializationAndReadsHaveNoBodyOrBusinessSideEffects() {
        var budget=jdbc.queryForList("SELECT * FROM budget_account ORDER BY workspace_id,accepted_date");
        var entries=jdbc.queryForList("SELECT * FROM budget_entry ORDER BY job_id");
        long audits=jdbc.queryForObject("SELECT COUNT(*) FROM audit_event",Long.class),calls=fake.callCount();
        String serialized=json.writeValueAsString(page(null,null,null,25));
        for(String secret:List.of("HISTORY_BODY_NEVER_RETURN","input_cipher","inputText","masterKey","detail_json"))assertFalse(serialized.contains(secret));
        assertEquals(audits,jdbc.queryForObject("SELECT COUNT(*) FROM audit_event",Long.class));assertEquals(calls,fake.callCount());
        assertEquals(budget,jdbc.queryForList("SELECT * FROM budget_account ORDER BY workspace_id,accepted_date"));
        assertEquals(entries,jdbc.queryForList("SELECT * FROM budget_entry ORDER BY job_id"));
        assertEquals(seed,jobs.get("viewer.a",space,seed).jobId());
    }
}
