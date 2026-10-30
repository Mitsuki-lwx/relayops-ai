package dev.relayops.job;

import dev.relayops.common.ApiException;
import dev.relayops.identity.Access;
import dev.relayops.messaging.OutboxStore;
import dev.relayops.organization.OrganizationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RELAYOPS_DATABASE_TESTS",matches="true")
@SpringBootTest(properties={"relayops.worker-enabled=false","relayops.consumer-enabled=false","relayops.relay-enabled=false","relayops.recovery-enabled=false"})
class OrganizationTest {
    @Autowired OrganizationService org;
    @Autowired Access access;
    @Autowired JobService jobs;
    @Autowired JobWorker worker;
    @Autowired OutboxStore outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    String tenant, otherTenant, space, otherSpace, operator, viewer;
    String id() { return UUID.randomUUID().toString(); }
    String user(String name) { return jdbc.queryForObject("SELECT id FROM user_account WHERE username=?",String.class,name); }
    @BeforeEach void scope() throws Exception {
        try(var c=jdbc.getDataSource().getConnection()) { assertTrue(c.getMetaData().getURL().contains("/relayops_test?"),"Refuse non-test database"); }
        tenant=id(); otherTenant=id(); operator=user("operator.a"); viewer=user("viewer.a");
        jdbc.update("INSERT INTO tenant(id,name) VALUES (?,?),(?,?)",tenant,"组织测试公司",otherTenant,"其他公司");
        for(String name:List.of("owner.a","operator.a","viewer.a")) jdbc.update("INSERT INTO tenant_member(tenant_id,user_id,role) VALUES (?,?,?)",tenant,user(name),name.equals("owner.a")?"ADMIN":"MEMBER");
        jdbc.update("INSERT INTO tenant_member(tenant_id,user_id,role) VALUES (?,?,'ADMIN')",otherTenant,user("owner.b"));
        space=org.createWorkspace("owner.a",tenant,new OrganizationService.NewWorkspace("组内协作")).id();
        otherSpace=org.createWorkspace("owner.b",otherTenant,new OrganizationService.NewWorkspace("其他空间")).id();
    }
    String unit(String name,String kind,String parent) { return org.createUnit("owner.a",tenant,new OrganizationService.NewUnit(name,kind,parent)).id(); }
    String team() { return unit("后端组","TEAM",null); }
    void member(String unit,String user,boolean active) { org.setMembership("owner.a",tenant,unit,user,new OrganizationService.Membership(active)); }
    void grant(String unit,String role) { org.setGrant("owner.a",tenant,space,unit,new OrganizationService.Grant(role)); }
    JobService.CreateJob request() { return new JobService.CreateJob(jdbc.queryForObject("SELECT id FROM application WHERE workspace_id=?",String.class,space),"组织隔离","已经受理的任务继续履行责任。",List.of(),"zh-CN"); }
    JobService.Accepted create(String name,String key) { return jobs.create(name,space,key,request()); }
    void denied(int status,Runnable action) { assertEquals(status,assertThrows(ApiException.class,action::run).status); }
    void finish(String job) { assertTrue(worker.execute(outbox.claim().stream().map(OutboxStore.Claimed::envelope).filter(e->e.aggregateId().equals(job)).findFirst().orElseThrow())); }
    boolean visible(String name) { return ((List<?>)access.me(name).get("workspaces")).stream().anyMatch(row->((Map<?,?>)row).get("id").equals(space)); }

