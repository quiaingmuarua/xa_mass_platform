package com.xa.mass.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

record WorkerScenarioSpec(String workerId,
                          String workerKey,
                          String workerGroupId,
                          String adapterNodeId,
                          String adapterId,
                          String transportHint,
                          String startMode,
                          Map<String, String> attributes,
                          List<EventBindingSpec> eventBindings) {
    WorkerScenarioSpec {
        attributes = copyMap(attributes);
        eventBindings = eventBindings == null ? List.of() : List.copyOf(eventBindings);
    }

    List<String> projectCodes() {
        return eventBindings.stream()
                .flatMap(binding -> binding.projectCodes().stream())
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    List<String> eventCodes() {
        return eventBindings.stream()
                .map(EventBindingSpec::eventCode)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    private static Map<String, String> copyMap(Map<String, String> value) {
        if (value == null || value.isEmpty()) {
            return Map.of();
        }
        return value.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                .collect(LinkedHashMap::new,
                        (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                        LinkedHashMap::putAll);
    }
}

record EventBindingSpec(String eventCode, List<String> projectCodes) {
    EventBindingSpec {
        projectCodes = projectCodes == null ? List.of() : List.copyOf(projectCodes);
    }
}

final class WorkerScenarioManifest {
    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new TypeReference<>() {
    };

    private WorkerScenarioManifest() {
    }

    static List<WorkerScenarioSpec> load(Path workerSpecFile, ObjectMapper objectMapper, Integer maxWorkers) {
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(
                    Files.readString(workerSpecFile, StandardCharsets.UTF_8),
                    LIST_OF_MAPS
            );
            List<WorkerScenarioSpec> expanded = expandSpecs(raw).stream()
                    .map(spec -> objectMapper.convertValue(spec, WorkerScenarioSpec.class))
                    .toList();
            int limit = maxWorkers == null || maxWorkers <= 0 ? expanded.size() : Math.min(maxWorkers, expanded.size());
            return List.copyOf(expanded.subList(0, limit));
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to read worker spec file: " + workerSpecFile, e);
        }
    }

    static List<Map<String, Object>> expandSpecs(List<Map<String, Object>> specs) {
        List<Map<String, Object>> expanded = new ArrayList<>();
        if (specs == null) {
            return expanded;
        }
        for (Map<String, Object> spec : specs) {
            expanded.addAll(expandCountedSpec(spec));
        }
        return expanded;
    }

    private static List<Map<String, Object>> expandCountedSpec(Map<String, Object> spec) {
        int count = 1;
        Object countValue = spec.get("count");
        if (countValue instanceof Number number && number.intValue() > 0) {
            count = number.intValue();
        }
        Map<String, Object> template = new LinkedHashMap<>(spec);
        template.remove("count");
        List<Map<String, Object>> expanded = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            expanded.add(asMap(replacePlaceholders(template, placeholderValues(index))));
        }
        return expanded;
    }

    private static Map<String, String> placeholderValues(int index) {
        String[] regions = {"us", "gb", "de", "fr", "sg", "jp"};
        String[] fingerprints = {"fp-sg-alpha", "fp-sg-beta", "fp-sg-gamma", "fp-sg-delta"};
        String[] mccMncs = {"52501", "52505"};
        return Map.of(
                "INDEX", String.valueOf(index),
                "INDEX1", String.valueOf(index + 1),
                "PAD3", String.format("%03d", index + 1),
                "PAD5", String.format("%05d", index + 1),
                "PAD6", String.format("%06d", index + 1),
                "REGION", regions[index % regions.length],
                "FINGERPRINT", fingerprints[index % fingerprints.length],
                "MCC_MNC", mccMncs[index % mccMncs.length]
        );
    }

    @SuppressWarnings("unchecked")
    private static Object replacePlaceholders(Object value, Map<String, String> replacements) {
        if (value instanceof String text) {
            String result = text;
            for (Map.Entry<String, String> entry : replacements.entrySet()) {
                result = result.replace("${" + entry.getKey() + "}", entry.getValue());
            }
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> replacePlaceholders(item, replacements)).toList();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> replaced = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                replaced.put(String.valueOf(entry.getKey()), replacePlaceholders(entry.getValue(), replacements));
            }
            return replaced;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }
}
