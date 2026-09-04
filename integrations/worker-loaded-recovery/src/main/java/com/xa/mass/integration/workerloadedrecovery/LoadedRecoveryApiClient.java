package com.xa.mass.integration.workerloadedrecovery;

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

final class LoadedRecoveryApiClient {

    private static final int OBSERVATION_LIMIT = 100;
    private static final int RESULT_LOAD_LIMIT = 1_000;
    private static final int STOP_BATCH_LIMIT = 100;
    private static final int MAXIMUM_CANDIDATE_WORKERS = 100;

    private final LoadedRecoveryHttpClient lab;
    private final LoadedRecoveryHttpClient runtime;

    LoadedRecoveryApiClient(LoadedRecoveryHttpClient lab, LoadedRecoveryHttpClient runtime) {
        this.lab = java.util.Objects.requireNonNull(lab, "lab");
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
    }

    List<LabWorker> labWorkers() {
        LoadedRecoveryHttpClient.JsonResponse response = lab.json(
                "GET",
                "/lab/v1/workers",
                null
        );
        requireStatus(response.statusCode(), 200, "list Lab Workers");
        List<LabWorker> workers = new ArrayList<>();
        for (Object raw : LoadedRecoveryJson.array(
                response.body().get("workers"),
                "workers"
        )) {
            Map<String, Object> value = LoadedRecoveryJson.object(raw, "worker");
            workers.add(new LabWorker(
                    LoadedRecoveryJson.string(value, "workerGroupId"),
                    LoadedRecoveryJson.string(value, "labWorkerKey"),
                    LoadedRecoveryJson.string(value, "desiredState"),
                    LoadedRecoveryJson.string(value, "runtimeState"),
                    LoadedRecoveryJson.optionalString(value, "workerId")
            ));
        }
        return List.copyOf(workers);
    }

    void stopWorkers(String workerGroupId, List<String> labWorkerKeys) {
        if (labWorkerKeys == null
                || labWorkerKeys.isEmpty()
                || labWorkerKeys.size() > STOP_BATCH_LIMIT
                || new LinkedHashSet<>(labWorkerKeys).size()
                != labWorkerKeys.size()
                || labWorkerKeys.stream().anyMatch(
                        key -> key == null || key.isBlank()
                )) {
            throw new IllegalArgumentException(
                    "labWorkerKeys must contain 1..100 unique non-blank values"
            );
        }
        List<Map<String, Object>> request = new ArrayList<>();
        for (String labWorkerKey : labWorkerKeys) {
            request.add(Map.of(
                    "workerGroupId", workerGroupId,
                    "labWorkerKey", labWorkerKey
            ));
        }
        LoadedRecoveryHttpClient.JsonResponse response = lab.json(
                "POST",
                "/lab/v1/workers:stop",
                request
        );
        requireStatus(response.statusCode(), 202, "stop Lab Workers");
        if (!response.body().keySet().equals(Set.of("acceptedCount"))
                || LoadedRecoveryJson.integer(response.body(), "acceptedCount")
                != labWorkerKeys.size()) {
            throw LoadedRecoveryJson.invalid("Batch stop response changed");
        }
    }

    Map<String, String> observeNetwork(
            String endpointManagerId,
            List<String> workerIds
    ) {
        return observe(
                "/api/v1/runtime-view/endpoint-managers/"
                        + segment(endpointManagerId)
                        + "/workers:network-observe",
                workerIds,
                "network"
        );
    }

    Map<String, String> observeScheduling(
            String workerGroupId,
            List<String> workerIds
    ) {
        return observe(
                "/api/v1/runtime-view/worker-groups/"
                        + segment(workerGroupId)
                        + "/workers:scheduling-observe",
                workerIds,
                "scheduling"
        );
    }

