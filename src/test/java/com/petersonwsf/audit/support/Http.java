package com.petersonwsf.audit.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Cliente HTTP mínimo para os testes de integração (JDK, sem dependências extras). */
public final class Http {

    public record Resp(int status, JsonNode body, String raw) {
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final HttpClient client = HttpClient.newHttpClient();
    private final String base;

    public Http(int port) {
        this.base = "http://127.0.0.1:" + port;
    }

    public Resp get(String path, String token) {
        return send("GET", path, token, null);
    }

    public Resp post(String path, String token, Object body) {
        return send("POST", path, token, body);
    }

    public Resp patch(String path, String token, Object body) {
        return send("PATCH", path, token, body);
    }

    public Resp delete(String path, String token) {
        return send("DELETE", path, token, null);
    }

    private Resp send(String method, String path, String token, Object body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path));
            if (token != null) {
                builder.header("Authorization", "Bearer " + token);
            }
            HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
            if (body != null) {
                builder.header("Content-Type", "application/json");
                publisher = HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : MAPPER.writeValueAsString(body));
            }
            HttpResponse<String> response = client.send(builder.method(method, publisher).build(),
                    HttpResponse.BodyHandlers.ofString());
            String raw = response.body();
            JsonNode json = raw == null || raw.isBlank() ? null : MAPPER.readTree(raw);
            return new Resp(response.statusCode(), json, raw);
        } catch (Exception e) {
            throw new IllegalStateException("Falha na requisição de teste", e);
        }
    }
}
