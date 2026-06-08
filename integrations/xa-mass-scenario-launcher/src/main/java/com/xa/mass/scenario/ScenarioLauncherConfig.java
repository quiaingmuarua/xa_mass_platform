package com.xa.mass.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
record ScenarioLauncherConfig(
        ServerConfig server,
        CredentialsConfig credentials,
        RuntimeConfig runtime,
        Map<String, ActionConfig> actions,
    List<TaskConfig> tasks
) {
    static Loaded load(Path configPath, ObjectMapper objectMapper) throws IOException {
        Objects.requireNonNull(configPath, "configPath is required");
        Objects.requireNonNull(objectMapper, "objectMapper is required");
        Path normalizedPath = configPath.toAbsolutePath().normalize();
        JsonNode root;
        try (var input = Files.newInputStream(normalizedPath)) {
            root = objectMapper.readTree(input);
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("config file must contain a JSON object: " + normalizedPath);
        }
        if (root.has("workers")) {
            throw new IllegalArgumentException("workers config is deferred; use the worker launcher with --scenario-dir / workers.json");
        }
        if (root.has("scenarioDir")) {
            throw new IllegalArgumentException("scenarioDir is not supported in --config; use legacy --scenario-dir instead");
        }
        ScenarioLauncherConfig config = objectMapper.convertValue(root, ScenarioLauncherConfig.class);
        return new Loaded(normalizedPath, config);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ServerConfig(
            String baseUrl,
            Integer connectTimeoutSeconds,
            Integer requestTimeoutSeconds
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CredentialsConfig(
            String taskApiKey,
            String taskApiKeyFile
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RuntimeConfig(
            Integer taskItemBatchSize
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ActionConfig(
            String eventCode,
            Map<String, String> paramMap,
            List<String> jsonFields
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TaskConfig(
            String apiKey,
            Integer itemBatchSize,
            Map<String, Object> body,
            String project,
            String userId,
            String sourceRef,
            String contract,
            Map<String, Object> sharedConfig,
            Map<String, Object> executionSpec,
            String eventCode,
            String action,
            Object items
    ) {
    }

    record Loaded(
            Path configPath,
            ScenarioLauncherConfig config
    ) {
        Loaded {
            Objects.requireNonNull(configPath, "configPath is required");
            Objects.requireNonNull(config, "config is required");
        }

        Path configDir() {
            Path parent = configPath.getParent();
            return parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
        }

        Path resolvePath(String value, String fieldName) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(fieldName + " is required");
            }
            Path path = Path.of(value);
            return path.isAbsolute() ? path.normalize() : configDir().resolve(path).normalize();
        }
    }

}
