package com.xa.mass.worker.runtime.selection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Worker-universe intent resolved by the scheduling plane before worker-runtime
 * reads worker facts.
 */
public record WorkerSelectionIntent(
        String project,
        String eventCode,
        List<String> workerGroupIds,
        String routingCode,
        Map<String, String> routeAttributes,
        String targetWorkerId,
        Map<String, String> targetWorkerAttributes
) {

    public WorkerSelectionIntent {
        project = normalizeNullable(project);
        eventCode = normalizeNullable(eventCode);
        workerGroupIds = normalizeList(workerGroupIds);
        routingCode = normalizeNullable(routingCode);
        routeAttributes = normalizeMap(routeAttributes);
        targetWorkerId = normalizeNullable(targetWorkerId);
        targetWorkerAttributes = normalizeMap(targetWorkerAttributes);
    }

    public boolean targetsWorker() {
        return targetWorkerId != null;
    }

    public boolean hasRoutingRequirement() {
        return routingCode != null || !routeAttributes.isEmpty();
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = values.stream()
                .map(WorkerSelectionIntent::normalizeNullable)
                .filter(value -> value != null)
                .distinct()
                .toList();
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }

    private static Map<String, String> normalizeMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            String normalizedKey = normalizeNullable(key);
            String normalizedValue = normalizeNullable(value);
            if (normalizedKey != null && normalizedValue != null) {
                normalized.put(normalizedKey, normalizedValue);
            }
        });
        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
