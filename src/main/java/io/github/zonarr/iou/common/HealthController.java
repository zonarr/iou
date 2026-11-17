package io.github.zonarr.iou.common;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Health", description = "Is the service up?")
@SecurityRequirements // public: no token needed
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
    @Operation(summary = "Liveness check (does not touch the database)")
    @GetMapping
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    /** Deeper check for humans: confirms the database is reachable. */
    @Operation(summary = "Checks that the database is reachable")
    @GetMapping("/db")
    public Map<String, String> database() {
        jdbc.sql("SELECT 1").query(Integer.class).single();
        return Map.of("status", "UP", "database", "UP");
    }
}
