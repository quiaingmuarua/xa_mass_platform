package com.xa.mass.scenarioworkers;

import com.xa.mass.transport.client.TextMessageReconnectPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

record ScenarioWorkerGroupConfig(
        String workerGroupId,
        List<String> eventCodes,
        Duration requestTimeout,
        TextMessageReconnectPolicy reconnectPolicy,
        Duration connectTimeout
) {

    ScenarioWorkerGroupConfig {
        requireNonBlank(workerGroupId, "workerGroupId");
        Objects.requireNonNull(eventCodes, "eventCodes");
        if (eventCodes.isEmpty()) {
            throw new IllegalArgumentException(
                    "eventCodes must contain at least one eventCode"
            );
        }
        List<String> copiedEventCodes = new ArrayList<>(eventCodes.size());
        LinkedHashSet<String> uniqueEventCodes = new LinkedHashSet<>();
        for (String eventCode : eventCodes) {
            requireNonBlank(eventCode, "eventCode");
            if (!uniqueEventCodes.add(eventCode)) {
                throw new IllegalArgumentException(
                        "eventCodes must not contain duplicates: "
                                + eventCode
                );
            }
            copiedEventCodes.add(eventCode);
        }
        eventCodes = List.copyOf(copiedEventCodes);
        requirePositive(requestTimeout, "requestTimeout");
        Objects.requireNonNull(reconnectPolicy, "reconnectPolicy");
        requirePositive(connectTimeout, "connectTimeout");
    }

    static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    name + " must be non-blank"
            );
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    name + " must be positive"
            );
        }
    }
}