    Map<String, String> previewTaskScoreBands(List<String> taskIds) {
        if (taskIds == null
                || taskIds.isEmpty()
                || taskIds.size() > 100
                || new LinkedHashSet<>(taskIds).size() != taskIds.size()
                || taskIds.stream().anyMatch(
                        taskId -> taskId == null || taskId.isBlank()
                )) {
            throw new IllegalArgumentException(
                    "taskIds must contain 1..100 unique non-blank values"
            );
        }
        LoadedRecoveryHttpClient.JsonResponse response = runtime.json(
                "POST",
                "/api/v1/runtime-view/tasks:preview",
                100
        );
        requireStatus(response.statusCode(), 200, "preview Tasks");
        if (!response.body().keySet().equals(
                Set.of("sampleLimit", "generatedAt", "entries")
        )) {
            throw LoadedRecoveryJson.invalid("Task preview fields changed");
        }
        if (LoadedRecoveryJson.integer(response.body(), "sampleLimit") != 100L) {
            throw LoadedRecoveryJson.invalid("Task preview sampleLimit changed");
        }
        LoadedRecoveryJson.string(response.body(), "generatedAt");

        Set<String> requested = new LinkedHashSet<>(taskIds);
        Map<String, String> scoreBands = new LinkedHashMap<>();
        for (Object raw : LoadedRecoveryJson.array(
                response.body().get("entries"),
                "Task preview entries"
        )) {
            Map<String, Object> entry = LoadedRecoveryJson.object(
                    raw,
                    "Task preview entry"
            );
            if (!entry.keySet().equals(
                    Set.of("taskId", "scoreBand", "task", "workerGroup")
            )) {
                throw LoadedRecoveryJson.invalid("Task preview entry fields changed");
            }
            String taskId = LoadedRecoveryJson.string(entry, "taskId");
            String scoreBand = LoadedRecoveryJson.string(entry, "scoreBand");
            if (requested.contains(taskId)
                    && scoreBands.putIfAbsent(taskId, scoreBand) != null) {
                throw LoadedRecoveryJson.invalid("Task preview contains duplicate taskId");
            }
        }
        return Collections.unmodifiableMap(scoreBands);
    }

    String createTask(String workerGroupId) {
        LoadedRecoveryHttpClient.JsonResponse response = runtime.json(
                "POST",
                "/api/v1/tasks",
                Map.of(
                        "workerGroupId", workerGroupId,
                        "allocationRule", Map.of(),
                        "priority", 50,
                        "maximumCandidateWorkers", MAXIMUM_CANDIDATE_WORKERS,
                        "maxRetryTimes", 3
                )
        );
        requireStatus(response.statusCode(), 200, "create Task");
        return LoadedRecoveryJson.string(response.body(), "taskId");
    }

    void appendItems(String taskId, List<TaskItem> items) {
        if (items == null || items.isEmpty() || items.size() > 100) {
            throw new IllegalArgumentException(
                    "items must contain 1..100 values"
            );
        }
        List<Map<String, Object>> encoded = new ArrayList<>();
        for (TaskItem item : items) {
            encoded.add(Map.of(
                    "messageId", item.messageId(),
                    "eventCode", item.eventCode(),
                    "payload", item.payload()
            ));
        }
        LoadedRecoveryHttpClient.JsonResponse response = runtime.json(
                "POST",
                "/api/v1/tasks/" + segment(taskId) + "/items",
                encoded
        );
        requireStatus(response.statusCode(), 200, "append Task Items");
        Map<String, Object> results = response.body();
        for (TaskItem item : items) {
            Map<String, Object> result = LoadedRecoveryJson.object(
                    results.get(item.messageId()),
                    "append result"
            );
            if (!"applied".equals(LoadedRecoveryJson.string(result, "status"))) {
                throw LoadedRecoveryJson.invalid(
                        "Task Item append failed for " + item.messageId()
                );
            }
        }
    }

    void approveTask(String taskId) {
        LoadedRecoveryHttpClient.JsonResponse response = runtime.json(
                "POST",
                "/api/v1/tasks/" + segment(taskId) + "/approve",
                null
        );
        requireStatus(response.statusCode(), 200, "approve Task");
        String status = LoadedRecoveryJson.string(response.body(), "status");
        if (!"applied".equals(status) && !"unchanged".equals(status)) {
            throw LoadedRecoveryJson.invalid("Task approval status is invalid");
        }
    }

    Map<String, TaskResultStatus> loadResultStatuses(
            String taskId,
            List<String> messageIds
    ) {
        if (messageIds == null
                || messageIds.isEmpty()
                || messageIds.size() > RESULT_LOAD_LIMIT
                || new LinkedHashSet<>(messageIds).size()
                != messageIds.size()
                || messageIds.stream().anyMatch(
                        messageId -> messageId == null || messageId.isBlank()
                )) {
            throw new IllegalArgumentException(
                    "messageIds must contain 1..1000 unique non-blank values"
            );
        }
        LoadedRecoveryHttpClient.JsonResponse response = runtime.json(
                "POST",
                "/api/v1/tasks/" + segment(taskId) + "/results:load",
                messageIds
        );
        requireStatus(response.statusCode(), 200, "load Task Results");
        if (!response.body().keySet().equals(
                new LinkedHashSet<>(messageIds)
        )) {
            throw LoadedRecoveryJson.invalid("Loaded result identities changed");
        }
        Map<String, TaskResultStatus> statuses = new LinkedHashMap<>();
        for (String messageId : messageIds) {
            Map<String, Object> result = LoadedRecoveryJson.object(
                    response.body().get(messageId),
                    "loaded result"
            );
            TaskResultStatus status = TaskResultStatus.fromWire(
                    LoadedRecoveryJson.string(result, "status")
            );
            Set<String> expectedFields;
            if (status == TaskResultStatus.SUCCEEDED) {
                LoadedRecoveryJson.string(result, "opaqueResultPayload");
                expectedFields = Set.of("status", "opaqueResultPayload");
            } else {
                expectedFields = Set.of("status");
            }
            if (!result.keySet().equals(expectedFields)) {
                throw LoadedRecoveryJson.invalid("Loaded result fields changed");
            }
            statuses.put(messageId, status);
        }
        return Collections.unmodifiableMap(statuses);
    }

