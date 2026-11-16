package io.github.zonarr.iou.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Minimal JSON-over-HTTP client for integration tests: talks to the real running server. */
public final class Api {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final String baseUrl;
    private final String token;

    public Api(String baseUrl, String token) {
        this.baseUrl = baseUrl;
        this.token = token;
    }

    /** The same server, called as the owner of {@code token}. */
    public Api as(String token) {
        return new Api(baseUrl, token);
    }

    public Response get(String path) {
        return send("GET", path, null);
    }

    public Response post(String path, Object body) {
        return send("POST", path, body);
    }

    public Response post(String path) {
        return send("POST", path, null);
    }

    public Response put(String path, Object body) {
        return send("PUT", path, body);
    }

    public Response patch(String path, Object body) {
        return send("PATCH", path, body);
    }

    public Response delete(String path) {
        return send("DELETE", path, null);
    }

    private Response send(String method, String path, Object body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path));
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                String json = body instanceof String s ? s : MAPPER.writeValueAsString(body);
                request.header("Content-Type", "application/json");
                request.method(method, HttpRequest.BodyPublishers.ofString(json));
            }
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            String text = response.body();
            JsonNode node = null;
            if (text != null && !text.isBlank()
                    && response.headers().firstValue("Content-Type").orElse("").contains("json")) {
                node = MAPPER.readTree(text);
            }
            return new Response(response.statusCode(), node, text);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public record Response(int status, JsonNode body, String text) {

        /** Asserts the status code and returns the JSON body, for chaining. */
        public JsonNode expect(int expectedStatus) {
            if (status != expectedStatus) {
                throw new AssertionError("Expected HTTP " + expectedStatus + " but got " + status + ": " + text);
            }
            return body;
        }
    }
}
