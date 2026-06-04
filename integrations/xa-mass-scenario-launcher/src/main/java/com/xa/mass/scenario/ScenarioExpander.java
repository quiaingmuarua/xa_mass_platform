package com.xa.mass.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ScenarioExpander {
    private ScenarioExpander() {
    }

    static List<WorkerScenarioSpec> expandWorkerSpecs(List<Map<String, Object>> specs, ObjectMapper objectMapper) {
        return expandSpecs(specs).stream()
                .map(spec -> objectMapper.convertValue(spec, WorkerScenarioSpec.class))
                .toList();
    }

    static List<TaskScenarioSpec> expandTaskSpecs(List<Map<String, Object>> specs, ObjectMapper objectMapper) {
        return specs.stream()
                .map(spec -> expandTaskSpec(spec, objectMapper))
                .toList();
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

    @SuppressWarnings("unchecked")
    private static TaskScenarioSpec expandTaskSpec(Map<String, Object> spec, ObjectMapper objectMapper) {
        Map<String, Object> result = new LinkedHashMap<>(spec);
        Map<String, Object> body = new LinkedHashMap<>((Map<String, Object>) result.getOrDefault("body", Map.of()));
        Object generatedItemsValue = result.get("generatedItems");
        if (generatedItemsValue instanceof Map<?, ?> generatedItems) {
            Object countValue = generatedItems.get("count");
            Object templateValue = generatedItems.get("template");
            int count = countValue instanceof Number number ? number.intValue() : 0;
            if (count > 0 && templateValue instanceof Map<?, ?> template) {
                List<Object> generated = new ArrayList<>();
                for (int index = 0; index < count; index++) {
                    generated.add(replacePlaceholders(template, placeholderValues(index)));
                }
                List<Object> items = new ArrayList<>();
                Object existingItems = body.get("items");
                if (existingItems instanceof List<?> existingList) {
                    items.addAll(existingList);
                }
                items.addAll(generated);
                body.put("items", items);
            }
        }
        result.put("body", body);
        result.remove("generatedItems");
        return objectMapper.convertValue(result, TaskScenarioSpec.class);
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
            expanded.add(asStringObjectMap(replacePlaceholders(template, placeholderValues(index))));
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
            return list.stream()
                    .map(item -> replacePlaceholders(item, replacements))
                    .toList();
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
    private static Map<String, Object> asStringObjectMap(Object value) {
        return (Map<String, Object>) value;
    }
}