    TaskExport exportTask(String taskId) {
        LoadedRecoveryHttpClient.BinaryResponse response = runtime.binary(
                "POST",
                "/api/v1/tasks/" + segment(taskId) + "/results:export",
                null
        );
        if (response.statusCode() == 400) {
            Map<String, Object> body = Jsons.parseObject(new String(
                    response.body(),
                    StandardCharsets.UTF_8
            ));
            if (LoadedRecoveryJson.integer(body, "code") != 12010L) {
                throw LoadedRecoveryJson.invalid("Task export error is not 12010");
            }
            return new TaskExport(false, Set.of());
        }
        requireStatus(response.statusCode(), 200, "export Task Results");
        Set<String> messageIds = new LinkedHashSet<>();
        String jsonl = new String(response.body(), StandardCharsets.UTF_8);
        for (String line : jsonl.lines().toList()) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> result = Jsons.parseObject(line);
            String messageId = LoadedRecoveryJson.string(result, "messageId");
            LoadedRecoveryJson.string(result, "opaqueResultPayload");
            if (!messageIds.add(messageId)) {
                throw LoadedRecoveryJson.invalid("Task export contains duplicate messageId");
            }
        }
        return new TaskExport(true, Set.copyOf(messageIds));
    }

    private Map<String, String> observe(
            String path,
            List<String> workerIds,
            String owner
    ) {
        if (workerIds == null
                || workerIds.isEmpty()
                || workerIds.size() > OBSERVATION_LIMIT
                || new LinkedHashSet<>(workerIds).size() != workerIds.size()) {
            throw new IllegalArgumentException(
                    "workerIds must contain 1..100 unique values"
            );
        }
        LoadedRecoveryHttpClient.JsonResponse response = runtime.json(
                "POST",
                path,
                workerIds
        );
        requireStatus(response.statusCode(), 200, "observe " + owner);
        Map<String, Object> raw = LoadedRecoveryJson.object(
                response.body().get("statesByWorkerId"),
                "statesByWorkerId"
        );
        if (!raw.keySet().equals(new LinkedHashSet<>(workerIds))) {
            throw LoadedRecoveryJson.invalid(
                    owner + " observation changed the requested Worker set"
            );
        }
        Map<String, String> states = new LinkedHashMap<>();
        for (String workerId : workerIds) {
            Object state = raw.get(workerId);
            if (!(state instanceof String text) || text.isBlank()) {
                throw LoadedRecoveryJson.invalid(owner + " state must be a string");
            }
            states.put(workerId, text);
        }
        return Collections.unmodifiableMap(states);
    }

    private static void requireStatus(
            int actual,
            int expected,
            String operation
    ) {
        if (actual != expected) {
            throw new IllegalStateException(
                    "Runtime API " + operation + " returned HTTP " + actual
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

    record LabWorker(
            String workerGroupId,
            String labWorkerKey,
            String desiredState,
            String runtimeState,
            String workerId
    ) {
    }

    record TaskItem(
            String messageId,
            String eventCode,
            Map<String, Object> payload
    ) {

        TaskItem {
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("messageId must be non-blank");
            }
            if (eventCode == null || eventCode.isBlank()) {
                throw new IllegalArgumentException("eventCode must be non-blank");
            }
            payload = Map.copyOf(payload);
        }
    }

    record TaskExport(boolean ready, Set<String> messageIds) {
    }

    enum TaskResultStatus {
        SUCCEEDED,
        FAILED,
        NOT_OBSERVED;

        static TaskResultStatus fromWire(String value) {
            return switch (value) {
                case "succeeded" -> SUCCEEDED;
                case "failed" -> FAILED;
                case "not_observed" -> NOT_OBSERVED;
                default -> throw LoadedRecoveryJson.invalid(
                        "Task Result status is invalid"
                );
            };
        }
    }
}
