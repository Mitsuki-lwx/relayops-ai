package dev.relayops.delivery;

import dev.relayops.common.ApiException;
import dev.relayops.identity.Access;
import dev.relayops.job.DocumentInput;
import dev.relayops.messaging.OutboxStore;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ReplayService {
    private static final String OPERATION = "CONFIRM_DELIVERY_REPLAY";
    private final JdbcTemplate jdbc;
    private final Access access;
    private final DestinationService catalog;
    private final OutboxStore outbox;
    private final JsonMapper json;

    public ReplayService(JdbcTemplate jdbc, Access access, DestinationService catalog, OutboxStore outbox, JsonMapper json) {
        this.jdbc = jdbc; this.access = access; this.catalog = catalog; this.outbox = outbox; this.json = json;
    }
    public record Preview(Long expectedVersion, String reason) {}
    public record Confirm(Boolean acknowledgeDuplicateDelivery) {}
    public record Plan(String planId, String deliveryId, long deliveryVersion, String destinationName, String endpoint,
                       String payloadHash, int nextRunNo, String reason, Instant expiresAt) {}
    public record Accepted(String deliveryId, String runId, int runNo, String status, long version) {}
    private record Intent(String planId, boolean acknowledged) {}

    private Map<String,Object> lockRoot(String workspace, String id) {
        var rows = jdbc.queryForList("SELECT * FROM delivery WHERE workspace_id=? AND id=? FOR UPDATE", workspace, id);
        if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "交付不存在或不可访问");
        return rows.getFirst();
    }
    private Instant time(Object value) { return ((LocalDateTime) value).toInstant(ZoneOffset.UTC); }
    private void requireLive(Map<String,Object> root) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT payload_expires_at>CURRENT_TIMESTAMP(3) AND payload_deleted_at IS NULL AND OCTET_LENGTH(payload_cipher)>0 FROM delivery WHERE id=?", Boolean.class, root.get("id"))))
            throw new ApiException(410, "PAYLOAD_EXPIRED", "交付载荷已过期或不可用，不能补投");
    }
    private Map<String,Object> latestDead(String id) {
        var run = jdbc.queryForMap("SELECT * FROM delivery_run WHERE delivery_id=? ORDER BY run_no DESC LIMIT 1 FOR UPDATE", id);
        if (!"DEAD".equals(run.get("status"))) throw new ApiException(409, "STATE_CONFLICT", "仅最新已停止轮次可以补投，请刷新交付");
        return run;
    }
    private long requireDestination(String workspace, Map<String,Object> root) {
        var rows = jdbc.queryForList("SELECT enabled,version FROM destination WHERE workspace_id=? AND id=? FOR SHARE", workspace, root.get("destination_id"));
        if (rows.isEmpty() || !Boolean.TRUE.equals(rows.getFirst().get("enabled")) || !catalog.current((String)root.get("catalog_id"), (String)root.get("endpoint")))
            throw new ApiException(422, "DESTINATION_UNAVAILABLE", "目的地未启用或目录已变化，请修复后重新预览");
        return ((Number) rows.getFirst().get("version")).longValue();
    }
    @Transactional
    public Plan preview(String username, String workspace, String delivery, Preview request) {
        var actor = access.require(username, workspace, true, true, true);
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0 || request.reason() == null || request.reason().isBlank())
            throw DocumentInput.invalid("需要当前交付版本和补投原因");
        String reason = DocumentInput.normalize(request.reason()).strip();
        if (reason.codePointCount(0, reason.length()) > 300) throw DocumentInput.invalid("补投原因最多300字符");
        var root = lockRoot(workspace, delivery);
        requireLive(root);
        long version = ((Number) root.get("version")).longValue();
        if (version != request.expectedVersion()) throw new ApiException(409, "VERSION_CONFLICT", "交付版本已变化，请刷新");
        var run = latestDead(delivery);
        long targetVersion = requireDestination(workspace, root);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO replay_plan(id,workspace_id,delivery_id,actor_id,delivery_version,destination_version,payload_hash,catalog_id,endpoint,reason)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, id, workspace, delivery, actor.userId(), version, targetVersion, root.get("payload_hash"), root.get("catalog_id"), root.get("endpoint"), reason);
        audit(workspace, actor.userId(), delivery, "DELIVERY_REPLAY_PREVIEWED", Map.of("planId", id, "reason", reason));
        Instant expires = jdbc.queryForObject("SELECT expires_at FROM replay_plan WHERE id=?", (rs,n) -> time(rs.getObject(1,LocalDateTime.class)), id);
        return new Plan(id, delivery, version, (String)root.get("destination_name"), (String)root.get("endpoint"),
                (String)root.get("payload_hash"), ((Number)run.get("run_no")).intValue()+1, reason, expires);
    }
    @Transactional
    public Accepted confirm(String username, String workspace, String planId, String key, Confirm request) {
        var actor = access.require(username, workspace, true, true, true);
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}")) throw DocumentInput.invalid("需要1至100位ASCII Idempotency-Key");
        if (request == null || !Boolean.TRUE.equals(request.acknowledgeDuplicateDelivery())) throw DocumentInput.invalid("请明确确认重复投递风险");
        var ids = jdbc.queryForList("SELECT delivery_id FROM replay_plan WHERE workspace_id=? AND id=? AND actor_id=?", String.class, workspace, planId, actor.userId());
        if (ids.isEmpty()) throw new ApiException(404, "NOT_FOUND", "补投计划不存在或不可访问");
        String delivery = ids.getFirst();
        var root = lockRoot(workspace, delivery);
        requireLive(root);
        String fingerprint = DocumentInput.hash(json.writeValueAsString(new Intent(planId, true)));
        var previous = jdbc.queryForList("SELECT request_hash,response_json FROM idempotency_record WHERE workspace_id=? AND actor_id=? AND operation=? AND idempotency_key=? FOR UPDATE", workspace, actor.userId(), OPERATION, key);
        if (!previous.isEmpty()) {
            if (!fingerprint.equals(previous.getFirst().get("request_hash"))) throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "此幂等键已用于另一补投意图");
            return json.readValue((String)previous.getFirst().get("response_json"), Accepted.class);
        }
        var plan = jdbc.queryForMap("SELECT *,expires_at>CURRENT_TIMESTAMP(3) AS live FROM replay_plan WHERE workspace_id=? AND id=? AND actor_id=? FOR UPDATE", workspace, planId, actor.userId());
        if (!"READY".equals(plan.get("status"))) throw new ApiException(409, "PLAN_CONSUMED", "计划已经确认，请恢复原确认操作或刷新交付");
        if (((Number)plan.get("live")).intValue() != 1) throw new ApiException(410, "PLAN_EXPIRED", "补投预览已过期，请重新预览");
        long version = ((Number)root.get("version")).longValue();
        if (version != ((Number)plan.get("delivery_version")).longValue()
                || !root.get("payload_hash").equals(plan.get("payload_hash")) || !root.get("catalog_id").equals(plan.get("catalog_id")) || !root.get("endpoint").equals(plan.get("endpoint")))
            throw new ApiException(409, "PLAN_STALE", "交付或目标快照已变化，请重新预览");
        var run = latestDead(delivery);
        if (requireDestination(workspace, root) != ((Number)plan.get("destination_version")).longValue())
            throw new ApiException(409, "PLAN_STALE", "目的地配置已变化，请重新预览");
        outbox.requireCapacity(workspace);
        String next = UUID.randomUUID().toString(); int runNo = ((Number)run.get("run_no")).intValue()+1;
        jdbc.update("INSERT INTO delivery_run(id,workspace_id,delivery_id,run_no,replay_plan_id) VALUES (?,?,?,?,?)", next, workspace, delivery, runNo, planId);
        outbox.enqueueDelivery(workspace, next, 1);
        jdbc.update("UPDATE delivery SET version=version+1 WHERE id=?", delivery);
        jdbc.update("UPDATE replay_plan SET status='CONFIRMED',confirmed_run_id=? WHERE id=?", next, planId);
        var response = new Accepted(delivery, next, runNo, "PENDING", version+1);
        jdbc.update("""
                INSERT INTO idempotency_record(workspace_id,actor_id,operation,idempotency_key,request_hash,job_id,expires_at,response_json)
                VALUES (?,?,?,?,?,?,DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 24 HOUR),?)
                """, workspace, actor.userId(), OPERATION, key, fingerprint, root.get("job_id"), json.writeValueAsString(response));
        audit(workspace, actor.userId(), delivery, "DELIVERY_REPLAY_CONFIRMED", Map.of("planId", planId, "runId", next, "runNo", runNo, "reason", plan.get("reason"), "acknowledgeDuplicateDelivery", true));
        return response;
    }
    private void audit(String workspace, String actor, String delivery, String operation, Map<String,Object> detail) {
        jdbc.update("INSERT INTO audit_event(workspace_id,actor_id,resource_id,operation,detail_json) VALUES (?,?,?,?,?)", workspace, actor, delivery, operation, json.writeValueAsString(detail));
    }
}
