package com.xa.mass.integration.androidworkerproof;

import com.xa.mass.workerdelivery.json.Jsons;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class AndroidRuntimeApiClient {

    private static final long DIRECT_CALL_WAIT_MILLIS = 10_000L;

    private final JsonHttpClient http;

    AndroidRuntimeApiClient(JsonHttpClient http) {
        this.http = java.util.Objects.requireNonNull(http, "http");
    }

    String networkState(String endpointManagerId, String workerId) {
        return observedState(
                "/api/v1/runtime-view/endpoint-managers/"
                        + segment(endpointManagerId)
                        + "/workers:network-observe",
                workerId,
                "workerNetwork.observe"
        );
    }

    String schedulingState(String workerGroupId, String workerId) {
        return observedState(
                "/api/v1/runtime-view/worker-groups/"
                        + segment(workerGroupId)
                        + "/workers:scheduling-observe",
                workerId,
                "workerScheduling.observe"
        );
    }

    DirectTarget callWorker(
            String endpointManagerId,
            String workerId,
            String messageType,
            String opaquePayload
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workerGroupId", AndroidWorkerProofConstants.WORKER_GROUP_ID);
        body.put("workerPayloads", Map.of(workerId, opaquePayload));
        body.put("messageType", messageType);
        body.put("waitTimeoutMillis", DIRECT_CALL_WAIT_MILLIS);
        DirectCall call = directCall(endpointManagerId, body);
        if (!call.targets().keySet().equals(Set.of(workerId))) {
            throw identityFailure(
                    "direct-call.worker-identities",
                    "Worker Direct Call result identities do not match",
                    workerId,
                    call.targets().keySet()
            );
        }
        return call.targets().get(workerId);
    }

    DirectTarget callAdapter(
            String endpointManagerId,
            String messageType,
            String opaquePayload
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageType", messageType);
        body.put("opaquePayload", opaquePayload);
        body.put("waitTimeoutMillis", DIRECT_CALL_WAIT_MILLIS);
        DirectCall call = directCall(endpointManagerId, body);
        if (!call.targets().keySet().equals(Set.of(endpointManagerId))) {
            throw new ProofFailure(
                    "direct-call.adapter-identities",
                    "Adapter Direct Call result identities do not match"
            );
        }
        return call.targets().get(endpointManagerId);
    }

    String closeCurrentConnection(String endpointManagerId, String workerId) {
        DirectTarget target = callAdapter(
                endpointManagerId,
                AndroidWorkerProofConstants.ADAPTER_CLOSE_CURRENT_EVENT,
                Jsons.toJson(Map.of("workerIds", List.of(workerId)))
        );
        target.requireSuccessful("Adapter close-current");
        Map<String, Object> payload = Jsons.parseObject(
                target.opaqueResultPayload()
        );
        Map<String, Object> outcomes = JsonValues.object(
                payload.get("outcomeByWorkerId"),
                "close-current outcomes"
        );
        if (!outcomes.keySet().equals(Set.of(workerId))
                || !(outcomes.get(workerId) instanceof String outcome)) {
            throw new ProofFailure(
                    "adapter.close-current.contract",
                    "Adapter close-current result is invalid"
            );
        }
        return outcome;
    }

    TaskCall callItem(
            String eventName,
            Map<String, Object> payload,
            long waitTimeoutMillis
    ) {
        return callItems(
                List.of(new TaskItemCall(eventName, payload, Map.of())),
                waitTimeoutMillis
        ).get(0);
    }

    TaskCall callItem(
            String eventName,
            Map<String, Object> payload,
            Map<String, Object> allocationRule,
            long waitTimeoutMillis
    ) {
        return callItems(
                List.of(new TaskItemCall(eventName, payload, allocationRule)),
                waitTimeoutMillis
        ).get(0);
    }

    List<TaskCall> callItems(
            List<TaskItemCall> calls,
            long waitTimeoutMillis
    ) {
        if (calls == null || calls.isEmpty() || calls.size() > 100) {
            throw new IllegalArgumentException("calls must contain 1..100 items");
        }
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        List<String> messageIds = new java.util.ArrayList<>();
        for (TaskItemCall call : calls) {
            String messageId = UUID.randomUUID().toString();
            messageIds.add(messageId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("messageId", messageId);
            item.put("eventCode", call.eventName());
            item.put("payload", call.payload());
            item.put("allocationRule", call.allocationRule());
            items.add(Map.copyOf(item));
        }
        JsonHttpClient.Response response = http.send(
                "POST",
                "/api/v1/tasks/"
                        + segment(managedTaskId())
                        + "/items:call",
                Map.of(
                        "items", List.copyOf(items),
                        "waitTimeoutMillis", waitTimeoutMillis
                ),
                "androidWorker.items.call"
        );
        requireStatus(response, 200, "Task items:call");
        Map<String, Object> results = response.body();
        Set<String> expectedIds = Set.copyOf(messageIds);
        if (!results.keySet().equals(expectedIds)) {
            throw new ProofFailure(
                    "task-call.identities",
                    "Task call result identities do not match",
                    expectedIds.stream()
                            .filter(id -> !results.containsKey(id))
                            .toList(),
                    results.keySet().stream()
                            .filter(id -> !expectedIds.contains(id))
                            .toList(),
                    List.of()
            );
        }
        List<TaskCall> resolved = new java.util.ArrayList<>();
        for (String messageId : messageIds) {
            Map<String, Object> result = JsonValues.object(
                    results.get(messageId),
                    "Task call result"
            );
            resolved.add(new TaskCall(
                    messageId,
                    CallStatus.fromWire(JsonValues.requiredString(
                            result,
                            "status"
                    ))
            ));
        }
        return List.copyOf(resolved);
    }

    CallStatus resultStatus(String messageId) {
        JsonHttpClient.Response response = http.send(
                "POST",
                "/api/v1/tasks/"
                        + segment(managedTaskId())
                        + "/results:load",
                List.of(messageId),
                "androidWorker.results.load"
        );
        requireStatus(response, 200, "Task results:load");
        Map<String, Object> results = response.body();
        if (!results.keySet().equals(Set.of(messageId))) {
            throw identityFailure(
                    "task-result.identities",
                    "Task loaded result identities do not match",
                    messageId,
                    results.keySet()
            );
        }
        Map<String, Object> result = JsonValues.object(
                results.get(messageId),
                "Task loaded result"
        );
        return CallStatus.fromWire(JsonValues.requiredString(
                result,
                "status"
        ));
    }

    void requirePropertiesRelation(
            String endpointManagerId,
            String workerId
    ) {
        requirePropertiesRelation(endpointManagerId, workerId, Map.of());
    }

    void requirePropertiesRelation(
            String endpointManagerId,
            String workerId,
            Map<String, Object> expectedProperties
    ) {
        DirectTarget worker = callWorker(
                endpointManagerId,
                workerId,
                AndroidWorkerProofConstants.WORKER_PROPERTIES_EVENT,
                "null"
        );
        worker.requireSuccessful("Worker properties snapshot");
        Map<String, Object> workerSnapshot = Jsons.parseObject(
                worker.opaqueResultPayload()
        );
        Map<String, Object> workerProperties = JsonValues.object(
                workerSnapshot.get("properties"),
                "Worker properties"
        );

        DirectTarget adapter = callAdapter(
                endpointManagerId,
                AndroidWorkerProofConstants.ADAPTER_PROPERTIES_EVENT,
                Jsons.toJson(Map.of("workerIds", List.of(workerId)))
        );
        adapter.requireSuccessful("Adapter properties snapshot");
        Map<String, Object> adapterSnapshot = Jsons.parseObject(
                adapter.opaqueResultPayload()
        );
        Map<String, Object> observations = JsonValues.object(
                adapterSnapshot.get("propertiesByWorkerId"),
                "Adapter properties observations"
        );
        if (!observations.keySet().equals(Set.of(workerId))) {
            throw identityFailure(
                    "properties.adapter-identities",
                    "Adapter properties identities do not match",
                    workerId,
                    observations.keySet()
            );
        }
        Map<String, Object> observation = JsonValues.object(
                observations.get(workerId),
                "Adapter Worker properties observation"
        );
        JsonValues.requiredLong(observation, "updatedAtMillis");
        Map<String, Object> adapterProperties = JsonValues.object(
                observation.get("properties"),
                "Adapter cached Worker properties"
        );
        if (!workerProperties.equals(adapterProperties)) {
            throw new ProofFailure(
                    "properties.relation",
                    "Worker and Adapter properties snapshots differ",
                    List.of(),
                    List.of(),
                    List.of(workerId)
            );
        }
        for (Map.Entry<String, Object> expected : Map.copyOf(
                expectedProperties
        ).entrySet()) {
            if (!Objects.equals(
                    expected.getValue(),
                    workerProperties.get(expected.getKey())
            )) {
                throw new ProofFailure(
                        "properties.expected",
                        "Worker properties do not contain the expected value",
                        List.of(),
                        List.of(),
                        List.of(workerId)
                );
            }
        }
    }

    private String observedState(
            String path,
            String workerId,
            String operation
    ) {
        JsonHttpClient.Response response = http.send(
                "POST",
                path,
                List.of(workerId),
                operation
        );
        requireStatus(response, 200, operation);
        Map<String, Object> states = JsonValues.object(
                response.body().get("statesByWorkerId"),
                "Worker states"
        );
        if (!states.keySet().stream().allMatch(workerId::equals)) {
            throw identityFailure(
                    operation + ".identities",
                    operation + " returned unexpected identities",
                    workerId,
                    states.keySet()
            );
        }
        Object state = states.get(workerId);
        if (state == null) {
            return null;
        }
        if (!(state instanceof String text) || text.isBlank()) {
            throw new ProofFailure(
                    operation + ".state",
                    operation + " returned an invalid state"
            );
        }
        return text;
    }

    private DirectCall directCall(
            String endpointManagerId,
            Map<String, Object> body
    ) {
        JsonHttpClient.Response response = http.send(
                "POST",
                "/api/v1/worker-delivery/endpoint-managers/"
                        + segment(endpointManagerId)
                        + "/direct-calls",
                body,
                "workerDelivery.directCall"
        );
        requireStatus(response, 200, "Worker Delivery Direct Call");
        String status = JsonValues.requiredString(response.body(), "status");
        Map<String, Object> rawTargets = JsonValues.object(
                response.body().get("results"),
                "Direct Call results"
        );
        Map<String, DirectTarget> targets = new LinkedHashMap<>();
        rawTargets.forEach((targetId, rawTarget) -> {
            Map<String, Object> target = JsonValues.object(
                    rawTarget,
                    "Direct Call target"
            );
            targets.put(targetId, new DirectTarget(
                    JsonValues.requiredString(target, "status"),
                    JsonValues.optionalString(
                            target.get("outcomeCode"),
                            "outcomeCode"
                    ),
                    JsonValues.optionalString(
                            target.get("opaqueResultPayload"),
                            "opaqueResultPayload"
                    )
            ));
        });
        return new DirectCall(status, Map.copyOf(targets));
    }

    private static void requireStatus(
            JsonHttpClient.Response response,
            int expected,
            String operation
    ) {
        if (response.statusCode() != expected) {
            throw new ProofFailure(
                    "runtime-api.http",
                    operation + " returned HTTP " + response.statusCode()
            );
        }
    }

    private static ProofFailure identityFailure(
            String invariant,
            String message,
            String expected,
            Set<String> actual
    ) {
        return new ProofFailure(
                invariant,
                message,
                actual.contains(expected) ? List.of() : List.of(expected),
                actual.stream().filter(value -> !expected.equals(value)).toList(),
                List.of()
        );
    }

    private static String managedTaskId() {
        return "scenario-rpc-" + AndroidWorkerProofConstants.WORKER_GROUP_ID;
    }

    private static String segment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("identifier must be non-blank");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    record DirectCall(String status, Map<String, DirectTarget> targets) {
    }

    record DirectTarget(
            String status,
            String outcomeCode,
            String opaqueResultPayload
    ) {
        void requireSuccessful(String operation) {
            if (!"observed".equals(status)
                    || !"200".equals(outcomeCode)
                    || opaqueResultPayload == null) {
                throw new ProofFailure(
                        "direct-call.outcome",
                        operation + " was not observed successfully"
                );
            }
        }
    }

    record TaskCall(String messageId, CallStatus status) {
    }

    record TaskItemCall(
            String eventName,
            Map<String, Object> payload,
            Map<String, Object> allocationRule
    ) {
        TaskItemCall {
            if (eventName == null || eventName.isBlank()) {
                throw new IllegalArgumentException("eventName must be non-blank");
            }
            payload = Map.copyOf(Objects.requireNonNull(payload, "payload"));
            allocationRule = Map.copyOf(Objects.requireNonNull(
                    allocationRule,
                    "allocationRule"
            ));
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
                default -> throw new ProofFailure(
                        "task-call.status",
                        "Task call status is invalid"
                );
            };
        }
    }
}
