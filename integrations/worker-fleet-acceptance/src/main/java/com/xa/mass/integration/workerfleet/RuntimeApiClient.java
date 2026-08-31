package com.xa.mass.integration.workerfleet;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                Map.of("sampleLimit", 100),
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
                    || !(rawInventoryLine instanceof Number line)
                    || line.longValue() < 1L
                    || line.longValue() > 100L) {
                throw new IllegalStateException(
                        "Worker Runtime preview has no Lab inventory coordinate"
                );
            }
            String labWorkerKey = inventoryKey + ":" + line.longValue();
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
                Map.of("workerIds", workerIds),
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
