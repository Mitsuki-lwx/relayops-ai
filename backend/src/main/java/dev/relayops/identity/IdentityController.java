package dev.relayops.identity;

import java.security.Principal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IdentityController {
    private final Access access;
    private final JdbcTemplate jdbc;
    public IdentityController(Access access, JdbcTemplate jdbc) { this.access = access; this.jdbc = jdbc; }

    @GetMapping("/auth/csrf")
    CsrfToken csrf(CsrfToken token) { return token; }

    @GetMapping("/api/v1/me")
    Object me(Principal principal) { return access.me(principal.getName()); }

    @GetMapping("/api/v1/workspaces/{workspaceId}/applications")
    Object applications(Principal principal, @PathVariable String workspaceId) {
        access.require(principal.getName(), workspaceId, false, false, false);
        return jdbc.queryForList("SELECT id, name FROM application WHERE workspace_id = ? AND enabled = TRUE ORDER BY name", workspaceId);
    }
}
