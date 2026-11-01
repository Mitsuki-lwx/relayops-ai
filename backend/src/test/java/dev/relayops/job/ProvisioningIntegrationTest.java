package dev.relayops.job;

import dev.relayops.common.ApiException;
import dev.relayops.identity.Access;
import dev.relayops.identity.ProvisioningService;
import dev.relayops.organization.OrganizationService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RELAYOPS_DATABASE_TESTS",matches="true")
@SpringBootTest(properties={"relayops.worker-enabled=false","relayops.consumer-enabled=false","relayops.relay-enabled=false","relayops.recovery-enabled=false"})
class ProvisioningIntegrationTest {
    @Autowired ProvisioningService service;
    @Autowired OrganizationService organizations;
    @Autowired Access access;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder encoder;
    String username() {return "team-"+UUID.randomUUID();}
    String password() {return "test-only-"+UUID.randomUUID();}
    @BeforeEach void guard() throws Exception {
        try(var c=jdbc.getDataSource().getConnection()) {assertTrue(c.getMetaData().getURL().contains("/relayops_test?"));}
    }
    @Test void newCompanyCreatesRealOwnerWithHashedPassword() {
        String user=username(),pass=password(); var created=service.provision(user,pass,"发布测试公司",null);
        var hash=jdbc.queryForObject("SELECT password_hash FROM user_account WHERE id=?",String.class,created.userId());
        assertNotEquals(pass,hash); assertTrue(encoder.matches(pass,hash));
        assertEquals("ADMIN",organizations.list(user).getFirst().get("role"));
        assertEquals("OWNER",access.require(user,created.workspaceId(),true,true,false).role());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM application WHERE workspace_id=?",Integer.class,created.workspaceId()));
    }
    @Test void addedMemberNeedsExplicitOrganizationGrant() {
        String owner=username(),member=username();var company=service.provision(owner,password(),"授权公司",null);
        var created=service.provision(member,password(),null,company.tenantId());
        assertNull(created.workspaceId());
        assertEquals(404,assertThrows(ApiException.class,()->access.require(member,company.workspaceId(),false,false,false)).status);
        assertEquals(403,assertThrows(ApiException.class,()->organizations.auditEvents(member,company.tenantId(),null,null,null,25)).status);
        String unit=organizations.createUnit(owner,company.tenantId(),new OrganizationService.NewUnit("发布组","TEAM",null)).id();
        organizations.setMembership(owner,company.tenantId(),unit,created.userId(),new OrganizationService.Membership(true));
        organizations.setGrant(owner,company.tenantId(),company.workspaceId(),unit,new OrganizationService.Grant("VIEWER"));
        assertEquals("VIEWER",access.require(member,company.workspaceId(),false,false,false).role());
    }
    @Test void duplicateUserNeverOverwritesPasswordOrCreatesPartialCompany() {
        String user=username(),pass=password();service.provision(user,pass,"原公司",null);
        long companies=jdbc.queryForObject("SELECT COUNT(*) FROM tenant",Long.class);
        assertEquals(409,assertThrows(ApiException.class,()->service.provision(user,password(),"不应创建",null)).status);
        assertEquals(companies,jdbc.queryForObject("SELECT COUNT(*) FROM tenant",Long.class));
        assertTrue(encoder.matches(pass,jdbc.queryForObject("SELECT password_hash FROM user_account WHERE username=?",String.class,user)));
    }
    @Test void invalidInputDoesNotCreateIdentity() {
        long users=jdbc.queryForObject("SELECT COUNT(*) FROM user_account",Long.class);
        for(String pass:List.of("short","replace_with_password","中".repeat(25))) assertEquals(422,assertThrows(ApiException.class,()->service.provision(username(),pass,"公司",null)).status);
        assertEquals(422,assertThrows(ApiException.class,()->service.provision("bad user",password(),"公司",null)).status);
        assertEquals(422,assertThrows(ApiException.class,()->service.provision(username(),password()," ",null)).status);
        assertEquals(404,assertThrows(ApiException.class,()->service.provision(username(),password(),null,UUID.randomUUID().toString())).status);
        assertEquals(users,jdbc.queryForObject("SELECT COUNT(*) FROM user_account",Long.class));
    }
    @Test void concurrentDuplicateProvisionHasOnlyOneCompany() throws Exception {
        String user=username(),company="race-"+UUID.randomUUID();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->{try {service.provision(user,password(),company,null);return 201;}catch(ApiException ex){return ex.status;}});
            var second=pool.submit(()->{try {service.provision(user,password(),company,null);return 201;}catch(ApiException ex){return ex.status;}});
            assertEquals(List.of(201,409),java.util.stream.Stream.of(first.get(),second.get()).sorted().toList());
        }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE name=?",Integer.class,company));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE username=?",Integer.class,user));
    }
}
