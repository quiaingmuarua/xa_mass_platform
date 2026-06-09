package com.xa.mass.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class AdminHttpClient {
    private static final String API_KEY_HEADER = "X-Mass-Api-Key";

    private final URI baseUri;
    private final Duration requestTimeout;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private String csrfHeaderName;
    private String csrfToken;

    AdminHttpClient(String baseUrl,
                    Duration connectTimeout,
                    Duration requestTimeout,
                    ObjectMapper objectMapper) {
        this.baseUri = URI.create(normalizeBaseUrl(baseUrl));
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .cookieHandler(cookieManager)
                .build();
    }

    void health() {
        HttpRequest request = baseRequest("/actuator/health").GET().build();
        sendRaw(request, "server health");
    }

    AuthConfig authConfig() {
        JsonNode data = getData("/api/v1/auth/config", "auth config");
        String mode = data.path("authMode").asText("");
        String csrfHeader = data.path("csrfHeaderName").asText(null);
        if (csrfHeader != null && !csrfHeader.isBlank()) {
            csrfHeaderName = csrfHeader;
        }
        return new AuthConfig(mode, data.path("sessionCookieSupported").asBoolean(false), csrfHeader);
    }

    void login(String user, String password) {
        Map<String, Object> body = Map.of("userId", user, "password", password);
        JsonNode data = postData("/api/v1/auth/login", body, false, "operator login");
        String token = data.path("csrfToken").asText("");
        if (!token.isBlank()) {
            csrfToken = token;
        }
    }

    void requireCurrentOperator() {
        getData("/api/v1/auth/me", "operator current user");
    }

    JsonNode getProjects() {
        return getData("/api/v1/projects", "project list");
    }

    JsonNode getEvents() {
        return getData("/api/v1/catalog/events", "event list");
    }

    JsonNode getRules() {
        return getData("/api/v1/admin/rules", "rule list");
    }

    void syncCatalog(JsonNode manifest) {
        postData("/api/v1/control-plane/catalog:sync", manifest, true, "catalog sync");
    }

    void syncRules(JsonNode manifest) {
        postData("/api/v1/control-plane/rules:sync", manifest, true, "rules sync");
    }

    CurrentApiKey currentApiKey(String rawSecret) {
        if (rawSecret == null || rawSecret.isBlank()) {
            return null;
        }
        HttpRequest request = baseRequest("/api/v1/api-keys:current")
                .header(API_KEY_HEADER, rawSecret)
                .GET()
                .build();
        try {
            JsonNode data = sendApiResponse(request, "current API key");
            return CurrentApiKey.from(data);
        } catch (AdminHttpException e) {
            if (e.statusCode() == 401 || e.statusCode() == 403) {
                return null;
            }
            throw e;
        }
    }

    String createApiKey(DesiredApiKey desired) {
        JsonNode data = postData("/api/v1/api-keys", desired.createRequestBody(), true,
                "create API key " + desired.principalId());
        String rawSecret = data.path("rawSecret").asText("");
        if (rawSecret.isBlank()) {
            throw new IllegalStateException("API-key creation did not return rawSecret for " + desired.principalId());
        }
        return rawSecret;
    }

    void revokeApiKey(String keyId, String reason) {
        if (keyId == null || keyId.isBlank()) {
            return;
        }
        postData("/api/v1/api-keys/" + keyId + ":revoke", Map.of("reason", reason), true,
                "revoke API key " + keyId);
    }

    private JsonNode getData(String path, String description) {
        return sendApiResponse(baseRequest(path).GET().build(), description);
    }

    private JsonNode postData(String path, Object body, boolean csrf, String description) {
        try {
            String json = body instanceof JsonNode node
                    ? objectMapper.writeValueAsString(node)
                    : objectMapper.writeValueAsString(body);
            HttpRequest.Builder builder = baseRequest(path)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (csrf) {
                addCsrf(builder);
            }
            return sendApiResponse(builder.build(), description);
        } catch (IOException e) {
            throw new IllegalStateException("failed to serialize request body for " + description, e);
        }
    }

    private HttpRequest.Builder baseRequest(String path) {
        return HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(requestTimeout)
                .header("Accept", "application/json");
    }

    private void addCsrf(HttpRequest.Builder builder) {
        if (csrfHeaderName != null && !csrfHeaderName.isBlank()
                && csrfToken != null && !csrfToken.isBlank()) {
            builder.header(csrfHeaderName, csrfToken);
        }
    }

    private JsonNode sendApiResponse(HttpRequest request, String description) {
        HttpResponse<String> response = sendRaw(request, description);
        try {
            JsonNode envelope = objectMapper.readTree(response.body());
            int code = envelope.path("code").asInt(response.statusCode());
            if (code != 0) {
                throw new AdminHttpException(response.statusCode(), description + " failed: "
                        + envelope.path("msg").asText(response.body()));
            }
            return envelope.path("data");
        } catch (IOException e) {
            throw new IllegalStateException("failed to parse response for " + description, e);
        }
    }

    private HttpResponse<String> sendRaw(HttpRequest request, String description) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AdminHttpException(response.statusCode(), description + " failed: " + response.body());
            }
            return response;
        } catch (IOException e) {
            throw new IllegalStateException(description + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(description + " interrupted", e);
        }
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = AdminEnvConfig.required(value, "server.baseUrl");
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}

record AuthConfig(String authMode, boolean sessionCookieSupported, String csrfHeaderName) {
}

record CurrentApiKey(String principalId,
                     String createdForUserId,
                     List<String> permissions,
                     List<String> projectScopes,
                     List<String> eventScopes,
                     Map<String, String> attributes,
                     String keyId) {
    static CurrentApiKey from(JsonNode data) {
        JsonNode credential = data.path("credential");
        return new CurrentApiKey(
                data.path("principalId").asText(credential.path("principalId").asText("")),
                credential.path("createdForUserId").asText(""),
                textList(data.path("permissions")),
                textList(data.path("projectScopes")),
                textList(data.path("eventScopes")),
                textMap(data.path("attributes")),
                credential.path("keyId").asText("")
        );
    }

    boolean matches(DesiredApiKey desired) {
        return principalId.equals(desired.principalId())
                && createdForUserId.equals(desired.createdForUserId())
                && containsAll(permissions, desired.permissions())
                && containsAll(projectScopes, desired.projectScopes())
                && containsAll(eventScopes, desired.eventScopes())
                && desired.attributes().entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(attributes.get(entry.getKey())));
    }

    private static boolean containsAll(List<String> actual, List<String> expected) {
        return actual.containsAll(expected);
    }

    private static List<String> textList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        node.forEach(item -> {
            if (item.isTextual()) {
                result.add(item.asText());
            }
        });
        return List.copyOf(result);
    }

    private static Map<String, String> textMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> result.put(entry.getKey(), entry.getValue().asText("")));
        return Map.copyOf(result);
    }
}

final class AdminHttpException extends RuntimeException {
    private final int statusCode;

    AdminHttpException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    int statusCode() {
        return statusCode;
    }
}
