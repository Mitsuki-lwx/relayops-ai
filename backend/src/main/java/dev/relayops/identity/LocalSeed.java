package dev.relayops.identity;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local")
public class LocalSeed implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final String password;

    public LocalSeed(JdbcTemplate jdbc, PasswordEncoder encoder, @Value("${relayops.demo-password}") String password) {
        this.jdbc = jdbc; this.encoder = encoder; this.password = password;
        if (password.codePointCount(0, password.length()) < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72
                || password.startsWith("replace_") || password.contains("CHANGE_ME"))
            throw new IllegalArgumentException("Set RELAYOPS_DEMO_PASSWORD to a new password of at least 12 characters and at most 72 UTF-8 bytes");
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (String code : List.of("a", "b")) {
            var existing = jdbc.queryForList("SELECT id FROM workspace WHERE code = ?", String.class, code);
            if (!existing.isEmpty()) continue;
            String workspaceId = UUID.randomUUID().toString();
            String tenantId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO tenant(id, name) VALUES (?, ?)", tenantId, "演示公司 " + code.toUpperCase());
            jdbc.update("INSERT INTO workspace(id, code, name, tenant_id) VALUES (?, ?, ?, ?)", workspaceId, code, "演示空间 " + code.toUpperCase(), tenantId);
            jdbc.update("INSERT INTO application(id, workspace_id, name) VALUES (?, ?, ?)", UUID.randomUUID().toString(), workspaceId, "技术文档提取");
            for (String role : List.of("OWNER", "OPERATOR", "VIEWER")) {
                String id = UUID.randomUUID().toString();
                jdbc.update("INSERT INTO user_account(id, username, password_hash) VALUES (?, ?, ?)", id, role.toLowerCase() + "." + code, encoder.encode(password));
                jdbc.update("INSERT INTO tenant_member(tenant_id, user_id, role) VALUES (?, ?, ?)", tenantId, id, role.equals("OWNER") ? "ADMIN" : "MEMBER");
                jdbc.update("INSERT INTO workspace_member(workspace_id, user_id, role) VALUES (?, ?, ?)", workspaceId, id, role);
            }
        }
    }
}
