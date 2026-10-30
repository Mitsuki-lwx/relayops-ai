package dev.relayops.job;

import dev.relayops.common.ApiException;
import dev.relayops.common.PayloadCipher;
import dev.relayops.identity.Access;
import dev.relayops.messaging.OutboxStore;
import dev.relayops.delivery.DestinationService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class JobService {
    private final JdbcTemplate jdbc;
    private final Access access;
    private final PayloadCipher cipher;
    private final JsonMapper json;
    private final OutboxStore outbox;
    private final ModelGateway models;
    private final DestinationService destinations;

    public JobService(JdbcTemplate jdbc, Access access, PayloadCipher cipher, JsonMapper json, OutboxStore outbox, ModelGateway models, DestinationService destinations) {
        this.jdbc = jdbc; this.access = access; this.cipher = cipher; this.json = json; this.outbox = outbox;
        this.models = models;
        this.destinations = destinations;
    }

    public record CreateJob(String applicationId, String title, String inputText, List<String> destinationIds, String outputLanguage,
                            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Boolean acknowledgeExternalProcessing) {
        public CreateJob(String applicationId, String title, String inputText, List<String> destinationIds, String outputLanguage) {
            this(applicationId, title, inputText, destinationIds, outputLanguage, null);
        }
    }
    public record Accepted(String jobId, String status, long version, Instant payloadExpiresAt) {}
    public record CancelJob(Long expectedVersion) {}
    public record RetryJob(Long expectedVersion, String reason, Boolean acknowledgeDuplicateCost) {}
    private record CancelIntent(String jobId, long expectedVersion) {}
    private record RetryIntent(String jobId, long expectedVersion, String reason, boolean acknowledgeDuplicateCost) {}
    public record Job(String jobId, String applicationId, String title, String status, String modelProfile,
                      String errorCode, long version, Instant createdAt, Instant payloadExpiresAt, int modelCalls, String parentJobId) {}

    private Job map(ResultSet rs, int row) throws SQLException {
        return new Job(rs.getString("id"), rs.getString("application_id"), rs.getString("title"), rs.getString("status"),
                rs.getString("model_profile"), rs.getString("error_code"), rs.getLong("version"),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getObject("payload_expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getInt("model_calls"), rs.getString("parent_job_id"));
    }

    private static final String SELECT = """
            SELECT j.*, (SELECT COUNT(*) FROM ai_attempt a WHERE a.job_id = j.id AND a.sent_at IS NOT NULL) AS model_calls
            FROM ai_job j
            """;

    @Transactional
    public Accepted create(String username, String workspaceId, String key, CreateJob request) {
        Access.Member member = access.require(username, workspaceId, true, false, true);
        requireKey(key);
        CreateJob normalized = normalize(request);
        String fingerprint = DocumentInput.hash(json.writeValueAsString(normalized));
        Accepted previous = replay(member, workspaceId, "CREATE_JOB", key, fingerprint);
        if (previous != null) return previous;
        if (models.currentProfile().billable() && !Boolean.TRUE.equals(normalized.acknowledgeExternalProcessing()))
            throw DocumentInput.invalid("请确认将文档发送给已配置的模型供应商及可能的费用");
        LocalDate date = lockAdmission(workspaceId, normalized.applicationId());
        Accepted response = admit(member, workspaceId, date, normalized, null);
        remember(member, workspaceId, "CREATE_JOB", key, fingerprint, response);
        return response;
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,100}")) throw DocumentInput.invalid("需要 1 至 100 位 ASCII Idempotency-Key");
    }

    private CreateJob normalize(CreateJob request) {
        if (request == null || request.applicationId() == null || request.title() == null
                || request.title().isBlank() || request.title().length() > 100) throw DocumentInput.invalid("请选择应用，标题为 1 至 100 字符");
        var targets = request.destinationIds() == null ? List.<String>of() : request.destinationIds();
        if (targets.size()>3 || targets.stream().anyMatch(id->!dev.relayops.messaging.ReadyMessage.uuid(id)) || targets.stream().distinct().count()!=targets.size())
            throw DocumentInput.invalid("最多选择三个不同的有效目的地");
        String language = request.outputLanguage() == null ? "zh-CN" : request.outputLanguage();
        if (!List.of("zh-CN", "en-US").contains(language)) throw DocumentInput.invalid("语言只支持 zh-CN 或 en-US");
        String input = DocumentInput.normalize(request.inputText());
        DocumentInput.paragraphs(input);
        return new CreateJob(request.applicationId(), request.title().strip(), input, targets.stream().sorted().toList(), language,
                Boolean.TRUE.equals(request.acknowledgeExternalProcessing()) ? true : null);
    }

    private Accepted replay(Access.Member member, String workspaceId, String operation, String key, String fingerprint) {
        // Every public entry point holds the current member row before inspecting a prior response.
        record Stored(String hash, String response) {}
        var previous = jdbc.query("""
                SELECT request_hash, response_json FROM idempotency_record
                WHERE workspace_id = ? AND actor_id = ? AND operation = ? AND idempotency_key = ?
                """, (rs, row) -> new Stored(rs.getString(1), rs.getString(2)), workspaceId, member.userId(), operation, key);
        if (previous.isEmpty()) return null;
        var stored = previous.getFirst();
        if (!stored.hash().equals(fingerprint)) throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED", "此幂等键已用于不同请求");
        return json.readValue(stored.response(), Accepted.class);
    }

    private LocalDate lockAdmission(String workspaceId, String applicationId) {
        var apps = jdbc.queryForList("SELECT id FROM application WHERE id = ? AND workspace_id = ? AND enabled = TRUE FOR UPDATE", applicationId, workspaceId);
        if (apps.isEmpty()) throw new ApiException(404, "NOT_FOUND", "应用不存在或不可访问");
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        jdbc.update("INSERT IGNORE INTO budget_account(workspace_id, accepted_date) VALUES (?, ?)", workspaceId, date);
        jdbc.queryForObject("SELECT daily_limit FROM budget_account WHERE workspace_id = ? AND accepted_date = ? FOR UPDATE", Integer.class, workspaceId, date);
        return date;
    }

    private Accepted admit(Access.Member member, String workspaceId, LocalDate date, CreateJob request, String parentJobId) {
        outbox.requireCapacity(workspaceId);
        int reserved = jdbc.update("""
                UPDATE budget_account SET reserved = reserved + 1
                WHERE workspace_id = ? AND accepted_date = ? AND reserved + spent + uncertain < daily_limit
                """, workspaceId, date);
        if (reserved == 0) throw new ApiException(429, "QUOTA_EXCEEDED", "本空间当日调用额度已用尽");
        String id = UUID.randomUUID().toString();
        var profile = models.currentProfile();
        jdbc.update("""
                INSERT INTO ai_job(id, workspace_id, application_id, actor_id, title, status, input_cipher, input_hash,
                  accepted_date, output_language, parent_job_id, model_profile, prompt_version, schema_version, queue_expires_at, payload_expires_at)
                VALUES (?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?, ?, ?, ?, ?, ?, DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 10 MINUTE), DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 7 DAY))
                """, id, workspaceId, request.applicationId(), member.userId(), request.title(),
                cipher.encrypt(request.inputText(), workspaceId + ":" + id + ":input"), DocumentInput.hash(request.inputText()), date,
                request.outputLanguage(), parentJobId, profile.profileId(), profile.promptVersion(), profile.schemaVersion());
        jdbc.update("INSERT INTO budget_entry(job_id, workspace_id, accepted_date, state) VALUES (?, ?, ?, 'RESERVED')", id, workspaceId, date);
        destinations.freeze(workspaceId,id,request.destinationIds());
        outbox.enqueue(workspaceId, id, 1);
        audit(workspaceId, member.userId(), id, "JOB_ACCEPTED");
        return accepted(workspaceId, id);
    }

    private void remember(Access.Member member, String workspaceId, String operation, String key, String fingerprint, Accepted response) {
        jdbc.update("""
                INSERT INTO idempotency_record(workspace_id, actor_id, operation, idempotency_key, request_hash, job_id, expires_at, response_json)
                VALUES (?, ?, ?, ?, ?, ?, DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 24 HOUR), ?)
                """, workspaceId, member.userId(), operation, key, fingerprint, response.jobId(), json.writeValueAsString(response));
    }

    private Accepted accepted(String workspaceId, String id) {
        // Replay the original acceptance representation; current status is available from GET.
        return jdbc.queryForObject("SELECT payload_expires_at FROM ai_job WHERE workspace_id = ? AND id = ?",
                (rs, row) -> new Accepted(id, "QUEUED", 0, rs.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC)), workspaceId, id);
    }

    private record Source(String id, String applicationId, String title, String status, String modelProfile,
                          String language, LocalDate acceptedDate, long version, Instant expiresAt, byte[] input) {}

    private Source source(String workspaceId, String id, boolean lock) {
        return jdbc.query("SELECT * FROM ai_job WHERE workspace_id = ? AND id = ?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Source(rs.getString("id"), rs.getString("application_id"), rs.getString("title"), rs.getString("status"),
                        rs.getString("model_profile"), rs.getString("output_language"), rs.getDate("accepted_date").toLocalDate(),
                        rs.getLong("version"), rs.getObject("payload_expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getBytes("input_cipher")),
                workspaceId, id).stream().findFirst().orElseThrow(() -> new ApiException(404, "NOT_FOUND", "任务不存在或不可访问"));
    }

    private static long requireVersion(Long version) {
        if (version == null || version < 0) throw DocumentInput.invalid("需要有效的 expectedVersion");
        return version;
    }

    private static void compareVersion(Source source, long expected) {
        if (source.version() != expected) throw new ApiException(409, "VERSION_CONFLICT", "任务版本已变化，请刷新后重试");
    }

    private void requireLiveInput(Source source) {
        Instant now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", (rs, row) -> rs.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC));
        if (!source.expiresAt().isAfter(now) || source.input() == null || source.input().length == 0)
            throw new ApiException(410, "PAYLOAD_EXPIRED", "来源正文已过期或不可用，不能重跑");
    }

    @Transactional
    public Accepted cancel(String username, String workspaceId, String jobId, String key, CancelJob request) {
        Access.Member member = access.require(username, workspaceId, true, false, true);
        requireKey(key);
        long expected = requireVersion(request == null ? null : request.expectedVersion());
        Source initial = source(workspaceId, jobId, false);
        String fingerprint = DocumentInput.hash(json.writeValueAsString(new CancelIntent(jobId, expected)));
        Accepted previous = replay(member, workspaceId, "CANCEL_JOB", key, fingerprint);
        if (previous != null) return previous;
        // Budget before Job matches the worker's settlement order.
        jdbc.queryForObject("SELECT daily_limit FROM budget_account WHERE workspace_id = ? AND accepted_date = ? FOR UPDATE", Integer.class, workspaceId, initial.acceptedDate());
        Source current = source(workspaceId, jobId, true);
        compareVersion(current, expected);
        if (!current.status().equals("QUEUED")) throw new ApiException(409, "STATE_CONFLICT", "只有排队中的任务可以取消");
        int released = jdbc.update("UPDATE budget_entry SET state = 'RELEASED' WHERE job_id = ? AND state = 'RESERVED'", jobId);
        if (released != 1) throw new ApiException(409, "STATE_CONFLICT", "任务额度状态已变化，请刷新");
        if (jdbc.update("UPDATE budget_account SET reserved = reserved - 1 WHERE workspace_id = ? AND accepted_date = ? AND reserved > 0", workspaceId, current.acceptedDate()) != 1)
            throw new IllegalStateException("Budget reservation invariant failed");
        jdbc.update("UPDATE ai_job SET status = 'CANCELLED', version = version + 1, completed_at = CURRENT_TIMESTAMP(3) WHERE id = ?", jobId);
        audit(workspaceId, member.userId(), jobId, "JOB_CANCELLED");
        Accepted response = new Accepted(jobId, "CANCELLED", current.version() + 1, current.expiresAt());
        remember(member, workspaceId, "CANCEL_JOB", key, fingerprint, response);
        return response;
    }

    @Transactional
    public Accepted retry(String username, String workspaceId, String jobId, String key, RetryJob request) {
        Access.Member member = access.require(username, workspaceId, true, true, true);
        requireKey(key);
        long expected = requireVersion(request == null ? null : request.expectedVersion());
        if (!Boolean.TRUE.equals(request.acknowledgeDuplicateCost())) throw DocumentInput.invalid("请明确确认新调用及可能的额外费用");
        if (request.reason() == null || request.reason().isBlank()) throw DocumentInput.invalid("请填写重跑原因");
        String reason = DocumentInput.normalize(request.reason());
        if (reason.codePointCount(0, reason.length()) > 300) throw DocumentInput.invalid("重跑原因最多 300 字符");
        Source initial = source(workspaceId, jobId, false);
        requireLiveInput(initial);
        String fingerprint = DocumentInput.hash(json.writeValueAsString(new RetryIntent(jobId, expected, reason, true)));
        Accepted previous = replay(member, workspaceId, "RERUN_JOB", key, fingerprint);
        if (previous != null) return previous;
        // Lock current admission resources before the source Job; never resurrect or refund the source.
        LocalDate date = lockAdmission(workspaceId, initial.applicationId());
        Source current = source(workspaceId, jobId, true);
        requireLiveInput(current);
        compareVersion(current, expected);
        if (!List.of("FAILED", "UNKNOWN").contains(current.status())) throw new ApiException(409, "STATE_CONFLICT", "只有失败或结果未知的任务可以明确重跑");
        String input = cipher.decrypt(current.input(), workspaceId + ":" + jobId + ":input");
        var targetIds=jdbc.queryForList("SELECT destination_id FROM job_destination WHERE workspace_id=? AND job_id=? ORDER BY destination_id",String.class,workspaceId,jobId);
        CreateJob normalized = normalize(new CreateJob(current.applicationId(), current.title(), input, targetIds, current.language()));
        Accepted response = admit(member, workspaceId, date, normalized, jobId);
        jdbc.update("INSERT INTO audit_event(workspace_id, actor_id, resource_id, operation, detail_json) VALUES (?, ?, ?, 'JOB_RERUN_REQUESTED', ?)",
                workspaceId, member.userId(), jobId, json.writeValueAsString(Map.of("reason", reason, "newJobId", response.jobId(),
                        "acknowledgeDuplicateCost", true, "previousModelProfile", current.modelProfile(), "newModelProfile", models.currentProfile().profileId())));
        remember(member, workspaceId, "RERUN_JOB", key, fingerprint, response);
        return response;
    }

    public ModelGateway.Profile modelProfile(String username, String workspaceId) {
        access.require(username, workspaceId, false, false, false);
        return models.currentProfile();
    }

    public List<Job> list(String username, String workspaceId) {
        access.require(username, workspaceId, false, false, false);
        return jdbc.query(SELECT + " WHERE j.workspace_id = ? ORDER BY j.created_at DESC, j.id DESC LIMIT 50", this::map, workspaceId);
    }

    public Job get(String username, String workspaceId, String id) {
        access.require(username, workspaceId, false, false, false);
        return jdbc.query(SELECT + " WHERE j.workspace_id = ? AND j.id = ?", this::map, workspaceId, id).stream()
                .findFirst().orElseThrow(() -> new ApiException(404, "NOT_FOUND", "任务不存在或不可访问"));
    }

    @Transactional
    public Object result(String username, String workspaceId, String id) {
        Access.Member member = access.require(username, workspaceId, false, true, true);
        var rows = jdbc.queryForList("""
                SELECT j.status, j.input_cipher, (j.payload_expires_at <= CURRENT_TIMESTAMP(3)) AS expired, r.result_cipher
                FROM ai_job j LEFT JOIN ai_result r ON r.job_id = j.id AND r.workspace_id = j.workspace_id
                WHERE j.workspace_id = ? AND j.id = ?
                """, workspaceId, id);
        if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "任务不存在或不可访问");
        var row = rows.getFirst();
        if (((Number) row.get("expired")).intValue() != 0) throw new ApiException(410, "PAYLOAD_EXPIRED", "正文已过期");
        if (!row.get("status").equals("SUCCEEDED")) throw new ApiException(409, "RESULT_NOT_READY", "结果尚未就绪");
        String context = workspaceId + ":" + id;
        Object result = json.readTree(cipher.decrypt((byte[]) row.get("result_cipher"), context + ":result"));
        var paragraphs = DocumentInput.paragraphs(cipher.decrypt((byte[]) row.get("input_cipher"), context + ":input"));
        audit(workspaceId, member.userId(), id, "RESULT_READ");
        return Map.of("data", result, "paragraphs", paragraphs);
    }

    public Map<String, Object> budget(String username, String workspaceId) {
        access.require(username, workspaceId, false, false, false);
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var rows = jdbc.queryForList("SELECT daily_limit AS dailyLimit, reserved, spent, uncertain FROM budget_account WHERE workspace_id = ? AND accepted_date = ?", workspaceId, date);
        return Map.of("acceptedDate", date.toString(), "credits", rows.isEmpty() ? Map.of("dailyLimit", 20, "reserved", 0, "spent", 0, "uncertain", 0) : rows.getFirst());
    }

    private void audit(String workspaceId, String actorId, String id, String operation) {
        jdbc.update("INSERT INTO audit_event(workspace_id, actor_id, resource_id, operation) VALUES (?, ?, ?, ?)", workspaceId, actorId, id, operation);
    }
}
