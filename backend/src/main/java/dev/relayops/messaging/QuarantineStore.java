package dev.relayops.messaging;

import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuarantineStore {
    private final JdbcTemplate jdbc;
    public QuarantineStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Diagnostic(String messageId, String bodyHash, String errorCode) {}

    @Transactional
    public Diagnostic save(byte[] body, String messageId, String code) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            jdbc.update("INSERT INTO quarantine_message(message_id, body_hash, error_code) VALUES (?, ?, ?)",
                    ReadyMessage.uuid(messageId) ? messageId : null, hash, code);
            return new Diagnostic(ReadyMessage.uuid(messageId) ? messageId : null, hash, code);
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
