package dev.relayops.identity;

import dev.relayops.common.ApiException;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Access {
    private final JdbcTemplate jdbc;
    public Access(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Member(String userId, String role) {}

    private static final String ROLES = """
            (SELECT workspace_id, user_id, CASE role WHEN 'OWNER' THEN 3 WHEN 'OPERATOR' THEN 2 ELSE 1 END AS rank_value
             FROM workspace_member WHERE active = TRUE
             UNION ALL
             SELECT g.workspace_id, m.user_id, CASE g.role WHEN 'OPERATOR' THEN 2 ELSE 1 END
             FROM organization_unit_member m JOIN workspace_unit_grant g ON g.tenant_id=m.tenant_id AND g.unit_id=m.unit_id
             WHERE m.active = TRUE) r
            """;

    public void lockTenant(String tenantId) {
        if (jdbc.queryForList("SELECT id FROM tenant WHERE id = ? FOR UPDATE", String.class, tenantId).isEmpty())
            throw new ApiException(404, "NOT_FOUND", "公司不存在或不可访问");
    }

    private static String role(int rank) { return rank == 3 ? "OWNER" : rank == 2 ? "OPERATOR" : "VIEWER"; }

    public Member require(String username, String workspaceId, boolean write, boolean body, boolean lock) {
        if (lock) {
            // A current read avoids creating a REPEATABLE READ snapshot before waiting on the tenant lock.
            // Existing workspace ownership is immutable; the shared row lock also allows worker FK checks.
            var tenants = jdbc.queryForList("SELECT tenant_id FROM workspace WHERE id = ? FOR SHARE", String.class, workspaceId);
            if (tenants.isEmpty()) throw new ApiException(404, "NOT_FOUND", "空间不存在或不可访问");
            lockTenant(tenants.getFirst());
            // Preserve the direct-member lock for existing mutation/revocation transactions.
            jdbc.queryForList("SELECT m.user_id FROM workspace_member m JOIN user_account u ON u.id=m.user_id WHERE m.workspace_id=? AND u.username=? FOR UPDATE", workspaceId, username);
        }
        var rows = jdbc.query("""
                SELECT u.id, MAX(r.rank_value) AS effective_rank FROM user_account u
                JOIN tenant_member tm ON tm.user_id=u.id AND tm.active=TRUE
                JOIN workspace w ON w.tenant_id=tm.tenant_id
                JOIN
                """ + ROLES + """
                 ON r.workspace_id=w.id AND r.user_id=u.id
                WHERE u.username=? AND u.enabled=TRUE AND w.id=? GROUP BY u.id
                """, (rs, row) -> new Member(rs.getString("id"), role(rs.getInt("effective_rank"))), username, workspaceId);
        if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "空间不存在或不可访问");
        Member member = rows.getFirst();
        if ((write || body) && member.role().equals("VIEWER"))
            throw new ApiException(403, "FORBIDDEN", "当前角色无权执行此操作");
        return member;
    }

    public Map<String, Object> me(String username) {
        return Map.of("username", username, "workspaces", jdbc.queryForList("""
                SELECT w.id, w.name, w.tenant_id AS tenantId, t.name AS tenantName,
                  CASE MAX(r.rank_value) WHEN 3 THEN 'OWNER' WHEN 2 THEN 'OPERATOR' ELSE 'VIEWER' END AS role
                FROM user_account u JOIN tenant_member tm ON tm.user_id=u.id AND tm.active=TRUE
                JOIN tenant t ON t.id=tm.tenant_id JOIN workspace w ON w.tenant_id=t.id JOIN
                """ + ROLES + """
                 ON r.workspace_id=w.id AND r.user_id=u.id
                WHERE u.username=? AND u.enabled=TRUE GROUP BY w.id,w.name,w.tenant_id,t.name,w.code ORDER BY w.code
                """, username));
    }
}
