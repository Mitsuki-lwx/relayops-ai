package dev.relayops.job;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class ModelGatewayTest {
    static final List<DocumentInput.Paragraph> PARAGRAPHS = DocumentInput.paragraphs("必须持久化任务，再返回受理。");
    ModelGateway.Prepared prepare(ModelGateway gateway) { return gateway.prepare(gateway.currentProfile().profileId(), "协议验证", PARAGRAPHS, "zh-CN"); }

    @Test void supportsExplicitSuccessfulDataEnvelopeWithoutRelaxingInnerValidation() throws Exception {
        try (var stub = new StubProvider(); var gateway = stub.gateway(3)) {
            var completion = java.util.Map.of("choices", List.of(java.util.Map.of("finish_reason", "stop", "message",
                    java.util.Map.of("role", "assistant", "content", stub.validAnalysis()))));
            stub.raw = stub.json.writeValueAsString(java.util.Map.of("success", true, "data", completion));
            DocumentResult.validate(prepare(gateway).invoke(), PARAGRAPHS);
            for (var envelope : List.of(java.util.Map.of("success", false, "data", completion),
                    java.util.Map.of("success", "true", "data", completion), java.util.Map.of("data", completion),
                    java.util.Map.of("success", true, "error", "private-provider-body", "data", completion),
                    java.util.Map.of("success", true, "data", java.util.Map.of("error", "private-provider-body")))) {
                stub.raw = stub.json.writeValueAsString(envelope);
                assertEquals("OUTPUT_INVALID", assertThrows(ModelGateway.Failure.class, () -> prepare(gateway).invoke()).code);
            }
            stub.raw = stub.json.writeValueAsString(java.util.Map.of("success", true, "data", java.util.Map.of("choices",
                    List.of(java.util.Map.of("finish_reason", "length", "message", java.util.Map.of("content", stub.validAnalysis()))))));
            assertEquals("OUTPUT_TRUNCATED", assertThrows(ModelGateway.Failure.class, () -> prepare(gateway).invoke()).code);
            assertEquals(7, stub.calls.get());
        }
    }

    @Test void acceptsNullAndEmptyToolMetadataWithoutTreatingItAsAToolRequest() throws Exception {
        try (var stub = new StubProvider(); var gateway = stub.gateway(3)) {
            for (Object tools : new Object[]{null, List.of()}) {
                var message = new java.util.LinkedHashMap<String,Object>();
                message.put("role", "assistant"); message.put("content", stub.validAnalysis());
                message.put("tool_calls", tools); message.put("function_call", null);
                stub.raw = stub.json.writeValueAsString(java.util.Map.of("choices", List.of(java.util.Map.of("finish_reason", "stop", "message", message))));
                DocumentResult.validate(prepare(gateway).invoke(), PARAGRAPHS);
            }
            assertEquals(2, stub.calls.get());
        }
    }
    @Test void rejectsActualMalformedOrLegacyToolRequestsEvenWithValidText() throws Exception {
        try (var stub = new StubProvider(); var gateway = stub.gateway(3)) {
            for (var request : List.of(java.util.Map.of("tool_calls", List.of(java.util.Map.of("id", "call-1"))),
                    java.util.Map.of("tool_calls", java.util.Map.of("name", "unsafe")), java.util.Map.of("function_call", java.util.Map.of("name", "unsafe")))) {
                var message = new java.util.LinkedHashMap<String,Object>();
                message.put("role", "assistant"); message.put("content", stub.validAnalysis()); message.putAll(request);
                stub.raw = stub.json.writeValueAsString(java.util.Map.of("choices", List.of(java.util.Map.of("finish_reason", "stop", "message", message))));
                assertEquals("OUTPUT_INVALID", assertThrows(ModelGateway.Failure.class, () -> prepare(gateway).invoke()).code);
            }
            assertEquals(3, stub.calls.get());
        }
    }

    @Test void validatesEndpointAndSecretsWithoutPrintingThem() throws Exception {
        try (var stub = new StubProvider()) {
            for (String endpoint : List.of("http://example.com/chat/completions", "https://secret:password@example.com/chat/completions", "https://example.com/chat/completions?token=secret", "https://example.com/other")) {
                var ex = assertThrows(IllegalStateException.class, () -> new ModelGateway(new FakeModel(), stub.json, "compatible", endpoint, "private-key", "model", 60, 4096, "max_tokens", true, false));
                assertFalse(ex.getMessage().contains("private-key")); assertFalse(ex.getMessage().contains(endpoint));
            }
            assertThrows(IllegalStateException.class, () -> new ModelGateway(new FakeModel(), stub.json, "compatible", stub.endpoint(), "", "model", 60, 4096, "max_tokens", true, true));
            assertEquals(0, stub.calls.get());
        }
    }
    @Test void sendsProtocolAndBindsServerMetadata() throws Exception {
        try (var stub = new StubProvider(); var gateway = stub.gateway(3)) {
            var result = prepare(gateway).invoke();
            DocumentResult.validate(result, PARAGRAPHS);
            assertEquals("协议验证", result.title()); assertEquals(gateway.currentProfile().profileId(), result.modelProfile());
            assertEquals("Bearer test-key-not-real", stub.authorization);
            assertEquals("fixture-model", stub.request.path("model").asText());
            assertEquals("json_object", stub.request.path("response_format").path("type").asText());
            assertEquals(4096, stub.request.path("max_tokens").asInt());
            assertFalse(stub.request.path("stream").asBoolean());
            assertTrue(stub.request.path("messages").get(1).path("content").asText().contains("p0001"));
            assertEquals(1, stub.calls.get());
        }
    }
    @Test void supportsExplicitTokenParameterAndDisablingJsonMode() throws Exception {
        try (var stub = new StubProvider(); var gateway = new ModelGateway(new FakeModel(), stub.json, "compatible", stub.endpoint(), "test-key", "fixture-model", 3, 2048, "max_completion_tokens", false, true)) {
            prepare(gateway).invoke();
            assertTrue(stub.request.has("max_completion_tokens")); assertFalse(stub.request.has("response_format"));
        }
    }
    @Test void knownRejectionsHaveSafeCodesAndNoApplicationRetries() throws Exception {
        try (var stub = new StubProvider(); var gateway = stub.gateway(3)) {
            for (int status : List.of(400, 401, 402, 429)) {
                stub.status = status; stub.raw = "provider-secret-error";
                assertEquals("MODEL_REJECTED", assertThrows(ModelGateway.Failure.class, () -> prepare(gateway).invoke()).code);
            }
            assertEquals(4, stub.calls.get());
        }
    }
    @Test void errorEnvelopesNeverBecomeSuccessfulResultsOrExposeProviderText() throws Exception {
        try (var stub = new StubProvider(); var gateway = stub.gateway(3)) {
            stub.raw = "{\"error\":{\"message\":\"private-provider-body\"}}";
            var failure = assertThrows(ModelGateway.Failure.class, () -> prepare(gateway).invoke());
            assertEquals("OUTPUT_INVALID", failure.code);
            assertEquals("OUTPUT_INVALID", failure.getMessage());
            assertEquals(1, stub.calls.get());
        }
    }
    @Test void rejectsTruncationRefusalAndMalformedOutput() throws Exception {
        try (var stub = new StubProvider(); var gateway = stub.gateway(3)) {
            stub.finish = "length";
            assertEquals("OUTPUT_TRUNCATED", assertThrows(ModelGateway.Failure.class, () -> prepare(gateway).invoke()).code);
            stub.finish = "content_filter";
            assertEquals("MODEL_REFUSED", assertThrows(ModelGateway.Failure.class, () -> prepare(gateway).invoke()).code);
            stub.finish = "stop"; stub.content = "not JSON";
            assertEquals("OUTPUT_INVALID", assertThrows(ModelGateway.Failure.class, () -> prepare(gateway).invoke()).code);
            stub.raw = "{}";
            assertEquals("OUTPUT_INVALID", assertThrows(ModelGateway.Failure.class, () -> prepare(gateway).invoke()).code);
        }
    }
    @Test void rejectsOversizedResponses() throws Exception {
        try (var stub = new StubProvider(); var gateway = stub.gateway(3)) {
            stub.raw = "x".repeat(131073);
            assertEquals("OUTPUT_TOO_LARGE", assertThrows(ModelGateway.Failure.class, () -> prepare(gateway).invoke()).code);
            assertEquals(1, stub.calls.get());
        }
    }
    @Test void deadlineIncludesSlowBody() throws Exception {
        try (var stub = new StubProvider(); var gateway = stub.gateway(1)) {
            stub.slowBody = true; stub.delayMillis = 4000;
            long started = System.nanoTime();
            assertThrows(Exception.class, () -> prepare(gateway).invoke());
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3000);
            assertEquals(1, stub.calls.get());
        }
    }
    @Test void redirectsServerErrorsAndDisconnectsRemainUncertainWithoutResend() throws Exception {
        for (String fault : List.of("redirect", "server", "disconnect")) {
            try (var stub = new StubProvider(); var gateway = stub.gateway(2)) {
                if (fault.equals("redirect")) stub.status = 302;
                if (fault.equals("server")) stub.status = 500;
                if (fault.equals("disconnect")) stub.disconnect = true;
                assertThrows(Exception.class, () -> prepare(gateway).invoke());
                assertEquals(1, stub.calls.get(), fault);
            }
        }
    }
    @Test void profileChangesRejectOldConfigurationButAlwaysSupportsOldFake() throws Exception {
        try (var stub = new StubProvider(); var first = stub.gateway(2); var second = stub.gateway(3)) {
            assertNotEquals(first.currentProfile().profileId(), second.currentProfile().profileId());
            assertEquals("MODEL_CONFIG_CHANGED", assertThrows(ModelGateway.Failure.class, () -> second.prepare(first.currentProfile().profileId(), "旧任务", PARAGRAPHS, "zh-CN")).code);
            assertFalse(second.prepare("fake-extract-v1", "旧 Fake", PARAGRAPHS, "zh-CN").profile().billable());
            assertEquals(0, stub.calls.get());
        }
    }
    @Test void missingAcknowledgementKeepsLegacyFingerprintRepresentation() {
        String encoded = JsonMapper.builder().build().writeValueAsString(new JobService.CreateJob("app", "title", "input", List.of(), "zh-CN"));
        assertEquals("{\"applicationId\":\"app\",\"title\":\"title\",\"inputText\":\"input\",\"destinationIds\":[],\"outputLanguage\":\"zh-CN\"}", encoded);
    }
}
