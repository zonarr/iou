package io.github.zonarr.iou.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for tests that need the whole application and a real PostgreSQL.
 *
 * <p>By default a throwaway PostgreSQL 16 is started with Testcontainers (needs Docker; this
 * is what CI uses). On a machine without Docker, point the tests at an existing, EMPTY
 * database instead:
 *
 * <pre>
 *   IOU_TEST_DB_URL=jdbc:postgresql://localhost:5432/iou_test \
 *   IOU_TEST_DB_USER=iou IOU_TEST_DB_PASSWORD=iou ./mvnw verify
 * </pre>
 *
 * The schema in that database is dropped and re-created by Flyway on every run.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CleanDatabaseConfig.class)
public abstract class IntegrationTest {

    @LocalServerPort
    private int port;

    /** An HTTP client for the running application, with no login. */
    protected Api api() {
        return new Api("http://localhost:" + port, null);
    }

    private static final java.util.concurrent.atomic.AtomicLong COUNTER =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    /** Registers a brand-new account with a unique email, so tests never collide. */
    protected TestUser register(String name) {
        String email = name.toLowerCase().replaceAll("[^a-z0-9]", "") + "-" + COUNTER.incrementAndGet()
                + "@example.com";
        String password = "correct horse battery";
        var body = api().post("/api/auth/register",
                java.util.Map.of("email", email, "password", password, "displayName", name)).expect(201);
        String token = body.get("token").asString();
        return new TestUser(body.get("user").get("id").asLong(), email, password, name, token, api().as(token));
    }

    private static final String EXTERNAL_URL = System.getenv("IOU_TEST_DB_URL");

    /** Started once and shared by every test class (the JVM shutdown stops it). */
    private static PostgreSQLContainer container;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        if (EXTERNAL_URL != null && !EXTERNAL_URL.isBlank()) {
            registry.add("spring.datasource.url", () -> EXTERNAL_URL);
            registry.add("spring.datasource.username", () -> env("IOU_TEST_DB_USER", "iou"));
            registry.add("spring.datasource.password", () -> env("IOU_TEST_DB_PASSWORD", "iou"));
        } else {
            registry.add("spring.datasource.url", () -> container().getJdbcUrl());
            registry.add("spring.datasource.username", () -> container().getUsername());
            registry.add("spring.datasource.password", () -> container().getPassword());
        }
        // Start every run from an empty schema, whichever database is used.
        registry.add("spring.flyway.clean-disabled", () -> "false");
    }

    private static synchronized PostgreSQLContainer container() {
        if (container == null) {
            container = new PostgreSQLContainer("postgres:16-alpine");
            container.start();
        }
        return container;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
