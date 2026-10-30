package dev.relayops.common;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> business(ApiException ex) {
        return ResponseEntity.status(ex.status).body(Map.of("code", ex.code, "message", ex.getMessage(),
                "traceId", UUID.randomUUID().toString()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> malformed() {
        return ResponseEntity.badRequest().body(Map.of("code", "MALFORMED_JSON", "message", "请求 JSON 无效"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception ex) {
        String traceId = UUID.randomUUID().toString();
        // JDBC exception messages can contain query arguments. Never log raw exceptions here.
        LOG.error("operation=api_failed traceId={} exceptionType={}", traceId, ex.getClass().getSimpleName());
        return ResponseEntity.status(503).body(Map.of("code", "DEPENDENCY_UNAVAILABLE", "message", "服务暂不可用，请使用原幂等键重试", "traceId", traceId));
    }
}
