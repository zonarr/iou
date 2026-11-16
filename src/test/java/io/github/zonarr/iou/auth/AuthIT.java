package io.github.zonarr.iou.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zonarr.iou.support.IntegrationTest;
import io.github.zonarr.iou.support.TestUser;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

class AuthIT extends IntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void registerReturnsATokenThatWorks() {
        TestUser alice = register("Alice");

        JsonNode me = alice.api().get("/api/me").expect(200);

        assertThat(me.get("id").asLong()).isEqualTo(alice.id());
        assertThat(me.get("email").asString()).isEqualTo(alice.email());
        assertThat(me.get("displayName").asString()).isEqualTo("Alice");
    }

    @Test
    void passwordIsStoredAsBcryptHashNeverPlainText() {
        TestUser alice = register("Alice");

        String hash = jdbc.sql("SELECT password_hash FROM users WHERE id = ?").param(alice.id())
                .query(String.class).single();

        assertThat(hash).startsWith("$2").doesNotContain(alice.password());
    }

    @Test
    void loginIsCaseInsensitiveOnEmailAndReturnsAToken() {
        TestUser alice = register("Alice");

        JsonNode body = api().post("/api/auth/login",
                Map.of("email", "  " + alice.email().toUpperCase() + " ", "password", alice.password())).expect(200);

        assertThat(api().as(body.get("token").asString()).get("/api/me").expect(200).get("id").asLong())
                .isEqualTo(alice.id());
        assertThat(body.get("expiresAt").asString()).isNotBlank();
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameAnswer() {
        TestUser alice = register("Alice");

        JsonNode wrong = api().post("/api/auth/login",
                Map.of("email", alice.email(), "password", "not the password")).expect(401);
        JsonNode unknown = api().post("/api/auth/login",
                Map.of("email", "nobody@example.com", "password", "whatever123")).expect(401);

        assertThat(wrong.get("error").asString()).isEqualTo("bad_credentials");
        assertThat(unknown).isEqualTo(wrong);
    }

    @Test
    void duplicateEmailIsRejected() {
        TestUser alice = register("Alice");

        JsonNode body = api().post("/api/auth/register",
                Map.of("email", alice.email().toUpperCase(), "password", "another password", "displayName", "Al"))
                .expect(409);

        assertThat(body.get("error").asString()).isEqualTo("email_taken");
    }

    @Test
    void registrationValidatesInput() {
        JsonNode body = api().post("/api/auth/register",
                Map.of("email", "not-an-email", "password", "short", "displayName", "")).expect(400);

        assertThat(body.get("error").asString()).isEqualTo("validation_failed");
        assertThat(body.get("fields").propertyNames()).contains("email", "password", "displayName");
    }

    @Test
    void apiNeedsAToken() {
        assertThat(api().get("/api/me").expect(401).get("error").asString()).isEqualTo("unauthorized");
        assertThat(api().as("not.a.jwt").get("/api/me").status()).isEqualTo(401);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        // A structurally valid HS256 token for user 1, signed with a different secret.
        String forged = "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJpb3UiLCJzdWIiOiIxIiwiZXhwIjo0MTAyNDQ0ODAwfQ."
                + "3q0sJmUu2Yc1Vw0b0m0m3m6m4n0q5iJ2c1m4QmH0u3A";

        assertThat(api().as(forged).get("/api/me").status()).isEqualTo(401);
    }

    @Test
    void canRenameAndChangePassword() {
        TestUser alice = register("Alice");

        alice.api().patch("/api/me", Map.of("displayName", "Alice B")).expect(200);
        alice.api().post("/api/me/password",
                Map.of("currentPassword", "wrong wrong", "newPassword", "brand new password")).expect(400);
        alice.api().post("/api/me/password",
                Map.of("currentPassword", alice.password(), "newPassword", "brand new password")).expect(204);

        assertThat(alice.api().get("/api/me").expect(200).get("displayName").asString()).isEqualTo("Alice B");
        api().post("/api/auth/login", Map.of("email", alice.email(), "password", alice.password())).expect(401);
        api().post("/api/auth/login", Map.of("email", alice.email(), "password", "brand new password")).expect(200);
    }
}
