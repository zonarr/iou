package io.github.zonarr.iou.common;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Converts a libpq-style connection URL ({@code postgresql://user:pass@host:5432/db?sslmode=require})
 * into the JDBC URL, username and password that Spring's datasource needs.
 */
public record DatabaseUrl(String jdbcUrl, String username, String password) {

    public static Optional<DatabaseUrl> fromEnvironment(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(parse(value.trim()));
    }

    public static DatabaseUrl parse(String value) {
        if (value.startsWith("jdbc:postgresql://")) {
            return new DatabaseUrl(value, null, null);
        }
        if (!value.startsWith("postgres://") && !value.startsWith("postgresql://")) {
            throw new IllegalArgumentException(
                    "DATABASE_URL must start with postgresql://, postgres:// or jdbc:postgresql://");
        }
        URI uri = URI.create(value);
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("DATABASE_URL has no host");
        }
        String username = null;
        String password = null;
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            username = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
            password = colon < 0 ? null : decode(userInfo.substring(colon + 1));
        }
        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(uri.getHost());
        if (uri.getPort() > 0) {
            jdbc.append(':').append(uri.getPort());
        }
        jdbc.append(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            jdbc.append('?').append(translateParameters(uri.getRawQuery()));
        }
        return new DatabaseUrl(jdbc.toString(), username, password);
    }

    /**
     * The URL comes in libpq spelling (what psql uses); the Java driver spells a few
     * parameters differently, e.g. Neon's {@code channel_binding=require}.
     */
    private static String translateParameters(String query) {
        StringBuilder out = new StringBuilder();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String rest = equals < 0 ? "" : pair.substring(equals);
            String jdbcName = switch (name) {
                case "channel_binding" -> "channelBinding";
                case "application_name" -> "ApplicationName";
                case "connect_timeout" -> "connectTimeout";
                default -> name;
            };
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(jdbcName).append(rest);
        }
        return out.toString();
    }

    public void applyAsSystemProperties() {
        System.setProperty("spring.datasource.url", jdbcUrl);
        if (username != null) {
            System.setProperty("spring.datasource.username", username);
        }
        if (password != null) {
            System.setProperty("spring.datasource.password", password);
        }
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
