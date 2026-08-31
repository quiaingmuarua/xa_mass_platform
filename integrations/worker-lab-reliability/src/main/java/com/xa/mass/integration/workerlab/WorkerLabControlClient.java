package com.xa.mass.integration.workerlab;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WorkerLabControlClient {

    private static final String WORKERS_PATH = "/lab/v1/workers";

    private final JsonHttpClient http;

    WorkerLabControlClient(JsonHttpClient http) {
        this.http = java.util.Objects.requireNonNull(http, "http");
    }

    List<WorkerSnapshot> workers() {
        JsonHttpClient.Response response = http.send(
                "GET",
                WORKERS_PATH,
                null
        );
        requireStatus(response, 200, "list workers");
        List<WorkerSnapshot> workers = new ArrayList<>();
        for (Object raw : JsonValues.array(
                response.body().get("workers"),
                "workers"
        )) {
            workers.add(decodeSnapshot(JsonValues.object(raw, "worker")));
        }
        return List.copyOf(workers);
    }

    WorkerSnapshot worker(String workerGroupId, String clientWorkerKey) {
        JsonHttpClient.Response response = http.send(
                "GET",
                workerPath(workerGroupId, clientWorkerKey),
                null
        );
        requireStatus(response, 200, "load worker");
        return decodeSnapshot(response.body());
    }

    WorkerSnapshot start(String workerGroupId, String clientWorkerKey) {
        return action(workerGroupId, clientWorkerKey, ":start", "POST", 202);
    }

    WorkerSnapshot stop(String workerGroupId, String clientWorkerKey) {
        return action(workerGroupId, clientWorkerKey, ":stop", "POST", 202);
    }

    WorkerSnapshot scheduleStop(
            String workerGroupId,
            String clientWorkerKey,
            long delayMillis
    ) {
        JsonHttpClient.Response response = http.send(
                "POST",
                workerPath(workerGroupId, clientWorkerKey)
                        + ":schedule-stop",
                Map.of("delayMillis", delayMillis)
        );
        requireStatus(response, 202, "schedule worker stop");
        return decodeSnapshot(response.body());
    }

    void cancelScheduledStop(
            String workerGroupId,
            String clientWorkerKey
    ) {
        JsonHttpClient.Response response = http.send(
                "DELETE",
                workerPath(workerGroupId, clientWorkerKey)
                        + ":scheduled-stop",
                null
        );
        requireStatus(response, 204, "cancel worker stop");
    }

    WorkerSnapshot replaceProperties(
            String workerGroupId,
            String clientWorkerKey,
            Map<String, Object> workerProperties
    ) {
        JsonHttpClient.Response response = http.send(
                "PUT",
                workerPath(workerGroupId, clientWorkerKey),
                Map.of(
                        "schemaVersion", 2,
                        "workerProperties", workerProperties
                )
        );
        requireStatus(response, 200, "replace worker properties");
        return decodeSnapshot(response.body());
    }

    private WorkerSnapshot action(
            String workerGroupId,
            String clientWorkerKey,
            String suffix,
            String method,
            int expectedStatus
    ) {
        JsonHttpClient.Response response = http.send(
                method,
                workerPath(workerGroupId, clientWorkerKey) + suffix,
                null
        );
        requireStatus(response, expectedStatus, suffix.substring(1));
        return decodeSnapshot(response.body());
    }

    private static WorkerSnapshot decodeSnapshot(Map<String, Object> value) {
        Map<String, Object> properties = value.containsKey("workerProperties")
                ? Collections.unmodifiableMap(JsonValues.object(
                        value.get("workerProperties"),
                        "workerProperties"
                ))
                : null;
        Long scheduledStop = value.get("scheduledStopAtEpochMillis") == null
                ? null
                : JsonValues.requiredLong(
                        value,
                        "scheduledStopAtEpochMillis"
                );
        return new WorkerSnapshot(
                JsonValues.requiredString(value, "workerGroupId"),
                JsonValues.requiredString(value, "clientWorkerKey"),
                JsonValues.requiredString(value, "desiredState"),
                JsonValues.requiredString(value, "runtimeState"),
                JsonValues.optionalString(value, "workerId"),
                JsonValues.optionalString(value, "diagnosticMessage"),
                scheduledStop,
                properties
        );
    }

    private static void requireStatus(
            JsonHttpClient.Response response,
            int expected,
            String operation
    ) {
        if (response.statusCode() != expected) {
            throw new IllegalStateException(
                    "Worker Lab " + operation + " returned HTTP "
                            + response.statusCode()
            );
        }
    }

    private static String workerPath(
            String workerGroupId,
            String clientWorkerKey
    ) {
        return WORKERS_PATH
                + "/" + segment(workerGroupId)
                + "/" + segment(clientWorkerKey);
    }

    private static String segment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Worker coordinate must be non-blank"
            );
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    record WorkerSnapshot(
            String workerGroupId,
            String clientWorkerKey,
            String desiredState,
            String runtimeState,
            String workerId,
            String diagnosticMessage,
            Long scheduledStopAtEpochMillis,
            Map<String, Object> workerProperties
    ) {

        Map<String, Object> requireWorkerProperties() {
            if (workerProperties == null) {
                throw JsonValues.invalid("workerProperties are absent");
            }
            return new LinkedHashMap<>(workerProperties);
        }
    }
}
