package dev.relayops.identity;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="relayops.provision.enabled",havingValue="true")
public class ProvisioningRunner implements ApplicationRunner {
    private final ProvisioningService service;
    private final Environment env;
    private final ConfigurableApplicationContext context;
    public ProvisioningRunner(ProvisioningService service,Environment env,ConfigurableApplicationContext context) {this.service=service;this.env=env;this.context=context;}
    @Override public void run(ApplicationArguments args) {
        // Refuse accidental provisioning through an ordinary running web server.
        if(!"none".equals(env.getProperty("spring.main.web-application-type")))throw new IllegalStateException("Provisioning requires non-Web mode");
        var created=service.provision(env.getProperty("relayops.provision.username"),env.getProperty("RELAYOPS_PROVISION_PASSWORD"),env.getProperty("relayops.provision.company-name"),env.getProperty("relayops.provision.tenant-id"));
        System.out.println("RELAYOPS_PROVISIONED userId="+created.userId()+" tenantId="+created.tenantId()+" workspaceId="+created.workspaceId());
        context.close();
    }
}
