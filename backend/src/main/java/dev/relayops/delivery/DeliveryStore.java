package dev.relayops.delivery;

import dev.relayops.common.ApiException;
import dev.relayops.common.PayloadCipher;
import dev.relayops.identity.Access;
import dev.relayops.job.DocumentInput;
import dev.relayops.messaging.OutboxStore;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

@Service
public class DeliveryStore {
    private final JdbcTemplate jdbc;
    private final Access access;
    private final PayloadCipher cipher;
    private final JsonMapper json;
    private final OutboxStore outbox;
    public DeliveryStore(JdbcTemplate jdbc,Access access,PayloadCipher cipher,JsonMapper json,OutboxStore outbox) { this.jdbc=jdbc;this.access=access;this.cipher=cipher;this.json=json;this.outbox=outbox; }
    public void createForResult(String workspace,String job,String response) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Delivery requires result transaction");
        String payload=json.writeValueAsString(Map.of("schemaVersion","relay.webhook.v1","workspaceId",workspace,"jobId",job,"result",json.readTree(response)));
        var targets=jdbc.queryForList("SELECT * FROM job_destination WHERE workspace_id=? AND job_id=? ORDER BY destination_id",workspace,job);
        for(var target:targets) {
            String id=UUID.randomUUID().toString(),run=UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO delivery(id,workspace_id,job_id,destination_id,catalog_id,endpoint,destination_name,payload_cipher,payload_hash,payload_expires_at)
                    SELECT ?,workspace_id,id,?,?,?,?,?,?,payload_expires_at FROM ai_job WHERE id=? AND workspace_id=?
                    """,id,target.get("destination_id"),target.get("catalog_id"),target.get("endpoint"),target.get("name"),cipher.encrypt(payload,workspace+":"+id+":delivery"),DocumentInput.hash(payload),job,workspace);
            jdbc.update("INSERT INTO delivery_run(id,workspace_id,delivery_id) VALUES (?,?,?)",run,workspace,id);
            outbox.enqueueDelivery(workspace,run,1);
        }
    }
    public record Attempt(String id,int attemptNo,String status,Integer httpStatus,String errorCode,Instant startedAt,Instant completedAt) {}
    public record Run(String id,int runNo,String status,int attemptCount,String errorCode,Instant nextAttemptAt,List<Attempt> attempts) {}
    public record View(String id,String destinationName,String runId,String status,int attemptCount,String errorCode,Instant nextAttemptAt,Instant payloadExpiresAt,List<Attempt> attempts,long version,int runNo,List<Run> runs) {}
    private List<Attempt> attempts(String run) {
        return jdbc.query("SELECT * FROM delivery_attempt WHERE run_id=? ORDER BY attempt_no",(a,i)->new Attempt(a.getString("id"),a.getInt("attempt_no"),a.getString("status"),a.getObject("http_status",Integer.class),a.getString("error_code"),a.getObject("started_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),a.getObject("completed_at",LocalDateTime.class)==null?null:a.getObject("completed_at",LocalDateTime.class).toInstant(ZoneOffset.UTC)),run);
    }
    private List<Run> runs(String delivery) {
        return jdbc.query("SELECT * FROM delivery_run WHERE delivery_id=? ORDER BY run_no DESC",(r,i)->new Run(r.getString("id"),r.getInt("run_no"),r.getString("status"),r.getInt("attempt_count"),r.getString("error_code"),r.getObject("next_attempt_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),attempts(r.getString("id"))),delivery);
    }
    public List<View> list(String username,String workspace,String job) {
        access.require(username,workspace,false,false,false);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM ai_job WHERE workspace_id=? AND id=?",Integer.class,workspace,job)!=1) throw new ApiException(404,"NOT_FOUND","任务不存在或不可访问");
        return jdbc.query("SELECT d.*,r.id AS run_id,r.run_no,r.status,r.attempt_count,r.error_code,r.next_attempt_at FROM delivery d JOIN delivery_run r ON r.delivery_id=d.id WHERE d.workspace_id=? AND d.job_id=? AND r.run_no=(SELECT MAX(x.run_no) FROM delivery_run x WHERE x.delivery_id=d.id) ORDER BY d.id",
            (rs,n)->new View(rs.getString("id"),rs.getString("destination_name"),rs.getString("run_id"),rs.getString("status"),rs.getInt("attempt_count"),rs.getString("error_code"),
                rs.getObject("next_attempt_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),rs.getObject("payload_expires_at",LocalDateTime.class).toInstant(ZoneOffset.UTC),
                attempts(rs.getString("run_id")),rs.getLong("version"),rs.getInt("run_no"),runs(rs.getString("id"))),workspace,job);
    }
}
