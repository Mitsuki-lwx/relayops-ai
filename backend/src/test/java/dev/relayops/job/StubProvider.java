package dev.relayops.job;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Local protocol fixture, never a real supplier or key. */
final class StubProvider implements AutoCloseable {
    final JsonMapper json = JsonMapper.builder().build();
    final AtomicInteger calls = new AtomicInteger();
    final HttpServer server;
    final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory());
    volatile int status = 200;
    volatile long delayMillis;
    volatile boolean slowBody, disconnect;
    volatile String content, finish = "stop", raw, authorization;
    volatile JsonNode request;
    StubProvider() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        content = validAnalysis();
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            try (exchange) {
                authorization = exchange.getRequestHeaders().getFirst("Authorization");
                request = json.readTree(exchange.getRequestBody().readAllBytes());
                if (disconnect) return;
                byte[] body = (raw != null ? raw : json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", finish,
                        "message", Map.of("role", "assistant", "content", content)))))).getBytes(StandardCharsets.UTF_8);
                if (status == 302) exchange.getResponseHeaders().set("Location", endpoint().replace("/v1/chat/completions", "/redirect-target"));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (slowBody) {
                    exchange.sendResponseHeaders(status, body.length);
                    exchange.getResponseBody().write(body, 0, 1); exchange.getResponseBody().flush();
                }
                if (delayMillis > 0) Thread.sleep(delayMillis);
                if (!slowBody) exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body, slowBody ? 1 : 0, body.length - (slowBody ? 1 : 0));
            } catch (Exception ignored) { /* Timed-out clients disconnect by design. */ }
        });
        server.start();
    }
    String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions"; }
    String validAnalysis() {
        var claim = Map.of("text", "需先持久化", "evidence", List.of(Map.of("paragraphId", "p0001", "quote", "必须持久化任务")));
        return json.writeValueAsString(Map.of("summary", claim, "keyPoints", List.of(claim), "constraints", List.of(), "unknowns", List.of("未说明超时阈值")));
    }
    ModelGateway gateway(int timeout) {
        return new ModelGateway(new FakeModel(), json, "compatible", endpoint(), "test-key-not-real", "fixture-model", timeout, 4096, "max_tokens", true, true);
    }
    public void close() { server.stop(0); executor.shutdownNow(); }
}
