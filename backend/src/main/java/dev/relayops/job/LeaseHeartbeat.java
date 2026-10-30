package dev.relayops.job;

import jakarta.annotation.PreDestroy;
import java.sql.PreparedStatement;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class LeaseHeartbeat {
    private final JdbcTemplate jdbc;
    private final int leaseSeconds;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, Thread.ofPlatform().daemon().name("model-heartbeat-", 0).factory());
    public LeaseHeartbeat(JdbcTemplate jdbc, @Value("${relayops.lease-seconds:120}") int leaseSeconds) {
        if (leaseSeconds < 3 || leaseSeconds > 300) throw new IllegalStateException("Invalid lease duration");
        this.jdbc = jdbc; this.leaseSeconds = leaseSeconds;
    }
    int leaseSeconds() { return leaseSeconds; }
    int renew(JobWorker.Work work) {
        return jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    UPDATE ai_job SET lease_until = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL ? SECOND)
                    WHERE id = ? AND workspace_id = ? AND status = 'RUNNING' AND lease_token = ?
                      AND dispatch_generation = ? AND lease_until > CURRENT_TIMESTAMP(3)
                    """);
            statement.setQueryTimeout(2);
            statement.setInt(1, leaseSeconds); statement.setString(2, work.id()); statement.setString(3, work.workspaceId());
            statement.setString(4, work.token()); statement.setLong(5, work.generation());
            return statement;
        });
    }
    AutoCloseable start(JobWorker.Work work, int requestTimeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(requestTimeoutSeconds + 30L);
        var task = scheduler.scheduleWithFixedDelay(() -> {
            if (System.nanoTime() >= deadline) return;
            try { renew(work); } catch (Exception ignored) { /* Recovery remains responsible; never log SQL/body/key. */ }
        }, leaseSeconds * 1000L / 3, leaseSeconds * 1000L / 3, TimeUnit.MILLISECONDS);
        return () -> task.cancel(false);
    }
    @PreDestroy public void close() { scheduler.shutdownNow(); }
}
