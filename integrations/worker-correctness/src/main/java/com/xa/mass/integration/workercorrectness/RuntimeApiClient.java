package com.xa.mass.integration.workercorrectness;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RuntimeApiClient {

    private static final long DIRECT_CALL_WAIT_MILLIS = 10_000L;

    private final URI baseUrl;
    private final Duration requestTimeout;
    private final HttpClient http;

    RuntimeApiClient(URI baseUrl, Duration requestTimeout) {
        this.baseUrl = baseUrl;
        this.requestTimeout = requestTimeout;
        http = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    Map<String, String> previewWorkerIdentities(String workerGroupId) {
        Map<String, Object> response = post(
                "/api/v1/runtime-view/worker-groups/"
                        + workerGroupId
                        + "/workers:preview",
                100,
                "workerRuntime.preview"
        );
        Object unreadable = response.get("unreadableCount");
        if (!(unreadable instanceof Number)
                || ((Number) unreadable).intValue() != 0) {
            throw new IllegalStateException(
                    "Worker Runtime preview contains unreadable descriptors"
            );
        }
        Object rawWorkers = response.get("workers");
        if (!(rawWorkers instanceof List<?> workers)) {
            throw new IllegalStateException(
                    "Worker Runtime preview workers must be an array"
            );
        }
        Map<String, String> identities = new LinkedHashMap<>();
        for (Object rawWorker : workers) {
            Map<String, Object> worker = objectMap(
                    rawWorker,
                    "Worker Runtime preview worker"
            );
            if (!workerGroupId.equals(worker.get("workerGroupId"))
                    || !(worker.get("workerId") instanceof String workerId)
                    || workerId.isBlank()) {
                throw new IllegalStateException(
                        "Worker Runtime preview identity is invalid"
                );
            }
            Map<String, Object> properties = objectMap(
                    worker.get("workerProperties"),
                    "Worker Runtime preview properties"
            );
            Object rawInventoryKey = properties.get("labInventoryKey");
            Object rawInventoryLine = properties.get("labInventoryLine");
            if (!(rawInventoryKey instanceof String inventoryKey)
                    || inventoryKey.isBlank()
                    || !(rawInventoryLine instanceof String line)
                    || !line.matches("[1-9][0-9]?|100")) {
                throw new IllegalStateException(
                        "Worker Runtime preview has no Lab inventory coordinate"
                );
            }
            String labWorkerKey = inventoryKey + ":" + line;
            if (identities.putIfAbsent(labWorkerKey, workerId) != null) {
                throw new IllegalStateException(
                        "Worker Runtime preview has duplicate labWorkerKey"
                );
            }
        }
        return Collections.unmodifiableMap(identities);
    }

    Map<String, String> observeNetwork(
            String endpointManagerId,
            List<String> workerIds
    ) {
        Map<String, Object> response = post(
                "/api/v1/runtime-view/endpoint-managers/"
                        + endpointManagerId
                        + "/workers:network-observe",
                workerIds,
                "workerNetwork.observe"
        );
        Map<String, Object> rawStates = objectMap(
                response.get("statesByWorkerId"),
                "statesByWorkerId"
        );
        Map<String, String> states = new LinkedHashMap<>();
        rawStates.forEach((workerId, value) -> {
            if (!(value instanceof String state)) {
                throw new IllegalStateException(
                        "Network state must be a string"
                );
            }
            states.put(workerId, state);
        });
        return Collections.unmodifiableMap(states);
    }

    DirectCallOutcome callWorkers(
            String endpointManagerId,
            String workerGroupId,
            List<String> workerIds,
            String messageType
    ) {
        Map<String, String> workerPayloads = new LinkedHashMap<>();
        workerIds.forEach(workerId -> workerPayloads.put(workerId, "null"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerGroupId", workerGroupId);
        body.put("workerPayloads", workerPayloads);
        body.put("messageType", messageType);
        body.put("waitTimeoutMillis", DIRECT_CALL_WAIT_MILLIS);
        return decodeDirectCall(post(
                directCallPath(endpointManagerId),
                body,
                "workerDirectCall"
        ));
    }

    TargetOutcome callAdapter(
            String endpointManagerId,
            String messageType,
            String opaquePayload
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageType", messageType);
        body.put("opaquePayload", opaquePayload);
        body.put("waitTimeoutMillis", DIRECT_CALL_WAIT_MILLIS);
        DirectCallOutcome call = decodeDirectCall(post(
                directCallPath(endpointManagerId),
                body,
                "adapterDirectCall"
        ));
        TargetOutcome target = call.results().get(endpointManagerId);
        if (target == null || call.results().size() != 1) {
            throw new IllegalStateException(
                    "Adapter Direct Call result identities do not match"
            );
        }
        return target;
    }

    Map<String, CallStatus> callItems(
            String workerGroupId,
            List<TaskItem> items,
            long waitTimeoutMillis
    ) {
        if (items == null || items.isEmpty() || items.size() > 100) {
            throw new IllegalArgumentException("items must contain 1..100 values");
        }
        List<Map<String, Object>> encoded = new ArrayList<>();
        for (TaskItem item : items) {
            encoded.add(Map.of(
                    "messageId", item.messageId(),
                    "eventCode", item.eventCode(),
                    "payload", item.payload(),
                    "workerSelector", List.of()
            ));
        }
        Map<String, Object> response = post(
                "/api/v1/tasks/"
                        + managedTaskId(workerGroupId)
                        + "/items:call",
                Map.of(
                        "items", encoded,
                        "waitTimeoutMillis", waitTimeoutMillis
                ),
                "workerCorrectness.items.call"
        );
        Map<String, Object> rawResults = response;
        Set<String> expectedIds = items.stream()
                .map(TaskItem::messageId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!rawResults.keySet().equals(expectedIds)) {
            throw new IllegalStateException(
                    "Task call result identities do not match submitted Items"
            );
        }
        Map<String, CallStatus> results = new LinkedHashMap<>();
        for (TaskItem item : items) {
            Map<String, Object> result = objectMap(
                    rawResults.get(item.messageId()),
                    "Task call result"
            );
            results.put(
                    item.messageId(),
                    CallStatus.fromWire(requiredString(result, "status"))
            );
        }
        return Collections.unmodifiableMap(results);
    }

    private static String managedTaskId(String workerGroupId) {
        if (workerGroupId == null || workerGroupId.isBlank()) {
            throw new IllegalArgumentException("workerGroupId must be non-blank");
        }
        return "scenario-rpc-" + workerGroupId;
    }

    private DirectCallOutcome decodeDirectCall(Map<String, Object> response) {
        Object rawStatus = response.get("status");
        if (!(rawStatus instanceof String status)) {
            throw new IllegalStateException(
                    "Direct Call response requires status"
            );
        }
        Map<String, Object> rawResults = objectMap(
                response.get("results"),
                "Direct Call results"
        );
        Map<String, TargetOutcome> results = new LinkedHashMap<>();
        rawResults.forEach((targetId, rawTarget) -> {
            Map<String, Object> target = objectMap(
                    rawTarget,
                    "Direct Call target"
            );
            results.put(targetId, new TargetOutcome(
                    optionalString(target.get("status")),
                    optionalString(target.get("outcomeCode")),
                    optionalString(target.get("opaqueResultPayload"))
            ));
        });
        return new DirectCallOutcome(
                status,
                Collections.unmodifiableMap(results)
        );
    }

    private Map<String, Object> post(
            String path,
            Object body,
            String operation
    ) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(endpoint(path))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        Jsons.toJson(body),
                        StandardCharsets.UTF_8
                ))
                .build();
        try {
            HttpResponse<String> response = http.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        operation + " returned HTTP " + response.statusCode()
                );
            }
            return Jsons.parseObject(response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    operation + " was interrupted",
                    error
            );
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException(
                    operation + " request failed",
                    error
            );
        }
    }

    private URI endpoint(String path) {
        String base = baseUrl.toString();
        return URI.create((base.endsWith("/")
                ? base.substring(0, base.length() - 1)
                : base) + path);
    }

    private static String directCallPath(String endpointManagerId) {
        return "/api/v1/worker-delivery/endpoint-managers/"
                + endpointManagerId
                + "/direct-calls";
    }

    static Map<String, Object> objectMap(Object value, String name) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalStateException(name + " must be an object");
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalStateException(
                        name + " keys must be strings"
                );
            }
            copied.put(key, entry.getValue());
        }
        return copied;
    }

    private static String optionalString(Object value) {
        return value instanceof String text ? text : null;
    }

    private static String requiredString(
            Map<String, Object> value,
            String name
    ) {
        Object raw = value.get(name);
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new IllegalStateException(name + " must be non-blank");
        }
        return text;
    }

    record TaskItem(
            String messageId,
            String eventCode,
            Map<String, Object> payload
    ) {
        TaskItem {
            if (messageId == null || messageId.isBlank()
                    || eventCode == null || eventCode.isBlank()) {
                throw new IllegalArgumentException(
                        "Task Item identity and eventCode must be non-blank"
                );
            }
            payload = Map.copyOf(payload);
        }
    }

    enum CallStatus {
        SUCCEEDED,
        FAILED,
        NOT_OBSERVED;

        private static CallStatus fromWire(String value) {
            return switch (value) {
                case "succeeded" -> SUCCEEDED;
                case "failed" -> FAILED;
                case "not_observed" -> NOT_OBSERVED;
                default -> throw new IllegalStateException(
                        "Task call status is invalid"
                );
            };
        }
    }

    record DirectCallOutcome(
            String status,
            Map<String, TargetOutcome> results
    ) {
    }

    record TargetOutcome(
            String status,
            String outcomeCode,
            String opaqueResultPayload
    ) {

        boolean successful() {
            return "observed".equals(status)
                    && "200".equals(outcomeCode)
                    && opaqueResultPayload != null;
        }
    }
}
