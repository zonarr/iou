package io.github.zonarr.iou.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("iou.jwt")
public record JwtProperties(String secret, Duration ttl) {

    public JwtProperties {
        if (ttl == null) {
            ttl = Duration.ofDays(60);
        }
    }
}
