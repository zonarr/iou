package io.github.zonarr.iou.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.zonarr.iou.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ApiDocsIT extends IntegrationTest {

    @Test
    void openApiDocumentIsPublicAndDescribesTheReadmeEndpoints() {
        JsonNode docs = api().get("/v3/api-docs").expect(200);

        assertThat(docs.get("info").get("title").asString()).isEqualTo("iou API");
        assertThat(docs.get("paths").propertyNames()).contains(
                "/api/auth/register", "/api/auth/login", "/api/groups", "/api/groups/{id}/members",
                "/api/groups/{groupId}/debts", "/api/groups/{groupId}/expenses", "/api/debts",
                "/api/debts/{id}/accept", "/api/debts/{id}/reject", "/api/payments",
                "/api/payments/{id}/confirm", "/api/groups/{id}/balances", "/api/groups/{id}/settle-up",
                "/api/me/settle-up", "/api/placeholders/{id}/claim");
        assertThat(docs.get("components").get("securitySchemes").has("bearer-jwt")).isTrue();
    }

    @Test
    void loginIsMarkedPublicAndEverythingElseNeedsTheToken() {
        JsonNode docs = api().get("/v3/api-docs").expect(200);

        assertThat(docs.get("paths").get("/api/auth/login").get("post").get("security")).isEmpty();
        assertThat(docs.get("paths").get("/api/groups").get("post").has("security")).isFalse(); // inherits the global rule
        assertThat(docs.get("security").get(0).has("bearer-jwt")).isTrue();
        // The logged-in user comes from the token, so it must not show up as a parameter.
        assertThat(docs.get("paths").get("/api/groups").get("post").has("parameters")).isFalse();
    }

    @Test
    void swaggerUiIsServed() {
        var page = api().get("/swagger-ui/index.html");

        assertThat(page.status()).isEqualTo(200);
        assertThat(page.text()).contains("swagger-ui");
    }
}
