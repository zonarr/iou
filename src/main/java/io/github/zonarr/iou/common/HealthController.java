package io.github.zonarr.iou.common;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final JdbcClient jdbc;

    public HealthController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Liveness check. Deliberately does not touch the database, so the host's frequent
     * health probes don't keep a serverless Postgres (Neon) awake.
     */
    @GetMapping
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    /** Deeper check for humans: confirms the database is reachable. */
    @GetMapping("/db")
    public Map<String, String> database() {
        jdbc.sql("SELECT 1").query(Integer.class).single();
        return Map.of("status", "UP", "database", "UP");
    }
}
