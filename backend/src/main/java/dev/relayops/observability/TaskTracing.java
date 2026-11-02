package dev.relayops.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Operator telemetry only. No payloads, exception messages or business retries. */
@Component
public class TaskTracing implements AutoCloseable {
    private static final ContextKey<String> JOB = ContextKey.named("relayops.job");
    private static final ContextKey<String> WORKSPACE = ContextKey.named("relayops.workspace");
    private static final ContextKey<String> KIND = ContextKey.named("relayops.kind");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Tracer tracer;
    private final SdkTracerProvider provider;

    @Autowired public TaskTracing(@Value("${relayops.tracing.enabled:false}") boolean enabled,
            @Value("${relayops.tracing.base-url:}") String address,
            @Value("${relayops.tracing.public-key:}") String publicKey,
            @Value("${relayops.tracing.secret-key:}") String secretKey,
            @Value("${relayops.tracing.environment:local}") String environment) {
        SdkTracerProvider configured = null;
        if (enabled) try {
            URI base = URI.create(address);
            boolean local = "http".equals(base.getScheme()) && List.of("localhost", "127.0.0.1", "[::1]").contains(base.getHost());
            if ((!local && !"https".equals(base.getScheme())) || base.getHost() == null || base.getUserInfo() != null
                    || base.getRawQuery() != null || base.getRawFragment() != null || !List.of("", "/").contains(base.getPath())
                    || !publicKey.matches("pk-lf-[A-Za-z0-9-]+") || !secretKey.matches("sk-lf-[A-Za-z0-9-]+")
                    || !environment.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException();
            String auth = Base64.getEncoder().encodeToString((publicKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));
            var exporter = OtlpHttpSpanExporter.builder().setEndpoint(address.replaceAll("/$", "") + "/api/public/otel/v1/traces")
                    .addHeader("Authorization", "Basic " + auth).addHeader("x-langfuse-ingestion-version", "4")
                    .setConnectTimeout(Duration.ofSeconds(1)).setTimeout(Duration.ofSeconds(2)).build();
            configured = SdkTracerProvider.builder().setResource(Resource.create(Attributes.of(
                    AttributeKey.stringKey("service.name"), "relayops", AttributeKey.stringKey("deployment.environment.name"), environment)))
                    .addSpanProcessor(BatchSpanProcessor.builder(exporter).setMaxQueueSize(256).setMaxExportBatchSize(32)
                            .setScheduleDelay(Duration.ofSeconds(1)).setExporterTimeout(Duration.ofSeconds(3)).build()).build();
        } catch (RuntimeException ex) {
            LoggerFactory.getLogger(TaskTracing.class).warn("operation=tracing_disabled reason=INVALID_CONFIGURATION");
        }
        provider = configured;
        tracer = configured == null ? OpenTelemetry.noop().getTracer("relayops")
                : OpenTelemetrySdk.builder().setTracerProvider(configured).build().getTracer("relayops");
    }

    TaskTracing(SdkTracerProvider provider) {
        this.provider = provider;
        this.tracer = OpenTelemetrySdk.builder().setTracerProvider(provider).build().getTracer("relayops");
    }

    public Observation execution(String workspace, String job, String kind) {
        return execution(workspace, job, kind, Instant.now());
    }

    public Observation execution(String workspace, String job, String kind, Instant queued) {
        // Explicit root: a worker thread must never inherit another request/tenant's parent.
        Instant now = Instant.now();
        Span span = tracer.spanBuilder(kind).setNoParent().setStartTimestamp(queued.isAfter(now) ? now : queued).startSpan();
        span.setAttribute("langfuse.trace.name", kind).setAttribute("langfuse.session.id", job)
                .setAttribute("langfuse.trace.public", false)
                .setAttribute("langfuse.trace.metadata.job_id", jsonString(job))
                .setAttribute("langfuse.trace.metadata.workspace_id", jsonString(workspace))
                .setAttribute("langfuse.observation.type", "chain")
                .setAttribute("langfuse.observation.input", "{\"payload\":\"omitted\"}");
        return new Observation(span, Context.root().with(span).with(JOB, job).with(WORKSPACE, workspace).with(KIND, kind).makeCurrent());
    }

    public Observation stage(String name, String type) {
        Span span = tracer.spanBuilder(name).setParent(Context.current()).startSpan();
        span.setAttribute("langfuse.observation.type", type);
        context(span);
        return new Observation(span, span.makeCurrent());
    }

    public void queue(Instant queued) {
        Instant end = Instant.now();
        if (queued.isAfter(end)) queued = end;
        Span span = tracer.spanBuilder("queue.wait").setStartTimestamp(queued).startSpan();
        span.setAttribute("langfuse.observation.type", "span");
        context(span);
        span.end(end);
    }

    private static void context(Span span) {
        String job = Context.current().get(JOB), workspace = Context.current().get(WORKSPACE);
        String kind = Context.current().get(KIND);
        if (kind != null) span.setAttribute("langfuse.trace.name", kind);
        if (job != null) span.setAttribute("langfuse.session.id", job).setAttribute("langfuse.trace.metadata.job_id", jsonString(job));
        if (workspace != null) span.setAttribute("langfuse.trace.metadata.workspace_id", jsonString(workspace));
    }

    private static String jsonString(String value) { return JSON.writeValueAsString(value); }

    public static void usage(JsonNode envelope) {
        // Observational parsing must never reject an otherwise valid model response.
        try {
            var usage = envelope.path("usage");
            var values = new LinkedHashMap<String, Long>();
            for (var field : List.of("prompt_tokens", "completion_tokens", "total_tokens")) {
                var n = usage.path(field);
                if (n.isIntegralNumber() && n.canConvertToLong() && n.longValue() >= 0 && n.longValue() <= 1_000_000_000L)
                    values.put(field.equals("prompt_tokens") ? "input" : field.equals("completion_tokens") ? "output" : "total", n.longValue());
            }
            if (!values.isEmpty()) Span.current().setAttribute("langfuse.observation.usage_details", JSON.writeValueAsString(values));
            Span.current().setAttribute("langfuse.observation.metadata.usage_available", !values.isEmpty());
        } catch (RuntimeException ignored) { /* Missing usage is unknown, never zero cost. */ }
    }

    public static final class Observation implements AutoCloseable {
        private final Span span;
        private final Scope scope;
        private Observation(Span span, Scope scope) { this.span = span; this.scope = scope; }
        public void attribute(String key, String value) { span.setAttribute(key, value); }
        public void attribute(String key, boolean value) { span.setAttribute(key, value); }
        public void outcome(String code) {
            span.setAttribute("langfuse.observation.output", "{\"outcome\":\"" + code + "\"}");
            if (List.of("SUCCEEDED", "ACKNOWLEDGED", "VALID", "SETTLE_RETURNED").contains(code)) {
                span.setStatus(StatusCode.OK);
                span.setAttribute("langfuse.observation.status_message", "");
            } else {
                span.setStatus(StatusCode.ERROR, code);
                span.setAttribute("langfuse.observation.status_message", code);
            }
        }
        @Override public void close() { try { scope.close(); } finally { span.end(); } }
    }

    public void flush() { if (provider != null) provider.forceFlush().join(4, TimeUnit.SECONDS); }
    public static void parameters(int maxTokens, String tokenParameter, boolean jsonMode) {
        try {
            Span.current().setAttribute("langfuse.observation.model.parameters", JSON.writeValueAsString(
                    java.util.Map.of(tokenParameter, maxTokens, "stream", false, "json_mode", jsonMode)));
        } catch (RuntimeException ignored) { /* Observational fields never affect the supplier request. */ }
    }
    @PreDestroy @Override public void close() { if (provider != null) provider.shutdown().join(4, TimeUnit.SECONDS); }
}
