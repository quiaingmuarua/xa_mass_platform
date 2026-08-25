package com.xa.mass.integration.workercapability.runtimeapi;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FiniteTaskApiClient {

    private static final int MAX_APPEND_ITEMS = 100;

    private final RuntimeApiHttpClient http;

    public FiniteTaskApiClient(RuntimeApiHttpClient http) {
        this.http = java.util.Objects.requireNonNull(http, "http");
    }

    public String createTask(String workerGroupId) {
        RuntimeApiHttpClient.ApiResponse response = http.send(
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
        RuntimeApiHttpClient.requireStatus(
                response,
                200,
                "capabilityTask.create"
        );
        return requiredString(response.body(), "taskId");
    }

    public void appendItems(String taskId, List<TaskItem> items) {
        if (items == null
                || items.isEmpty()
                || items.size() > MAX_APPEND_ITEMS) {
            throw new IllegalArgumentException(
                    "Task append requires 1..100 Items"
            );
        }
        List<Map<String, Object>> encoded = new ArrayList<>();
        for (TaskItem item : items) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("messageId", item.messageId());
            value.put("eventCode", item.eventCode());
            value.put("payload", item.payload());
            encoded.add(value);
        }
        RuntimeApiHttpClient.ApiResponse response = http.send(
                "POST",
                "/api/v1/tasks/" + RuntimeApiHttpClient.identifier(taskId)
                        + "/items",
                Map.of("items", encoded)
        );
        RuntimeApiHttpClient.requireStatus(
                response,
                200,
                "capabilityTask.append"
        );
        Map<String, Object> results = requiredMap(
                response.body(),
                "results"
        );
        for (TaskItem item : items) {
            Map<String, Object> result = requiredMap(
                    results,
                    item.messageId()
            );
            if (!"succeeded".equals(requiredString(result, "status"))) {
                throw invalid(
                        "Task Item was not appended: " + item.messageId()
                                + " (code=" + requiredInteger(result, "code")
                                + ", message="
                                + requiredString(result, "message") + ")"
                );
            }
        }
    }

    public void approveTask(String taskId) {
        RuntimeApiHttpClient.ApiResponse response = http.send(
                "POST",
                "/api/v1/tasks/" + RuntimeApiHttpClient.identifier(taskId)
                        + "/approve",
                null
        );
        RuntimeApiHttpClient.requireStatus(
                response,
                200,
                "capabilityTask.approve"
        );
        String status = requiredString(response.body(), "status");
        if (!"approved".equals(status)
                && !"already_approved".equals(status)) {
            throw invalid("Task approval status is invalid");
        }
    }

    public ExportResult exportResults(
            String taskId,
            long waitTimeoutMillis
    ) {
        RuntimeApiHttpClient.BinaryApiResponse response = http.sendBinary(
                "POST",
                "/api/v1/tasks/" + RuntimeApiHttpClient.identifier(taskId)
                        + "/results:export",
                Map.of("waitTimeoutMillis", waitTimeoutMillis)
        );
        if (response.statusCode() == 400) {
            Map<String, Object> body = Jsons.parseObject(new String(
                    response.body(),
                    StandardCharsets.UTF_8
            ));
            if (requiredInteger(body, "code") != 12010) {
                throw invalid("Task export business error is not 12010");
            }
            return new ExportResult(false, List.of());
        }
        if (response.statusCode() != 200) {
            throw invalid(
                    "Task export returned HTTP " + response.statusCode()
            );
        }
        String jsonl = new String(
                response.body(),
                StandardCharsets.UTF_8
        );
        List<SuccessResult> results = jsonl.lines()
                .map(Jsons::parseObject)
                .map(row -> new SuccessResult(
                        requiredString(row, "messageId"),
                        requiredString(row, "opaqueResultPayload")
                ))
                .toList();
        return new ExportResult(true, results);
    }

    private static String requiredString(
            Map<String, Object> body,
            String name
    ) {
        Object value = body.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalid("Runtime API response requires " + name);
        }
        return text;
    }

    private static int requiredInteger(
            Map<String, Object> body,
            String name
    ) {
        Object value = body.get(name);
        if (!(value instanceof Number number)) {
            throw invalid("Runtime API response requires " + name);
        }
        return number.intValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requiredMap(
            Map<String, Object> body,
            String name
    ) {
        Object value = body.get(name);
        if (!(value instanceof Map<?, ?> map)
                || map.keySet().stream().anyMatch(
                        key -> !(key instanceof String)
                )) {
            throw invalid("Runtime API response requires " + name);
        }
        return (Map<String, Object>) map;
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException(
                "Capability Task API failed: " + message
        );
    }

    public record TaskItem(
            String messageId,
            String eventCode,
            Map<String, Object> payload
    ) {

        public TaskItem {
            RuntimeApiHttpClient.identifier(messageId);
            if (eventCode == null || eventCode.isBlank()) {
                throw new IllegalArgumentException(
                        "eventCode must be non-blank"
                );
            }
            payload = Map.copyOf(payload);
        }
    }

    public record SuccessResult(
            String messageId,
            String opaqueResultPayload
    ) {
    }

    public record ExportResult(
            boolean ready,
            List<SuccessResult> results
    ) {

        public ExportResult {
            results = List.copyOf(results);
            if (!ready && !results.isEmpty()) {
                throw new IllegalArgumentException(
                        "not-ready export cannot contain results"
                );
            }
        }
    }
}
