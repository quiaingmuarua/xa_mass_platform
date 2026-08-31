package com.xa.mass.integration.workerlab;

import com.xa.mass.workerdelivery.json.Jsons;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RuntimeApiClient {

    private final JsonHttpClient http;

    RuntimeApiClient(JsonHttpClient http) {
        this.http = java.util.Objects.requireNonNull(http, "http");
    }

    Map<String, WorkerView> previewWorkers(String workerGroupId) {
        JsonHttpClient.Response response = http.send(
                "POST",
                "/api/v1/runtime-view/worker-groups/"
                        + segment(workerGroupId)
                        + "/workers:preview",
                Map.of("sampleLimit", 100)
        );
        requireStatus(response, 200, "preview workers");
        if (JsonValues.requiredLong(response.body(), "unreadableCount") != 0) {
            throw JsonValues.invalid("Worker preview is unreadable");
        }
        Map<String, WorkerView> workers = new LinkedHashMap<>();
        for (Object raw : JsonValues.array(
                response.body().get("workers"),
                "workers"
        )) {
            Map<String, Object> value = JsonValues.object(raw, "worker");
            if (!workerGroupId.equals(
                    JsonValues.requiredString(value, "workerGroupId")
            )) {
                throw JsonValues.invalid("WorkerGroup identity changed");
            }
            Map<String, Object> properties = JsonValues.object(
                    value.get("workerProperties"),
                    "workerProperties"
            );
            String clientWorkerKey = JsonValues.requiredString(
                    properties,
                    "clientWorkerKey"
            );
            WorkerView previous = workers.putIfAbsent(
                    clientWorkerKey,
                    new WorkerView(
                            JsonValues.requiredString(value, "workerId"),
                            Collections.unmodifiableMap(properties)
                    )
            );
            if (previous != null) {
                throw JsonValues.invalid("Duplicate clientWorkerKey");
            }
        }
        return Collections.unmodifiableMap(workers);
    }

    Map<String, String> observeNetwork(
            String endpointManagerId,
            List<String> workerIds
    ) {
        return observeStates(
                "/api/v1/runtime-view/endpoint-managers/"
                        + segment(endpointManagerId)
                        + "/workers:network-observe",
                Map.of("workerIds", workerIds),
                "observe network"
        );
    }

    Map<String, String> observeScheduling(
            String workerGroupId,
            List<String> workerIds
    ) {
        return observeStates(
                "/api/v1/runtime-view/worker-groups/"
                        + segment(workerGroupId)
                        + "/workers:scheduling-observe",
                Map.of("workerIds", workerIds),
                "observe scheduling"
        );
    }

    String createTask(
            String workerGroupId,
            Map<String, Object> allocationRule
    ) {
        JsonHttpClient.Response response = http.send(
                "POST",
                "/api/v1/tasks",
                Map.of(
                        "workerGroupId", workerGroupId,
                        "allocationRule", allocationRule,
                        "priority", 50,
                        "maximumCandidateWorkers", 10,
                        "maxRetryTimes", 3
                )
        );
        requireStatus(response, 200, "create Task");
        return JsonValues.requiredString(response.body(), "taskId");
    }

    void appendItems(String taskId, List<TaskItem> items) {
        if (items == null || items.isEmpty() || items.size() > 100) {
            throw new IllegalArgumentException("items must contain 1..100 values");
        }
        List<Map<String, Object>> encoded = new ArrayList<>();
        for (TaskItem item : items) {
            encoded.add(Map.of(
                    "messageId", item.messageId(),
                    "eventCode", item.eventCode(),
                    "payload", item.payload()
            ));
        }
        JsonHttpClient.Response response = http.send(
                "POST",
                "/api/v1/tasks/" + segment(taskId) + "/items",
                Map.of("items", encoded)
        );
        requireStatus(response, 200, "append Task Items");
        Map<String, Object> outcomes = JsonValues.object(
                response.body().get("results"),
                "append results"
        );
        for (TaskItem item : items) {
            Map<String, Object> outcome = JsonValues.object(
                    outcomes.get(item.messageId()),
                    "append outcome"
            );
            if (!"succeeded".equals(
                    JsonValues.requiredString(outcome, "status")
            )) {
                throw JsonValues.invalid(
                        "Task Item append failed: " + item.messageId()
                );
            }
        }
    }

    void approveTask(String taskId) {
        JsonHttpClient.Response response = http.send(
                "POST",
                "/api/v1/tasks/" + segment(taskId) + "/approve",
                null
        );
        requireStatus(response, 200, "approve Task");
        String status = JsonValues.requiredString(response.body(), "status");
        if (!"approved".equals(status) && !"already_approved".equals(status)) {
            throw JsonValues.invalid("Task approval status is invalid");
        }
    }

    ExportResult exportResults(String taskId, long waitTimeoutMillis) {
        JsonHttpClient.BinaryResponse response = http.sendBinary(
                "POST",
                "/api/v1/tasks/" + segment(taskId) + "/results:export",
                Map.of("waitTimeoutMillis", waitTimeoutMillis)
        );
        if (response.statusCode() == 400) {
            Map<String, Object> body = Jsons.parseObject(new String(
                    response.body(),
                    StandardCharsets.UTF_8
            ));
            if (JsonValues.requiredLong(body, "code") != 12010L) {
                throw JsonValues.invalid("Task export error is not 12010");
            }
            return new ExportResult(false, Set.of());
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Runtime API export Task returned HTTP "
                            + response.statusCode()
            );
        }
        Set<String> messageIds = new LinkedHashSet<>();
        String jsonl = new String(response.body(), StandardCharsets.UTF_8);
        for (String line : jsonl.lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> result = Jsons.parseObject(line);
            String messageId = JsonValues.requiredString(result, "messageId");
            if (!messageIds.add(messageId)) {
                throw JsonValues.invalid("Duplicate exported messageId");
            }
        }
        return new ExportResult(true, Set.copyOf(messageIds));
    }

    private Map<String, String> observeStates(
            String path,
            Map<String, Object> body,
            String operation
    ) {
        JsonHttpClient.Response response = http.send("POST", path, body);
        requireStatus(response, 200, operation);
        Map<String, Object> raw = JsonValues.object(
                response.body().get("statesByWorkerId"),
                "statesByWorkerId"
        );
        Map<String, String> states = new LinkedHashMap<>();
        raw.forEach((workerId, state) -> {
            if (!(state instanceof String text)) {
                throw JsonValues.invalid("Worker state must be a string");
            }
            states.put(workerId, text);
        });
        return Collections.unmodifiableMap(states);
    }

    private static void requireStatus(
            JsonHttpClient.Response response,
            int expected,
            String operation
    ) {
        if (response.statusCode() != expected) {
            throw new IllegalStateException(
                    "Runtime API " + operation + " returned HTTP "
                            + response.statusCode()
            );
        }
    }

    private static String segment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("identifier must be non-blank");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    record WorkerView(String workerId, Map<String, Object> workerProperties) {
    }

    record TaskItem(
            String messageId,
            String eventCode,
            Map<String, Object> payload
    ) {
    }

    record ExportResult(boolean ready, Set<String> messageIds) {
    }
}
