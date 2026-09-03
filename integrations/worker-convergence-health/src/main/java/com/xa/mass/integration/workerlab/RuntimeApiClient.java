package com.xa.mass.integration.workerlab;

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
                100
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
            String labWorkerKey = labWorkerKey(properties);
            WorkerView previous = workers.putIfAbsent(
                    labWorkerKey,
                    new WorkerView(
                            JsonValues.requiredString(value, "workerId"),
                            Collections.unmodifiableMap(properties)
                    )
            );
            if (previous != null) {
                throw JsonValues.invalid("Duplicate labWorkerKey");
            }
        }
        return Collections.unmodifiableMap(workers);
    }

    private static String labWorkerKey(Map<String, Object> properties) {
        String inventoryKey = JsonValues.requiredString(
                properties,
                "labInventoryKey"
        );
        long inventoryLine = JsonValues.requiredLong(
                properties,
                "labInventoryLine"
        );
        if (inventoryLine < 1L || inventoryLine > 100L) {
            throw JsonValues.invalid(
                    "labInventoryLine must be in 1..100"
            );
        }
        return inventoryKey + ":" + inventoryLine;
    }

    Map<String, String> observeNetwork(
            String endpointManagerId,
            List<String> workerIds
    ) {
        return observeStates(
                "/api/v1/runtime-view/endpoint-managers/"
                        + segment(endpointManagerId)
                        + "/workers:network-observe",
                workerIds,
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
                workerIds,
                "observe scheduling"
        );
    }

    Map<String, String> previewTaskScoreBands(List<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty() || taskIds.size() > 100) {
            throw new IllegalArgumentException(
                    "taskIds must contain 1..100 values"
            );
        }
        Set<String> requested = new LinkedHashSet<>();
        for (String taskId : taskIds) {
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException(
                        "taskIds must contain non-blank values"
                );
            }
            requested.add(taskId);
        }
        JsonHttpClient.Response response = http.send(
                "POST",
                "/api/v1/runtime-view/tasks:preview",
                100
        );
        requireStatus(response, 200, "preview Tasks");
        Map<String, String> scoreBands = new LinkedHashMap<>();
        for (Object raw : JsonValues.array(
                response.body().get("entries"),
                "Task preview entries"
        )) {
            Map<String, Object> entry = JsonValues.object(
                    raw,
                    "Task preview entry"
            );
            String taskId = JsonValues.requiredString(entry, "taskId");
            if (!requested.contains(taskId)) {
                continue;
            }
            String previous = scoreBands.putIfAbsent(
                    taskId,
                    JsonValues.requiredString(entry, "scoreBand")
            );
            if (previous != null) {
                throw JsonValues.invalid("Duplicate Task preview identity");
            }
        }
        return Collections.unmodifiableMap(scoreBands);
    }

    Map<String, CallStatus> callItems(
            String taskId,
            List<TaskItem> items,
            long waitTimeoutMillis
    ) {
        if (items == null || items.isEmpty() || items.size() > 100) {
            throw new IllegalArgumentException("items must contain 1..100 values");
        }
        List<Map<String, Object>> encoded = new ArrayList<>();
        for (TaskItem item : items) {
            if (item.workerSelector() == null) {
                throw new IllegalArgumentException(
                        "managed Task call Item requires workerSelector"
                );
            }
            encoded.add(Map.of(
                    "messageId", item.messageId(),
                    "eventCode", item.eventCode(),
                    "payload", item.payload(),
                    "workerSelector", item.workerSelector()
            ));
        }
        JsonHttpClient.Response response = http.send(
                "POST",
                "/api/v1/tasks/" + segment(taskId) + "/items:call",
                Map.of(
                        "items", encoded,
                        "waitTimeoutMillis", waitTimeoutMillis
                )
        );
        requireStatus(response, 200, "call Task Items");
        Map<String, Object> results = response.body();
        if (!results.keySet().equals(items.stream()
                .map(TaskItem::messageId)
                .collect(java.util.stream.Collectors.toSet()))) {
            throw JsonValues.invalid("Task call result identities changed");
        }
        Map<String, CallStatus> statuses = new LinkedHashMap<>();
        for (TaskItem item : items) {
            Map<String, Object> result = JsonValues.object(
                    results.get(item.messageId()),
                    "call result"
            );
            statuses.put(
                    item.messageId(),
                    CallStatus.fromWire(
                            JsonValues.requiredString(result, "status")
                    )
            );
        }
        return Collections.unmodifiableMap(statuses);
    }

    Map<String, CallStatus> loadResultStatuses(
            String taskId,
            List<String> messageIds
    ) {
        if (messageIds == null || messageIds.isEmpty()
                || messageIds.size() > 1_000) {
            throw new IllegalArgumentException(
                    "messageIds must contain 1..1000 values"
            );
        }
        JsonHttpClient.Response response = http.send(
                "POST",
                "/api/v1/tasks/" + segment(taskId) + "/results:load",
                messageIds
        );
        requireStatus(response, 200, "load Task results");
        Map<String, Object> results = response.body();
        var expected = java.util.Set.copyOf(messageIds);
        if (!results.keySet().equals(expected)) {
            throw JsonValues.invalid("Loaded result identities changed");
        }
        Map<String, CallStatus> statuses = new LinkedHashMap<>();
        for (String messageId : messageIds) {
            Object rawResult = results.get(messageId);
            Map<String, Object> result = JsonValues.object(
                    rawResult,
                    "loaded result"
            );
            CallStatus status = CallStatus.fromWire(
                    JsonValues.requiredString(result, "status")
            );
            statuses.put(messageId, status);
        }
        return Collections.unmodifiableMap(statuses);
    }

    PrecomputedWitness submitPrecomputedWitness(
            String workerGroupId,
            Map<String, Object> allocationRule,
            String messageId,
            String eventCode,
            Map<String, Object> payload
    ) {
        if (workerGroupId == null || workerGroupId.isBlank()
                || allocationRule == null
                || messageId == null || messageId.isBlank()
                || eventCode == null || eventCode.isBlank()
                || payload == null) {
            throw new IllegalArgumentException(
                    "Precomputed witness inputs are invalid"
            );
        }
        JsonHttpClient.Response created = http.send(
                "POST",
                "/api/v1/tasks",
                Map.of(
                        "workerGroupId", workerGroupId,
                        "allocationRule", allocationRule,
                        "priority", 50,
                        "maximumCandidateWorkers", 1,
                        "maxRetryTimes", 3
                )
        );
        requireStatus(created, 200, "create precomputed witness Task");
        String taskId = JsonValues.requiredString(created.body(), "taskId");

        JsonHttpClient.Response appended = http.send(
                "POST",
                "/api/v1/tasks/" + segment(taskId) + "/items",
                List.of(Map.of(
                        "messageId", messageId,
                        "eventCode", eventCode,
                        "payload", payload
                ))
        );
        requireStatus(appended, 200, "append precomputed witness Item");
        Map<String, Object> appendOutcome = JsonValues.object(
                appended.body().get(messageId),
                "precomputed witness append outcome"
        );
        String appendStatus = JsonValues.requiredString(
                appendOutcome,
                "status"
        );
        if (!"applied".equals(appendStatus)
                && !"unchanged".equals(appendStatus)) {
            throw JsonValues.invalid(
                    "Precomputed witness Item was not accepted"
            );
        }

        JsonHttpClient.Response approved = http.send(
                "POST",
                "/api/v1/tasks/" + segment(taskId) + "/approve",
                null
        );
        requireStatus(approved, 200, "approve precomputed witness Task");
        String approvalStatus = JsonValues.requiredString(
                approved.body(),
                "status"
        );
        if (!"applied".equals(approvalStatus)
                && !"unchanged".equals(approvalStatus)) {
            throw JsonValues.invalid(
                    "Precomputed witness Task was not approved"
            );
        }
        return new PrecomputedWitness(taskId, messageId);
    }

    static String managedTaskId(String workerGroupId) {
        if (workerGroupId == null || workerGroupId.isBlank()) {
            throw new IllegalArgumentException("workerGroupId must be non-blank");
        }
        return "scenario-rpc-" + workerGroupId;
    }

    private Map<String, String> observeStates(
            String path,
            Object body,
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

    record PrecomputedWitness(String taskId, String messageId) {
        PrecomputedWitness {
            if (taskId == null || taskId.isBlank()
                    || messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException(
                        "Precomputed witness identities must be non-blank"
                );
            }
        }
    }

    record TaskItem(
            String messageId,
            String eventCode,
            Map<String, Object> payload,
            List<Object> workerSelector
    ) {
        TaskItem {
            payload = Map.copyOf(payload);
            workerSelector = List.copyOf(workerSelector);
        }
    }

    enum CallStatus {
        SUCCEEDED,
        FAILED,
        NOT_OBSERVED;

        String wireValue() {
            return switch (this) {
                case SUCCEEDED -> "succeeded";
                case FAILED -> "failed";
                case NOT_OBSERVED -> "not_observed";
            };
        }

        private static CallStatus fromWire(String value) {
            return switch (value) {
                case "succeeded" -> SUCCEEDED;
                case "failed" -> FAILED;
                case "not_observed" -> NOT_OBSERVED;
                default -> throw JsonValues.invalid(
                        "Task call status is invalid"
                );
            };
        }
    }
}