    @Test void hierarchyAndMembershipDoNotImplicitlyGrantData() {
        String department=unit("研发部","DEPARTMENT",null), child=unit("后端组","TEAM",department);
        member(department,operator,true); member(child,viewer,true);
        assertFalse(visible("operator.a")); denied(404,()->jobs.list("operator.a",space));
        grant(department,"OPERATOR"); assertTrue(visible("operator.a"));
        assertFalse(visible("viewer.a")); // Parent department grant is not inherited by a child team's members.
        assertEquals("OWNER",access.require("owner.a",space,true,true,false).role());
        denied(404,()->unit("非法父组","TEAM",child));
        denied(422,()->unit(" ","TEAM",null)); denied(422,()->unit("非法类型","OTHER",null));
    }
    @Test void rolesCombineAcrossGroupsAndNeverDowngradeDirectOwnership() {
        String low=team(), high=team(); member(low,operator,true); member(high,operator,true);
        grant(low,"VIEWER"); assertEquals("VIEWER",access.require("operator.a",space,false,false,false).role());
        denied(403,()->create("operator.a",id()));
        grant(high,"OPERATOR"); assertEquals("OPERATOR",access.require("operator.a",space,true,true,false).role());
        String job=create("operator.a",id()).jobId(); finish(job); jobs.result("operator.a",space,job);
        grant(high,"NONE"); assertEquals("VIEWER",access.require("operator.a",space,false,false,false).role());
        assertEquals(job,jobs.get("operator.a",space,job).jobId()); denied(403,()->jobs.result("operator.a",space,job));
        member(low,user("owner.a"),true); assertEquals("OWNER",access.require("owner.a",space,true,true,false).role());
    }
    @Test void membershipRevocationBlocksAllInteractivePathsButPreservesAcceptedWork() {
        String team=team(); member(team,operator,true); grant(team,"OPERATOR");
        String key=id(); var accepted=create("operator.a",key); String job=accepted.jobId();
        member(team,operator,false);
        assertFalse(visible("operator.a"));
        denied(404,()->jobs.list("operator.a",space)); denied(404,()->jobs.get("operator.a",space,job));
        denied(404,()->jobs.budget("operator.a",space)); denied(404,()->jobs.modelProfile("operator.a",space));
        denied(404,()->create("operator.a",key));
        denied(404,()->jobs.cancel("operator.a",space,job,id(),new JobService.CancelJob(accepted.version())));
        denied(404,()->jobs.retry("operator.a",space,job,id(),new JobService.RetryJob(accepted.version(),"新调用",true)));
        finish(job); assertEquals("SUCCEEDED",jobs.get("owner.a",space,job).status());
        jobs.result("owner.a",space,job); denied(404,()->jobs.result("operator.a",space,job));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ai_attempt WHERE job_id=? AND sent_at IS NOT NULL",Integer.class,job));
    }
    @Test void grantRevocationAndInactiveCompanyMembershipAreCheckedImmediately() {
        String team=team(); member(team,operator,true); grant(team,"OPERATOR"); assertTrue(visible("operator.a"));
        grant(team,"NONE"); assertFalse(visible("operator.a")); denied(404,()->create("operator.a",id()));
        grant(team,"OPERATOR");
        jdbc.update("UPDATE tenant_member SET active=FALSE WHERE tenant_id=? AND user_id=?",tenant,operator);
        assertFalse(visible("operator.a")); denied(404,()->jobs.budget("operator.a",space));
        assertTrue(org.list("operator.a").stream().noneMatch(c->c.get("id").equals(tenant)));
    }
    @Test void crossCompanyIdentifiersAndReferencesAreRejected() {
        String unit=team(); String foreign=org.createUnit("owner.b",otherTenant,new OrganizationService.NewUnit("其他组","TEAM",null)).id();
        denied(404,()->org.details("owner.b",tenant)); denied(403,()->org.details("operator.a",tenant));
        denied(404,()->unit("跨公司父级","TEAM",foreign));
        denied(404,()->member(unit,user("owner.b"),true));
        denied(404,()->org.setGrant("owner.a",tenant,otherSpace,unit,new OrganizationService.Grant("OPERATOR")));
        denied(404,()->jobs.list("owner.b",space));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO workspace_unit_grant(tenant_id,workspace_id,unit_id,role) VALUES (?,?,?,'OPERATOR')",tenant,space,foreign));
        assertThrows(DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO organization_unit_member(tenant_id,unit_id,user_id) VALUES (?,?,?)",tenant,unit,user("owner.b")));
    }
    @Test void governanceAdminAloneCannotReadOrGrantAWorkspace() {
        String unit=team();
        jdbc.update("UPDATE tenant_member SET role='ADMIN' WHERE tenant_id=? AND user_id=?",tenant,operator);
        org.details("operator.a",tenant); denied(404,()->jobs.list("operator.a",space));
        denied(404,()->org.setGrant("operator.a",tenant,space,unit,new OrganizationService.Grant("OPERATOR")));
        jdbc.update("INSERT INTO workspace_member(workspace_id,user_id,role) VALUES (?,?,'VIEWER')",space,operator);
        denied(403,()->org.setGrant("operator.a",tenant,space,unit,new OrganizationService.Grant("OPERATOR")));
        denied(422,()->grant(unit,"OWNER"));
    }
    @Test void targetStateUpdatesAreIdempotentAndAuditedWithoutPayloads() {
        String team=team(); member(team,operator,true); grant(team,"OPERATOR");
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM organization_audit WHERE tenant_id=?",Integer.class,tenant);
        member(team,operator,true); grant(team,"OPERATOR");
        assertEquals(count,jdbc.queryForObject("SELECT COUNT(*) FROM organization_audit WHERE tenant_id=?",Integer.class,tenant));
        assertTrue(jdbc.queryForList("SELECT detail_json FROM organization_audit WHERE tenant_id=?",String.class,tenant).stream().noneMatch(s->s.contains("inputText")||s.contains("api-key")));
    }
    @Test void revocationCommittedWhileCreateWaitsPreventsStaleIdempotencyReplay() throws Exception {
        String team=team(); member(team,operator,true); grant(team,"OPERATOR");
        String key=id(); create("operator.a",key);
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var revoke=pool.submit(()->tx.executeWithoutResult(status->{
                access.lockTenant(tenant); member(team,operator,false); locked.countDown();
                try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch(InterruptedException ex) { throw new RuntimeException(ex); }
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS));
            var replay=pool.submit(()->{ try { create("operator.a",key); return 202; } catch(ApiException ex) { return ex.status; } });
            try { Thread.sleep(150); assertFalse(replay.isDone()); } finally { release.countDown(); }
            revoke.get(5,TimeUnit.SECONDS); assertEquals(404,replay.get(5,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM ai_job WHERE workspace_id=?",Integer.class,space));
    }
}
