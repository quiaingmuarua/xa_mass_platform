package com.xa.mass.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

final class DevBootstrapClient {
    private final String baseUrl;
    private final String bootstrapKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    DevBootstrapClient(String baseUrl, String bootstrapKey, HttpClient httpClient, ObjectMapper objectMapper) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl is required");
        this.bootstrapKey = Objects.requireNonNull(bootstrapKey, "bootstrapKey is required");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    void bootstrapCatalog(Map<String, Object> spec) {
        post("/sample-api/bootstrap/catalog", spec);
        System.out.println("[java-scenario-launcher] bootstrapped catalog");
    }

    void bootstrapRules(Map<String, Object> spec) {
        post("/sample-api/bootstrap/rules", spec);
        System.out.println("[java-scenario-launcher] bootstrapped rules");
    }

    private void post(String path, Object body) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body == null ? Map.of() : body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to encode bootstrap request " + path, e);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-Sample-Bootstrap-Key", bootstrapKey)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling " + path, e);
        } catch (IOException e) {
            throw new IllegalStateException("I/O failure while calling " + path, e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " " + path + ": " + response.body());
        }
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(response.body());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to decode bootstrap response " + path, e);
        }
        if (!envelope.has("code") || envelope.get("code").asInt(-1) != 0) {
            String message = envelope.hasNonNull("msg") ? envelope.get("msg").asText() : "unknown error";
            throw new IllegalStateException("bootstrap failed " + path + ": " + message);
        }
    }
}
