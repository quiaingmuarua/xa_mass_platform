package com.xa.mass.scenarioworkers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

record ScenarioWorkerGroupConfig(
        String workerGroupId,
        List<String> eventCodes,
        List<ScenarioWorkerConfig> workers,
        Duration requestTimeout,
        Duration reconnectInterval,
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
        Objects.requireNonNull(workers, "workers");
        if (workers.isEmpty() || workers.size() > 100) {
            throw new IllegalArgumentException(
                    "workers must contain between 1 and 100 entries"
            );
        }
        workers = List.copyOf(new ArrayList<>(workers));
        requirePositive(requestTimeout, "requestTimeout");
        requirePositive(reconnectInterval, "reconnectInterval");
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

    private static Map<String, Object> immutableJsonMap(
            Map<String, Object> value,
            String name
    ) {
        Objects.requireNonNull(value, name);
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(value)
        );
    }
}

record ScenarioWorkerConfig(
        String clientWorkerKey,
        Map<String, Object> workerProperties,
        Map<String, Object> indexedPropertyUpdates
) {

    ScenarioWorkerConfig {
        ScenarioWorkerGroupConfig.requireNonBlank(
                clientWorkerKey,
                "clientWorkerKey"
        );
        workerProperties = immutableJsonMap(
                workerProperties,
                "workerProperties"
        );
        indexedPropertyUpdates = immutableJsonMap(
                indexedPropertyUpdates,
                "indexedPropertyUpdates"
        );
        indexedPropertyUpdates.keySet().forEach(field -> {
            if (!field.startsWith("index.")
                    || field.length() == "index.".length()) {
                throw new IllegalArgumentException(
                        "indexedPropertyUpdates fields must use index.*"
                );
            }
        });
    }

    private static Map<String, Object> immutableJsonMap(
            Map<String, Object> value,
            String name
    ) {
        Objects.requireNonNull(value, name);
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(value)
        );
    }
}
