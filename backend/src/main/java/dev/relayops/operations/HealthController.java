package dev.relayops.operations;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final JdbcTemplate jdbc;
    public HealthController(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @GetMapping("/healthz")
    public ResponseEntity<Map<String,String>> health() {
        try {
            jdbc.queryForObject("SELECT 1",Integer.class);
            return ResponseEntity.ok().header("Cache-Control","no-store").body(Map.of("status","UP","version","1.0.0"));
        } catch (RuntimeException ex) {
            return ResponseEntity.status(503).header("Cache-Control","no-store").body(Map.of("status","DOWN","version","1.0.0"));
        }
    }
}
