package com.xa.mass.engine.runtime.scheduling;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Engine-facing task dispatch intent.
 *
 * <p>This value captures explicit task-side routing/target constraints. It is
 * not policy truth, storage truth, or item payload interpretation.</p>
 */
public record TaskDispatchIntent(
        String taskId,
        String project,
        String eventCode,
        List<String> workerGroupIds,
        String routingCode,
        Map<String, String> routeAttributes,
        String targetWorkerId,
        Map<String, String> targetWorkerAttributes
) {

    public TaskDispatchIntent {
        taskId = normalizeNullable(taskId);
        project = normalizeNullable(project);
        eventCode = normalizeNullable(eventCode);
        workerGroupIds = normalizeList(workerGroupIds);
        routingCode = normalizeNullable(routingCode);
        routeAttributes = normalizeMap(routeAttributes);
        targetWorkerId = normalizeNullable(targetWorkerId);
        targetWorkerAttributes = normalizeMap(targetWorkerAttributes);
    }

    public static TaskDispatchIntent fromTask(Task task) {
        return new TaskDispatchIntent(
                task == null ? null : task.getTid(),
                task == null ? null : task.getProject(),
                TaskSharedConfig.sdkEventCode(task),
                TaskSharedConfig.workerGroupSelector(task),
                TaskSharedConfig.routingCode(task),
                TaskSharedConfig.routeAttributes(task),
                TaskSharedConfig.targetWorkerId(task),
                TaskSharedConfig.targetWorkerAttributes(task)
        );
    }

    public boolean targetsWorker() {
        return targetWorkerId != null;
    }

    public boolean hasRouteConstraint() {
        return routingCode != null || !routeAttributes.isEmpty();
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = values.stream()
                .map(TaskDispatchIntent::normalizeNullable)
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
