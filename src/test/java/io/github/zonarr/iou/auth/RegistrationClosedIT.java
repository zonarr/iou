package io.github.zonarr.iou.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zonarr.iou.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "iou.registration-open=false")
class RegistrationClosedIT extends IntegrationTest {

    @Test
    void registrationCanBeSwitchedOff() {
        var body = api().post("/api/auth/register",
                Map.of("email", "late@example.com", "password", "correct horse battery", "displayName", "Late"))
                .expect(403);

        assertThat(body.get("error").asString()).isEqualTo("registration_closed");
    }
}
