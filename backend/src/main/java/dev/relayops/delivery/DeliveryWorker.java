package dev.relayops.delivery;

import dev.relayops.common.PayloadCipher;
import dev.relayops.job.DocumentInput;
import dev.relayops.messaging.OutboxStore;
import dev.relayops.messaging.ReadyMessage;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class DeliveryWorker {
    private static final Logger LOG=LoggerFactory.getLogger(DeliveryWorker.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final PayloadCipher cipher;
    private final DestinationService catalog;
    private final OutboxStore outbox;
    private final WebhookTransport transport;
    private final boolean enabled;
    public DeliveryWorker(JdbcTemplate jdbc,TransactionTemplate tx,PayloadCipher cipher,DestinationService catalog,OutboxStore outbox,WebhookTransport transport,
            @Value("${relayops.recovery-enabled}") boolean enabled) {
        this.jdbc=jdbc;this.tx=tx;this.cipher=cipher;this.catalog=catalog;this.outbox=outbox;this.transport=transport;this.enabled=enabled;
    }
    record Work(String workspace,String delivery,String run,String attempt,String token,String endpoint,String keyId,byte[] key,byte[] payload,String hash) {}
    private Instant now() {return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)",(rs,n)->rs.getObject(1,LocalDateTime.class).toInstant(ZoneOffset.UTC));}
    private boolean lock(String workspace,String run) {
        var roots=jdbc.queryForList("SELECT delivery_id FROM delivery_run WHERE workspace_id=? AND id=?",String.class,workspace,run);
        if(roots.isEmpty()) return false;
        jdbc.queryForList("SELECT id FROM delivery WHERE id=? FOR UPDATE",roots.getFirst());
        jdbc.queryForList("SELECT id FROM delivery_run WHERE id=? FOR UPDATE",run);
        return true;
    }
    public boolean execute(ReadyMessage envelope) {
        if(!"DELIVERY_SEND".equals(envelope.kind())) throw new ReadyMessage.Invalid("WRONG_ROUTE_KIND");
        try {
            Work work=tx.execute(s->claim(envelope)); if(work==null) return true;
            WebhookTransport.Outcome outcome;
            try {
                String payload=cipher.decrypt(work.payload(),work.workspace()+":"+work.delivery()+":delivery");
                if(!DocumentInput.hash(payload).equals(work.hash())) throw new IllegalStateException("PAYLOAD_HASH_MISMATCH");
                String secret=cipher.decrypt(work.key(),work.workspace()+":"+work.keyId()+":signing-key");
                outcome=transport.send(work.endpoint(),work.delivery(),work.run(),work.attempt(),work.keyId(),secret,payload,work.hash());
            } catch(Exception ex) {outcome=new WebhookTransport.Outcome(null,"LOCAL_DELIVERY_INVALID",false,0);}
            var finalOutcome=outcome;
            tx.executeWithoutResult(s->{
                if(!lock(work.workspace(),work.run())) return;
                if(jdbc.queryForObject("SELECT COUNT(*) FROM delivery_run WHERE id=? AND status='IN_FLIGHT' AND lease_token=? AND lease_until>CURRENT_TIMESTAMP(3)",Integer.class,work.run(),work.token())!=1) return;
                finish(work.run(),work.attempt(),finalOutcome);
            });
            return true;
        } catch(ReadyMessage.Invalid ex) {throw ex;}
        catch(Exception ex) {LOG.warn("operation=delivery_deferred exceptionType={}",ex.getClass().getSimpleName());return false;}
    }
    private Work claim(ReadyMessage e) {
        if(!lock(e.workspaceId(),e.aggregateId())) throw new ReadyMessage.Invalid("AGGREGATE_NOT_FOUND");
        var run=jdbc.queryForMap("SELECT * FROM delivery_run WHERE id=? FOR UPDATE",e.aggregateId());
        long generation=((Number)run.get("dispatch_generation")).longValue();
        if(e.dispatchGeneration()>generation) throw new ReadyMessage.Invalid("FUTURE_GENERATION");
        if(e.dispatchGeneration()<generation || !run.get("status").equals("PENDING")) return null;
        Instant time=now();
        if(((LocalDateTime)run.get("next_attempt_at")).toInstant(ZoneOffset.UTC).isAfter(time)) return null;
        var d=jdbc.queryForMap("SELECT * FROM delivery WHERE id=? FOR UPDATE",run.get("delivery_id"));
        String invalid=invalid(d,run,time);
        var targets=jdbc.queryForList("SELECT secret_cipher,enabled FROM destination WHERE workspace_id=? AND id=? FOR SHARE",e.workspaceId(),d.get("destination_id"));
        if(targets.isEmpty() || !Boolean.TRUE.equals(targets.getFirst().get("enabled"))) invalid="DESTINATION_DISABLED";
        if(invalid!=null) {dead(e.aggregateId(),(String)d.get("id"),invalid);return null;}
        int count=((Number)run.get("attempt_count")).intValue()+1;
        String token=UUID.randomUUID().toString(),attempt=UUID.randomUUID().toString();
        jdbc.update("UPDATE delivery_run SET status='IN_FLIGHT',attempt_count=?,lease_token=?,lease_until=DATE_ADD(CURRENT_TIMESTAMP(3),INTERVAL 30 SECOND) WHERE id=?",count,token,e.aggregateId());
        jdbc.update("INSERT INTO delivery_attempt(id,workspace_id,run_id,attempt_no,key_id) VALUES (?,?,?,?,?)",attempt,e.workspaceId(),e.aggregateId(),count,d.get("destination_id"));
        return new Work(e.workspaceId(),(String)d.get("id"),e.aggregateId(),attempt,token,(String)d.get("endpoint"),(String)d.get("destination_id"),(byte[])targets.getFirst().get("secret_cipher"),(byte[])d.get("payload_cipher"),(String)d.get("payload_hash"));
    }
    private String invalid(java.util.Map<String,Object> d,java.util.Map<String,Object> r,Instant time) {
        if(!((LocalDateTime)d.get("payload_expires_at")).toInstant(ZoneOffset.UTC).isAfter(time)) return "PAYLOAD_EXPIRED";
        if(!catalog.current((String)d.get("catalog_id"),(String)d.get("endpoint"))) return "CATALOG_CHANGED";
        if(((Number)r.get("attempt_count")).intValue()>=6 || !((LocalDateTime)r.get("deadline_at")).toInstant(ZoneOffset.UTC).isAfter(time)) return "DELIVERY_BUDGET_EXHAUSTED";
        return null;
    }
    private void dead(String run,String delivery,String error) {
        jdbc.update("UPDATE delivery_run SET status='DEAD',error_code=?,lease_token=NULL,lease_until=NULL WHERE id=?",error,run);
        jdbc.update("UPDATE delivery SET version=version+1 WHERE id=?",delivery);
    }
    private void finish(String runId,String attempt,WebhookTransport.Outcome outcome) {
        var r=jdbc.queryForMap("SELECT * FROM delivery_run WHERE id=? FOR UPDATE",runId);
        var d=jdbc.queryForMap("SELECT * FROM delivery WHERE id=? FOR UPDATE",r.get("delivery_id"));
        Instant time=now();int count=((Number)r.get("attempt_count")).intValue();
        String attemptState=outcome.success()?"SUCCEEDED":outcome.error().equals("NETWORK_UNKNOWN") || outcome.error().equals("LEASE_EXPIRED")?"UNKNOWN":"FAILED";
        jdbc.update("UPDATE delivery_attempt SET status=?,http_status=?,error_code=?,completed_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status='STARTED'",attemptState,outcome.status(),outcome.error(),attempt);
        String invalid=invalid(d,r,time);
        if(outcome.success()) {
            jdbc.update("UPDATE delivery_run SET status='SUCCEEDED',error_code=NULL,lease_token=NULL,lease_until=NULL WHERE id=?",runId);
            jdbc.update("UPDATE delivery SET version=version+1 WHERE id=?",d.get("id"));
            return;
        }
        if(!outcome.retry() || invalid!=null) {dead(runId,(String)d.get("id"),invalid!=null?invalid:outcome.error());return;}
        long[] base={1,5,30,120,600};
        long wait=Math.max(outcome.retryAfterMillis(),(long)(base[Math.min(count-1,4)]*1000*ThreadLocalRandom.current().nextDouble(1,1.2)));
        Instant next=time.plusMillis(wait);
        if(!next.isBefore(((LocalDateTime)r.get("deadline_at")).toInstant(ZoneOffset.UTC)) || !next.isBefore(((LocalDateTime)d.get("payload_expires_at")).toInstant(ZoneOffset.UTC))) {
            dead(runId,(String)d.get("id"),"DELIVERY_BUDGET_EXHAUSTED");return;
        }
        jdbc.update("UPDATE delivery_run SET status='RETRY_WAIT',error_code=?,next_attempt_at=?,lease_token=NULL,lease_until=NULL WHERE id=?",outcome.error(),LocalDateTime.ofInstant(next,ZoneOffset.UTC),runId);
        jdbc.update("UPDATE delivery SET version=version+1 WHERE id=?",d.get("id"));
    }
    @Scheduled(fixedDelay=1000) public void tick() {
        if(!enabled)return;
        try {recover();}catch(Exception ex){LOG.warn("operation=delivery_recovery_deferred exceptionType={}",ex.getClass().getSimpleName());}
    }
    public void recover() {
        var candidates=jdbc.queryForList("""
                SELECT id,workspace_id FROM delivery_run WHERE (status='IN_FLIGHT' AND lease_until<=CURRENT_TIMESTAMP(3))
                  OR (status='RETRY_WAIT' AND next_attempt_at<=CURRENT_TIMESTAMP(3))
                  OR (status='PENDING' AND dispatched_at<=DATE_SUB(CURRENT_TIMESTAMP(3),INTERVAL 120 SECOND)) LIMIT 25
                """);
        for(var candidate:candidates) tx.executeWithoutResult(s->{
            String id=(String)candidate.get("id"),workspace=(String)candidate.get("workspace_id");
            if(!lock(workspace,id))return;
            var r=jdbc.queryForMap("SELECT * FROM delivery_run WHERE id=? FOR UPDATE",id);Instant time=now();
            if(r.get("status").equals("IN_FLIGHT")) {
                if(((LocalDateTime)r.get("lease_until")).toInstant(ZoneOffset.UTC).isAfter(time))return;
                var attempts=jdbc.queryForList("SELECT id FROM delivery_attempt WHERE run_id=? AND status='STARTED' ORDER BY attempt_no DESC LIMIT 1",String.class,id);
                if(!attempts.isEmpty())finish(id,attempts.getFirst(),new WebhookTransport.Outcome(null,"LEASE_EXPIRED",true,0));
            } else if((r.get("status").equals("RETRY_WAIT") && !((LocalDateTime)r.get("next_attempt_at")).toInstant(ZoneOffset.UTC).isAfter(time))
                    || (r.get("status").equals("PENDING") && !((LocalDateTime)r.get("dispatched_at")).toInstant(ZoneOffset.UTC).plusSeconds(120).isAfter(time))) {
                long generation=((Number)r.get("dispatch_generation")).longValue()+1;
                jdbc.update("UPDATE delivery_run SET status='PENDING',dispatch_generation=?,dispatched_at=CURRENT_TIMESTAMP(3) WHERE id=?",generation,id);
                outbox.enqueueDelivery(workspace,id,generation);
            }
        });
    }
}
