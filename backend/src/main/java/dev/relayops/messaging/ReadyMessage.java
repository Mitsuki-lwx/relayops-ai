package dev.relayops.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

public record ReadyMessage(int schemaVersion, String messageId, String kind, String workspaceId,
                           String aggregateId, long dispatchGeneration, Instant createdAt, String traceId) {
    private static final List<String> FIELDS = List.of("schemaVersion", "messageId", "kind", "workspaceId",
            "aggregateId", "dispatchGeneration", "createdAt", "traceId");

    public static class Invalid extends RuntimeException {
        public final String code;
        public Invalid(String code) { super(code); this.code = code; }
    }

    public static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException ex) { return false; }
    }

    public static ReadyMessage decode(byte[] bytes, JsonMapper json) {
        if (bytes.length > 4096) throw new Invalid("ENVELOPE_TOO_LARGE");
        try {
            var tree = json.readTree(bytes);
            if (tree == null || !tree.isObject() || tree.size() != FIELDS.size()
                    || FIELDS.stream().anyMatch(name -> !tree.hasNonNull(name))) throw new Invalid("MALFORMED_ENVELOPE");
            if (!tree.get("schemaVersion").isIntegralNumber() || tree.get("schemaVersion").intValue() != 1)
                throw new Invalid("UNSUPPORTED_VERSION");
            if (!tree.get("dispatchGeneration").isIntegralNumber()) throw new Invalid("MALFORMED_ENVELOPE");
            var envelope = json.readValue(bytes, ReadyMessage.class);
            if (!uuid(envelope.messageId()) || !uuid(envelope.workspaceId()) || !uuid(envelope.aggregateId())
                    || !uuid(envelope.traceId()) || envelope.dispatchGeneration() < 1 || envelope.createdAt() == null
                    || !List.of("AI_EXECUTE","DELIVERY_SEND").contains(envelope.kind())) throw new Invalid("MALFORMED_ENVELOPE");
            return envelope;
        } catch (Invalid ex) { throw ex; }
        catch (Exception ex) { throw new Invalid("MALFORMED_ENVELOPE"); }
    }
}
