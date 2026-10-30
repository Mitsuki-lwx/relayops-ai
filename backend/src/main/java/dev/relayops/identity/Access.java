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

    public Member require(String username, String workspaceId, boolean write, boolean body, boolean lock) {
        var rows = jdbc.query("""
                SELECT m.user_id, m.role FROM workspace_member m
                JOIN user_account u ON u.id = m.user_id
                WHERE u.username = ? AND u.enabled = TRUE AND m.workspace_id = ? AND m.active = TRUE
                """ + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Member(rs.getString("user_id"), rs.getString("role")), username, workspaceId);
        if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "空间不存在或不可访问");
        Member member = rows.getFirst();
        if ((write || body) && member.role().equals("VIEWER"))
            throw new ApiException(403, "FORBIDDEN", "当前角色无权执行此操作");
        return member;
    }

    public Map<String, Object> me(String username) {
        return Map.of("username", username, "workspaces", jdbc.queryForList("""
                SELECT w.id, w.name, m.role FROM workspace w
                JOIN workspace_member m ON m.workspace_id = w.id
                JOIN user_account u ON u.id = m.user_id
                WHERE u.username = ? AND u.enabled = TRUE AND m.active = TRUE ORDER BY w.code
                """, username));
    }
}
