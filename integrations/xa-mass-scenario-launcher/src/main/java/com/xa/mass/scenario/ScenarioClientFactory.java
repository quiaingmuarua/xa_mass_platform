package com.xa.mass.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.MassPlatform;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

final class ScenarioClientFactory {
    private final String baseUrl;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final ObjectMapper objectMapper;
    private final Map<String, MassPlatform> clientsByApiKey = new HashMap<>();

    ScenarioClientFactory(String baseUrl,
                          Duration connectTimeout,
                          Duration requestTimeout,
                          ObjectMapper objectMapper) {
        this.baseUrl = Objects.requireNonNull(baseUrl, "baseUrl is required");
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout is required");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    }

    MassPlatform forApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey is required");
        }
        return clientsByApiKey.computeIfAbsent(apiKey, key -> MassPlatform.builder()
                .baseUrl(baseUrl)
                .apiKey(key)
                .connectTimeout(connectTimeout)
                .requestTimeout(requestTimeout)
                .objectMapper(objectMapper)
                .build());
    }
}
