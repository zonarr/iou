package io.github.zonarr.iou.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DatabaseUrlTest {

    @Test
    void convertsNeonStyleUrl() {
        DatabaseUrl url = DatabaseUrl.parse(
                "postgresql://neondb_owner:s3cr%40t@ep-cool-1234.eu-central-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require");

        assertThat(url.jdbcUrl()).isEqualTo(
                "jdbc:postgresql://ep-cool-1234.eu-central-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require");
        assertThat(url.username()).isEqualTo("neondb_owner");
        assertThat(url.password()).isEqualTo("s3cr@t");
    }

    @Test
    void keepsPortAndAcceptsPostgresScheme() {
        DatabaseUrl url = DatabaseUrl.parse("postgres://iou:iou@localhost:5433/iou");

        assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5433/iou");
        assertThat(url.username()).isEqualTo("iou");
        assertThat(url.password()).isEqualTo("iou");
    }

    @Test
    void passesJdbcUrlThrough() {
        DatabaseUrl url = DatabaseUrl.parse("jdbc:postgresql://localhost/iou?user=a&password=b");

        assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost/iou?user=a&password=b");
        assertThat(url.username()).isNull();
    }

    @Test
    void blankMeansNotConfigured() {
        assertThat(DatabaseUrl.fromEnvironment(null)).isEmpty();
        assertThat(DatabaseUrl.fromEnvironment("  ")).isEmpty();
    }

    @Test
    void rejectsOtherSchemes() {
        assertThatThrownBy(() -> DatabaseUrl.parse("mysql://localhost/iou"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
