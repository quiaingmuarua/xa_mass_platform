package com.xa.mass.integration.androidworkerproof;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

final class AndroidDeviceHostClient {

    private final JsonHttpClient http;

    AndroidDeviceHostClient(JsonHttpClient http) {
        this.http = java.util.Objects.requireNonNull(http, "http");
    }

    void requireHealth() {
        JsonHttpClient.Response response = http.send(
                "GET",
                "/health",
                null,
                "androidDevice.health"
        );
        requireStatus(response, 200, "Android device health");
        if (!"ok".equals(response.body().get("status"))) {
            throw new ProofFailure(
                    "device.health",
                    "Android device HTTP health is not ok"
            );
        }
    }

    Set<String> events() {
        JsonHttpClient.Response response = http.send(
                "GET",
                "/events",
                null,
                "androidDevice.events"
        );
        requireStatus(response, 200, "Android device events");
        Set<String> events = new LinkedHashSet<>();
        for (Object raw : JsonValues.array(
                response.body().get("events"),
                "events"
        )) {
            if (!(raw instanceof String eventName)
                    || eventName.isBlank()
                    || !events.add(eventName)) {
                throw new ProofFailure(
                        "device.events.contract",
                        "Android device events are invalid or duplicated"
                );
            }
        }
        return Set.copyOf(events);
    }

    Snapshot snapshot() {
        Map<String, Object> result = call(
                AndroidWorkerProofConstants.HOST_SNAPSHOT_EVENT,
                Map.of()
        );
        String state = JsonValues.requiredString(result, "state");
        if (!Set.of("RUNNING", "STOPPED").contains(state)) {
            throw new ProofFailure(
                    "device.snapshot.state",
                    "Android Worker local state is invalid"
            );
        }
        long processedCommands = JsonValues.requiredLong(
                result,
                "processedCommands"
        );
        long activeDelayCount = JsonValues.requiredLong(
                result,
                "activeDelayCount"
        );
        if (processedCommands < 0L || activeDelayCount < 0L) {
            throw new ProofFailure(
                    "device.snapshot.counts",
                    "Android Worker local counters are invalid"
            );
        }
        return new Snapshot(
                state,
                JsonValues.optionalString(result.get("workerId"), "workerId"),
                JsonValues.optionalString(
                        result.get("endpointUri"),
                        "endpointUri"
                ),
                JsonValues.optionalString(
                        result.get("diagnosticMessage"),
                        "diagnosticMessage"
                ),
                processedCommands,
                JsonValues.optionalString(result.get("lastEvent"), "lastEvent"),
                activeDelayCount
        );
    }

    void start() {
        requestState(
                AndroidWorkerProofConstants.HOST_START_EVENT,
                "RUNNING"
        );
    }

    void stop() {
        requestState(
                AndroidWorkerProofConstants.HOST_STOP_EVENT,
                "STOPPED"
        );
    }

    private void requestState(String eventName, String state) {
        Map<String, Object> result = call(eventName, Map.of());
        if (!Boolean.TRUE.equals(result.get("accepted"))
                || !state.equals(result.get("requestedState"))) {
            throw new ProofFailure(
                    "device.lifecycle.accepted",
                    "Android Worker lifecycle request was not accepted"
            );
        }
    }

    private Map<String, Object> call(
            String eventName,
            Map<String, Object> payload
    ) {
        JsonHttpClient.Response response = http.send(
                "POST",
                "/events/" + segment(eventName) + ":call",
                payload,
                "androidDevice.event[" + eventName + "]"
        );
        requireStatus(response, 200, "Android device event " + eventName);
        if (!"succeeded".equals(response.body().get("status"))
                || !eventName.equals(response.body().get("eventCode"))
                || !"200".equals(response.body().get("outcomeCode"))
                || !response.body().containsKey("result")) {
            throw new ProofFailure(
                    "device.event.outcome",
                    "Android device event did not succeed"
            );
        }
        return JsonValues.object(
                response.body().get("result"),
                "Android device event result"
        );
    }

    private static void requireStatus(
            JsonHttpClient.Response response,
            int expected,
            String operation
    ) {
        if (response.statusCode() != expected) {
            throw new ProofFailure(
                    "device.http",
                    operation + " returned HTTP " + response.statusCode()
            );
        }
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    record Snapshot(
            String state,
            String workerId,
            String endpointUri,
            String diagnosticMessage,
            long processedCommands,
            String lastEvent,
            long activeDelayCount
    ) {
    }
}
