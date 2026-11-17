package io.github.zonarr.iou;

import io.github.zonarr.iou.common.DatabaseUrl;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IouApplication {

    public static void main(String[] args) {
        // Hosts such as Neon and Render hand out a "postgresql://user:pass@host/db" URL.
        // Translate it into the JDBC properties Spring expects, so it can be pasted as-is.
        DatabaseUrl.fromEnvironment(System.getenv("DATABASE_URL"))
                .ifPresent(DatabaseUrl::applyAsSystemProperties);
        SpringApplication.run(IouApplication.class, args);
    }
}
