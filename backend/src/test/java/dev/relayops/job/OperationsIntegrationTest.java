package dev.relayops.job;

import dev.relayops.common.ApiException;
import dev.relayops.operations.OperationsService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
class OperationsIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired JobService jobs;
    @Autowired OperationsService operations;
    @Autowired JsonMapper json;
    @Autowired FakeModel fake;
    String space,other,app,otherApp,actor;
    String id(){return UUID.randomUUID().toString();}
    @BeforeEach void fixtures() throws Exception {
        try(var connection=jdbc.getDataSource().getConnection()){assertEquals("relayops_test",connection.getCatalog());}
        jdbc.update("UPDATE ai_job SET parent_job_id=NULL");
        for(var table:List.of("delivery_outbox","delivery_attempt","delivery_run","replay_plan","delivery","job_destination","destination","quarantine_message","outbox_event","audit_event","idempotency_record","ai_result","budget_entry","ai_attempt","ai_job","budget_account"))jdbc.update("DELETE FROM "+table);
        space=jdbc.queryForObject("SELECT id FROM workspace WHERE code='a'",String.class);
        other=jdbc.queryForObject("SELECT id FROM workspace WHERE code='b'",String.class);
        app=jdbc.queryForObject("SELECT id FROM application WHERE workspace_id=? LIMIT 1",String.class,space);
        otherApp=jdbc.queryForObject("SELECT id FROM application WHERE workspace_id=? LIMIT 1",String.class,other);
        actor=jdbc.queryForObject("SELECT id FROM user_account WHERE username='owner.a'",String.class);
    }
    String job(String workspace) {
        return jobs.create(workspace.equals(space)?"owner.a":"owner.b",workspace,id(),new JobService.CreateJob(workspace.equals(space)?app:otherApp,"统计夹具","DO_NOT_RETURN_PAYLOAD",List.of(),"zh-CN")).jobId();
    }
    String delivery(String job,String first,String latest) {
        String destination=id(),delivery=id();
        jdbc.update("INSERT INTO destination(id,workspace_id,name,catalog_id,endpoint,secret_cipher) VALUES (?,?,'统计夹具','demo-local-v1','http://127.0.0.1:8091/relayops/events',?)",destination,space,new byte[]{1});
        jdbc.update("INSERT INTO delivery(id,workspace_id,job_id,destination_id,catalog_id,endpoint,destination_name,payload_cipher,payload_hash,payload_expires_at) VALUES (?,?,?,?,'demo-local-v1','http://127.0.0.1:8091/relayops/events','统计夹具',?,?,DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 7 DAY))",delivery,space,job,destination,new byte[]{2},"0".repeat(64));
        jdbc.update("INSERT INTO delivery_run(id,workspace_id,delivery_id,run_no,status) VALUES (?,?,?,1,?)",id(),space,delivery,first);
        if(latest!=null)jdbc.update("INSERT INTO delivery_run(id,workspace_id,delivery_id,run_no,status) VALUES (?,?,?,2,?)",id(),space,delivery,latest);
        return delivery;
    }
    @Test void overviewIsScopedAndZeroValuesAndEmptyTimesAreReal() {
        for(String user:List.of("owner.a","operator.a","viewer.a")) {
            var view=operations.overview(user,space);
            assertEquals(0,view.jobs().values().stream().mapToLong(Long::longValue).sum());
            assertEquals(0,view.deliveries().values().stream().mapToLong(Long::longValue).sum());
            assertEquals(0,view.aiOutbox().pending());assertNull(view.aiOutbox().oldestPendingAt());assertNull(view.deliveryOutbox().oldestPendingAt());
            assertFalse(view.retention().enabled());assertNotNull(view.observedAt());
        }
        assertEquals(404,assertThrows(ApiException.class,()->operations.overview("owner.b",space)).status);
        assertEquals(403,assertThrows(ApiException.class,()->operations.audit("operator.a",space,null,null,null,25)).status);
        assertEquals(403,assertThrows(ApiException.class,()->operations.audit("viewer.a",space,null,null,null,25)).status);
    }
    @Test void allRetainedJobsAndOnlyLatestDeliveryRunsAreCounted() {
        String first=job(space),second=job(space);job(other);
        jdbc.update("UPDATE ai_job SET status='UNKNOWN' WHERE id=?",second);
        delivery(first,"DEAD","SUCCEEDED");delivery(second,"DEAD",null);
        var view=operations.overview("viewer.a",space);
        assertEquals(2,view.jobs().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(1L,view.jobs().get("QUEUED"));assertEquals(1L,view.jobs().get("UNKNOWN"));
        assertEquals(2,view.deliveries().values().stream().mapToLong(Long::longValue).sum());
        assertEquals(1L,view.deliveries().get("SUCCEEDED"));assertEquals(1L,view.deliveries().get("DEAD"));
        assertFalse(json.writeValueAsString(view).contains("DO_NOT_RETURN_PAYLOAD"));
    }
    @Test void outboxRecoveryAndRetentionUseWorkspaceFactsAndExcludeSentMessages() {
        String first=job(space),second=job(space),foreign=job(other);
        jdbc.update("UPDATE outbox_event SET status='CLAIMED',publish_attempts=2,created_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 60 SECOND) WHERE aggregate_id=?",first);
        jdbc.update("UPDATE outbox_event SET status='SENT',created_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 10 MINUTE) WHERE aggregate_id=?",second);
        jdbc.update("UPDATE ai_job SET status='RUNNING',lease_until=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND),payload_expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id IN (?,?)",first,foreign);
        jdbc.update("UPDATE ai_job SET status='FAILED',input_cipher=NULL,payload_deleted_at=CURRENT_TIMESTAMP(3) WHERE id=?",second);
        String current=delivery(first,"IN_FLIGHT",null),cleared=delivery(second,"DEAD",null);
        String run=jdbc.queryForObject("SELECT id FROM delivery_run WHERE delivery_id=?",String.class,current);
        jdbc.update("UPDATE delivery_run SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",run);
        jdbc.update("UPDATE delivery SET payload_expires_at=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",current);
        jdbc.update("UPDATE delivery SET payload_cipher=NULL,payload_deleted_at=CURRENT_TIMESTAMP(3) WHERE id=?",cleared);
        jdbc.update("INSERT INTO delivery_outbox(id,workspace_id,aggregate_id,dispatch_generation,trace_id,publish_attempts,created_at) VALUES (?,?,?,1,?,1,DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 20 SECOND))",id(),space,run,id());
        var view=operations.overview("operator.a",space);
        assertEquals(1,view.aiOutbox().pending());assertEquals(1,view.aiOutbox().claimed());assertEquals(1,view.aiOutbox().retried());
        assertEquals(jdbc.queryForObject("SELECT created_at FROM outbox_event WHERE aggregate_id=?",(rs,n)->rs.getObject(1,LocalDateTime.class).toInstant(ZoneOffset.UTC),first),view.aiOutbox().oldestPendingAt());
        assertEquals(1,view.deliveryOutbox().pending());assertEquals(0,view.deliveryOutbox().claimed());assertEquals(1,view.deliveryOutbox().retried());
        assertEquals(1,view.recovery().expiredModelLeases());assertEquals(1,view.recovery().expiredDeliveryLeases());
        assertEquals(1,view.retention().expiredJobs());assertEquals(1,view.retention().expiredDeliveries());assertEquals(1,view.retention().clearedJobs());assertEquals(1,view.retention().clearedDeliveries());
    }
    @Test void auditsAreRedactedFilteredAndPagedWithExactLargeStringCursors() {
        String resource=id();
        for(long n:List.of(9007199254741001L,9007199254741002L,9007199254741003L))
            jdbc.update("INSERT INTO audit_event(id,workspace_id,actor_id,resource_id,operation,detail_json,created_at) VALUES (?,?,?,?, 'TEST_EVENT',?, '2026-10-31 01:00:00')",n,space,actor,resource,"{\"secret\":\"DO_NOT_RETURN_DETAILS\"}");
        jdbc.update("INSERT INTO audit_event(workspace_id,resource_id,operation) VALUES (?,?,'FOREIGN_EVENT')",other,id());
        var first=operations.audit("owner.a",space,"TEST_EVENT",resource,null,2);
        assertEquals(List.of("9007199254741003","9007199254741002"),first.items().stream().map(OperationsService.Event::id).toList());
        assertEquals("9007199254741002",first.nextBeforeId());assertEquals("owner.a",first.items().getFirst().actorName());
        jdbc.update("INSERT INTO audit_event(workspace_id,resource_id,operation) VALUES (?,?,'TEST_EVENT')",space,resource);
        var next=operations.audit("owner.a",space,"TEST_EVENT",resource,first.nextBeforeId(),2);
        assertEquals(List.of("9007199254741001"),next.items().stream().map(OperationsService.Event::id).toList());assertNull(next.nextBeforeId());
        var serialized=json.writeValueAsString(first);assertFalse(serialized.contains("DO_NOT_RETURN_DETAILS"));assertFalse(serialized.contains("detail_json"));assertFalse(serialized.contains("FOREIGN_EVENT"));
        assertTrue(operations.audit("owner.a",space,"UNKNOWN_EVENT",null,null,25).items().isEmpty());
        assertTrue(operations.audit("owner.a",space,null,id(),null,25).items().isEmpty());
    }
    @Test void auditInputBoundsAndCurrentRoleAreEnforcedBeforeQuerying() {
        for(String before:List.of("0","-1","1.5","9223372036854775808","1 OR 1=1"))assertEquals(422,assertThrows(ApiException.class,()->operations.audit("owner.a",space,null,null,before,25)).status);
        for(int limit:List.of(0,101))assertThrows(ApiException.class,()->operations.audit("owner.a",space,null,null,null,limit));
        assertThrows(ApiException.class,()->operations.audit("owner.a",space,"TEST_EVENT' OR 1=1",null,null,25));
        assertThrows(ApiException.class,()->operations.audit("owner.a",space,null,"not-a-uuid",null,25));
        jdbc.update("UPDATE workspace_member SET role='VIEWER' WHERE workspace_id=? AND user_id=?",space,actor);
        try {assertNotNull(operations.overview("owner.a",space));assertEquals(403,assertThrows(ApiException.class,()->operations.audit("owner.a",space,null,null,null,25)).status);}
        finally {jdbc.update("UPDATE workspace_member SET role='OWNER' WHERE workspace_id=? AND user_id=?",space,actor);}
    }
    @Test void readsDoNotWriteBusinessFactsAndRevokedMembershipCannotReadOldPages() {
        job(space);long auditCount=jdbc.queryForObject("SELECT COUNT(*) FROM audit_event",Long.class);long calls=fake.callCount();
        var entries=jdbc.queryForList("SELECT * FROM budget_entry ORDER BY job_id");
        var accounts=jdbc.queryForList("SELECT * FROM budget_account ORDER BY workspace_id,accepted_date");
        for(int i=0;i<3;i++){operations.overview("owner.a",space);operations.audit("owner.a",space,null,null,null,25);}
        assertEquals(auditCount,jdbc.queryForObject("SELECT COUNT(*) FROM audit_event",Long.class));assertEquals(calls,fake.callCount());
        assertEquals(entries,jdbc.queryForList("SELECT * FROM budget_entry ORDER BY job_id"));assertEquals(accounts,jdbc.queryForList("SELECT * FROM budget_account ORDER BY workspace_id,accepted_date"));
        jdbc.update("UPDATE tenant_member tm JOIN workspace w ON w.tenant_id=tm.tenant_id SET tm.active=FALSE WHERE w.id=? AND tm.user_id=?",space,actor);
        try {assertEquals(404,assertThrows(ApiException.class,()->operations.overview("owner.a",space)).status);assertEquals(404,assertThrows(ApiException.class,()->operations.audit("owner.a",space,null,null,"9007199254741002",25)).status);}
        finally {jdbc.update("UPDATE tenant_member tm JOIN workspace w ON w.tenant_id=tm.tenant_id SET tm.active=TRUE WHERE w.id=? AND tm.user_id=?",space,actor);}
    }
}
