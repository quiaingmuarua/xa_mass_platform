package com.xa.mass.scenario;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

record ScenarioFiles(
        Map<String, Object> bootstrapSpec,
        Map<String, Object> ruleSpec,
        List<WorkerScenarioSpec> workerSpecs,
        List<TaskScenarioSpec> taskSpecs
) {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS_TYPE = new TypeReference<>() {
    };

    static ScenarioFiles load(Path scenarioDir, ObjectMapper objectMapper) throws IOException {
        Objects.requireNonNull(scenarioDir, "scenarioDir is required");
        Objects.requireNonNull(objectMapper, "objectMapper is required");
        Map<String, Object> bootstrap = readMap(objectMapper, scenarioDir.resolve("bootstrap.json"));
        Map<String, Object> rules = readMap(objectMapper, scenarioDir.resolve("rules.json"));
        List<Map<String, Object>> workers = readList(objectMapper, scenarioDir.resolve("workers.json"));
        List<Map<String, Object>> tasks = readList(objectMapper, scenarioDir.resolve("tasks.json"));
        return new ScenarioFiles(
                ScenarioExpander.expandBootstrapSpec(bootstrap),
                rules,
                ScenarioExpander.expandWorkerSpecs(workers, objectMapper),
                ScenarioExpander.expandTaskSpecs(tasks, objectMapper)
        );
    }

    private static Map<String, Object> readMap(ObjectMapper objectMapper, Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return objectMapper.readValue(input, MAP_TYPE);
        }
    }

    private static List<Map<String, Object>> readList(ObjectMapper objectMapper, Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return objectMapper.readValue(input, LIST_OF_MAPS_TYPE);
        }
    }
}
