package dev.relayops.observability;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class TaskTracingTest {
    static class Sink implements SpanExporter {
        final List<SpanData> spans = new CopyOnWriteArrayList<>();
        public CompletableResultCode export(Collection<SpanData> values) { spans.addAll(values); return CompletableResultCode.ofSuccess(); }
        public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
        public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
    }
    String attr(SpanData s, String key) { return s.getAttributes().get(AttributeKey.stringKey(key)); }

    @Test void childHierarchyMetadataAndThreadContextAreIsolated() {
        var sink = new Sink();
        try (var tracing = new TaskTracing(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(sink)).build())) {
            for (String job : List.of("job-a", "job-b")) {
                try (var root = tracing.execution("workspace-" + job, job, "job.execute")) {
                    tracing.queue(Instant.now().minusSeconds(2));
                    try (var generation = tracing.stage("model.extract", "generation")) {
                        generation.attribute("langfuse.observation.model.name", "fixture-model");
                        generation.outcome("SUCCEEDED");
                    }
                    root.outcome("DEFERRED"); root.outcome("SUCCEEDED");
                }
                assertFalse(Span.current().getSpanContext().isValid());
            }
            assertEquals(6, sink.spans.size());
            var roots = sink.spans.stream().filter(s -> s.getName().equals("job.execute")).toList();
            assertNotEquals(roots.get(0).getTraceId(), roots.get(1).getTraceId());
            for (var root : roots) {
                assertEquals(io.opentelemetry.api.trace.StatusCode.OK, root.getStatus().getStatusCode());
                for (var child : sink.spans.stream().filter(s -> s.getTraceId().equals(root.getTraceId()) && s != root).toList()) {
                    assertEquals(root.getSpanId(), child.getParentSpanId());
                    assertEquals(attr(root,"langfuse.session.id"), attr(child,"langfuse.session.id"));
                    assertEquals(attr(root,"langfuse.trace.metadata.workspace_id"), attr(child,"langfuse.trace.metadata.workspace_id"));
                }
            }
        }
    }

    @Test void usageWhitelistDoesNotCopyProviderFieldsOrInventMissingValues() {
        var sink = new Sink(); var json = JsonMapper.builder().build();
        try (var tracing = new TaskTracing(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(sink)).build())) {
            try (var root = tracing.execution("workspace", "job", "job.execute")) {
                for (String input : List.of("{\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":7,\"api_key\":\"private-sentinel\"}}", "{}", "{\"usage\":{\"prompt_tokens\":-1,\"completion_tokens\":\"3\",\"total_tokens\":2.5}}")) {
                    try (var generation = tracing.stage("model.extract", "generation")) { TaskTracing.usage(json.readTree(input)); }
                }
            }
            assertEquals("{\"input\":12,\"output\":7}", attr(sink.spans.get(0), "langfuse.observation.usage_details"));
            assertNull(attr(sink.spans.get(1), "langfuse.observation.usage_details"));
            assertNull(attr(sink.spans.get(2), "langfuse.observation.usage_details"));
            assertFalse(sink.spans.toString().contains("private-sentinel"));
        }
    }

    @Test void disabledAndInvalidConfigurationAreNoop() {
        for (boolean enabled : List.of(false,true)) try (var tracing = new TaskTracing(enabled,"http://example.com", "", "", "local")) {
            try (var root = tracing.execution("workspace", "job", "job.execute")) { assertFalse(Span.current().isRecording()); }
            tracing.flush(); assertFalse(Span.current().getSpanContext().isValid());
        }
    }

    @Test void stalledExporterAndFullQueueNeverBlockProducer() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var sink = new Sink() {
            @Override public CompletableResultCode export(Collection<SpanData> values) {
                entered.countDown();
                try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                return CompletableResultCode.ofFailure();
            }
        };
        var provider = SdkTracerProvider.builder().addSpanProcessor(BatchSpanProcessor.builder(sink).setMaxQueueSize(8).setMaxExportBatchSize(1).setScheduleDelay(Duration.ofMillis(1)).build()).build();
        try (var tracing = new TaskTracing(provider)) {
            try (var root = tracing.execution("workspace", "first", "job.execute")) { root.outcome("SUCCEEDED"); }
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                for (int i=0;i<1000;i++) try (var root=tracing.execution("workspace", "job-"+i,"job.execute")) { root.outcome("SUCCEEDED"); }
            });
            assertFalse(Span.current().getSpanContext().isValid());
        } finally { release.countDown(); }
    }
}
