package com.xa.mass.integration.workerscale;

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

final class ScaleApiClient {

    private static final int OBSERVATION_LIMIT = 100;

    private final ScaleHttpClient lab;
    private final ScaleHttpClient runtime;

    ScaleApiClient(ScaleHttpClient lab, ScaleHttpClient runtime) {
        this.lab = java.util.Objects.requireNonNull(lab, "lab");
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
    }

    List<LabWorker> labWorkers() {
        ScaleHttpClient.JsonResponse response = lab.json(
                "GET",
                "/lab/v1/workers",
                null
        );
        requireStatus(response.statusCode(), 200, "list Lab Workers");
        List<LabWorker> workers = new ArrayList<>();
        for (Object raw : ScaleJson.array(
                response.body().get("workers"),
                "workers"
        )) {
            Map<String, Object> value = ScaleJson.object(raw, "worker");
            workers.add(new LabWorker(
                    ScaleJson.string(value, "workerGroupId"),
                    ScaleJson.string(value, "labWorkerKey"),
                    ScaleJson.string(value, "desiredState"),
                    ScaleJson.string(value, "runtimeState"),
                    ScaleJson.optionalString(value, "workerId")
            ));
        }
        return List.copyOf(workers);
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

    String createTask(String workerGroupId) {
        ScaleHttpClient.JsonResponse response = runtime.json(
                "POST",
                "/api/v1/tasks",
                Map.of(
                        "workerGroupId", workerGroupId,
                        "allocationRule", Map.of(),
                        "priority", 50,
                        "maximumCandidateWorkers", 10,
                        "maxRetryTimes", 3
                )
        );
        requireStatus(response.statusCode(), 200, "create Task");
        return ScaleJson.string(response.body(), "taskId");
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
        ScaleHttpClient.JsonResponse response = runtime.json(
                "POST",
                "/api/v1/tasks/" + segment(taskId) + "/items",
                Map.of("items", encoded)
        );
        requireStatus(response.statusCode(), 200, "append Task Items");
        Map<String, Object> results = ScaleJson.object(
                response.body().get("results"),
                "append results"
        );
        for (TaskItem item : items) {
            Map<String, Object> result = ScaleJson.object(
                    results.get(item.messageId()),
                    "append result"
            );
            if (!"succeeded".equals(ScaleJson.string(result, "status"))) {
                throw ScaleJson.invalid(
                        "Task Item append failed for " + item.messageId()
                );
            }
        }
    }

    void approveTask(String taskId) {
        ScaleHttpClient.JsonResponse response = runtime.json(
                "POST",
                "/api/v1/tasks/" + segment(taskId) + "/approve",
                null
        );
        requireStatus(response.statusCode(), 200, "approve Task");
        String status = ScaleJson.string(response.body(), "status");
        if (!"approved".equals(status) && !"already_approved".equals(status)) {
            throw ScaleJson.invalid("Task approval status is invalid");
        }
    }

    TaskExport exportTask(String taskId) {
        ScaleHttpClient.BinaryResponse response = runtime.binary(
                "POST",
                "/api/v1/tasks/" + segment(taskId) + "/results:export",
                null
        );
        if (response.statusCode() == 400) {
            Map<String, Object> body = Jsons.parseObject(new String(
                    response.body(),
                    StandardCharsets.UTF_8
            ));
            if (ScaleJson.integer(body, "code") != 12010L) {
                throw ScaleJson.invalid("Task export error is not 12010");
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
            String messageId = ScaleJson.string(result, "messageId");
            ScaleJson.string(result, "opaqueResultPayload");
            if (!messageIds.add(messageId)) {
                throw ScaleJson.invalid("Task export contains duplicate messageId");
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
        ScaleHttpClient.JsonResponse response = runtime.json(
                "POST",
                path,
                Map.of("workerIds", workerIds)
        );
        requireStatus(response.statusCode(), 200, "observe " + owner);
        Map<String, Object> raw = ScaleJson.object(
                response.body().get("statesByWorkerId"),
                "statesByWorkerId"
        );
        if (!raw.keySet().equals(new LinkedHashSet<>(workerIds))) {
            throw ScaleJson.invalid(
                    owner + " observation changed the requested Worker set"
            );
        }
        Map<String, String> states = new LinkedHashMap<>();
        for (String workerId : workerIds) {
            Object state = raw.get(workerId);
            if (!(state instanceof String text) || text.isBlank()) {
                throw ScaleJson.invalid(owner + " state must be a string");
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
}
