package com.xa.mass.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.MassPlatform;

import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

final class ScenarioClientFactory {
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, MassPlatform> clientsByApiKey = new HashMap<>();

    ScenarioClientFactory(String baseUrl, HttpClient httpClient, ObjectMapper objectMapper) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl is required");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    MassPlatform forApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey is required");
        }
        return clientsByApiKey.computeIfAbsent(apiKey, key -> MassPlatform.builder()
                .baseUrl(baseUrl)
                .apiKey(key)
                .httpClient(httpClient)
                .objectMapper(objectMapper)
                .build());
    }
}
