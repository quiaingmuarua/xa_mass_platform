package com.xa.mass.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

final class AdminStubServer implements AutoCloseable {
    private final ObjectMapper objectMapper = AdminEnvConfig.objectMapper();
    private final HttpServer server;
    private final Map<String, JsonNode> credentialsBySecret = new LinkedHashMap<>();
    private final Set<String> projects = new LinkedHashSet<>();
    private final Set<String> events = new LinkedHashSet<>();
    private final Set<String> rules = new LinkedHashSet<>();
    private final List<String> calls = new ArrayList<>();
    private String authMode = "session";
    private boolean requireCsrf = true;
    private int keySequence;

    AdminStubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    AdminStubServer withAuthMode(String value) {
        authMode = value;
        return this;
    }

    AdminStubServer addProject(String code) {
        projects.add(code);
        return this;
    }

    AdminStubServer addEvent(String code) {
        events.add(code);
        return this;
    }

    AdminStubServer addRule(String id) {
        rules.add(id);
        return this;
    }

    List<String> calls() {
        return List.copyOf(calls);
    }

    boolean hasCredential(String rawSecret) {
        return credentialsBySecret.containsKey(rawSecret);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        calls.add(exchange.getRequestMethod() + " " + path);
        try {
            if (path.equals("/actuator/health")) {
                writeRaw(exchange, 200, "{\"status\":\"UP\"}");
            } else if (path.equals("/api/v1/auth/config")) {
                Map<String, Object> config = new LinkedHashMap<>();
                config.put("authMode", authMode);
                config.put("sessionCookieSupported", "session".equals(authMode));
                config.put("operatorHeaderSupported", "dev-header".equals(authMode));
                if ("session".equals(authMode)) {
                    config.put("csrfHeaderName", "X-Mass-Csrf-Token");
                }
                api(exchange, config);
            } else if (path.equals("/api/v1/auth/login")) {
                if (!"session".equals(authMode)) {
                    apiError(exchange, 401, "Invalid operator credentials");
                    return;
                }
                exchange.getResponseHeaders().add("Set-Cookie", "XA_MASS_OPERATOR_SESSION=session-1; Path=/");
                api(exchange, Map.of(
                        "user", Map.of("id", "ops-admin"),
                        "csrfToken", "csrf-1"
                ));
            } else if (path.equals("/api/v1/auth/me")) {
                api(exchange, Map.of("id", "ops-admin", "csrfToken", "csrf-1"));
            } else if (path.equals("/api/v1/projects")) {
                api(exchange, projects.stream().map(code -> Map.of("code", code)).toList());
            } else if (path.equals("/api/v1/catalog/events")) {
                api(exchange, events.stream().map(code -> Map.of("code", code)).toList());
            } else if (path.equals("/api/v1/admin/rules")) {
                api(exchange, Map.of("items", rules.stream().map(id -> Map.of("id", id)).toList()));
            } else if (path.equals("/api/v1/runtime/workers")) {
                api(exchange, Map.of("items", List.of(Map.of(
                        "workerId", "confidence-worker-001",
                        "transportOnline", true
                )), "total", 1, "limit", 200));
            } else if (path.equals("/api/v1/catalog/worker-capabilities")) {
                api(exchange, List.of(Map.of(
                        "workerId", "confidence-worker-001",
                        "supportedEventCodes", List.of("crawler.fetch-page")
                )));
            } else if (path.equals("/api/v1/catalog/worker-group-capabilities")) {
                api(exchange, List.of(Map.of(
                        "groupId", "confidence-crawler",
                        "workerCount", 1
                )));
            } else if (path.equals("/api/v1/control-plane/catalog:sync")) {
                requireCsrf(exchange);
                JsonNode body = read(exchange);
                body.path("projects").forEach(project -> projects.add(project.path("code").asText()));
                body.path("events").forEach(event -> events.add(event.path("code").asText()));
                api(exchange, Map.of("projects", projects.size(), "events", events.size(), "rules", 0));
            } else if (path.equals("/api/v1/control-plane/rules:sync")) {
                requireCsrf(exchange);
                JsonNode body = read(exchange);
                body.path("rules").forEach(rule -> rules.add(rule.path("id").asText()));
                api(exchange, Map.of("projects", 0, "events", 0, "rules", rules.size()));
            } else if (path.equals("/api/v1/api-keys:current")) {
                String secret = exchange.getRequestHeaders().getFirst("X-Mass-Api-Key");
                JsonNode credential = credentialsBySecret.get(secret);
                if (credential == null) {
                    apiError(exchange, 401, "Invalid or missing API key credential");
                    return;
                }
                api(exchange, currentView(credential));
            } else if (path.equals("/api/v1/api-keys") && "POST".equals(exchange.getRequestMethod())) {
                requireCsrf(exchange);
                JsonNode body = read(exchange);
                String rawSecret = body.path("rawSecret").asText("generated-" + (++keySequence));
                JsonNode credential = objectMapper.createObjectNode()
                        .put("keyId", "key-" + (++keySequence))
                        .put("principalId", body.path("principalId").asText())
                        .put("createdForUserId", body.path("createdForUserId").asText())
                        .set("permissions", body.path("permissions"));
                ((com.fasterxml.jackson.databind.node.ObjectNode) credential).set("projectScopes", body.path("projectScopes"));
                ((com.fasterxml.jackson.databind.node.ObjectNode) credential).set("eventScopes", body.path("eventScopes"));
                ((com.fasterxml.jackson.databind.node.ObjectNode) credential).set("attributes", body.path("attributes"));
                credentialsBySecret.put(rawSecret, credential);
                api(exchange, Map.of("credential", credential, "rawSecret", rawSecret));
            } else if (path.matches("^/api/v1/api-keys/[^/]+:revoke$")) {
                requireCsrf(exchange);
                String keyId = path.substring("/api/v1/api-keys/".length(), path.length() - ":revoke".length());
                credentialsBySecret.entrySet().removeIf(entry -> keyId.equals(entry.getValue().path("keyId").asText()));
                api(exchange, Map.of("keyId", keyId, "status", "REVOKED"));
            } else {
                apiError(exchange, 404, "No route: " + path);
            }
        } catch (RuntimeException e) {
            apiError(exchange, 500, e.getMessage());
        }
    }

    private Object currentView(JsonNode credential) {
        return Map.of(
                "credential", credential,
                "principalId", credential.path("principalId").asText(),
                "permissions", credential.path("permissions"),
                "projectScopes", credential.path("projectScopes"),
                "eventScopes", credential.path("eventScopes"),
                "attributes", credential.path("attributes")
        );
    }

    private void requireCsrf(HttpExchange exchange) {
        if (requireCsrf && !"csrf-1".equals(exchange.getRequestHeaders().getFirst("X-Mass-Csrf-Token"))) {
            throw new IllegalStateException("missing csrf header");
        }
    }

    private JsonNode read(HttpExchange exchange) throws IOException {
        return objectMapper.readTree(exchange.getRequestBody());
    }

    private void api(HttpExchange exchange, Object data) throws IOException {
        writeRaw(exchange, 200, objectMapper.writeValueAsString(Map.of("code", 0, "msg", "ok", "data", data)));
    }

    private void apiError(HttpExchange exchange, int status, String msg) throws IOException {
        writeRaw(exchange, status, objectMapper.writeValueAsString(Map.of("code", status, "msg", msg)));
    }

    private void writeRaw(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
