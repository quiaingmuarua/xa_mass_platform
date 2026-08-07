package com.xa.mass.worker.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class WorkerPropertiesSnapshot {

    static final String CLIENT_WORKER_KEY = "clientWorkerKey";

    private final Map<String, Object> properties;

    private WorkerPropertiesSnapshot(Map<String, Object> properties) {
        this.properties = properties;
    }

    static WorkerPropertiesSnapshot from(Map<String, Object> source) {
        if (source == null) {
            throw new IllegalArgumentException(
                    "workerProperties must be present"
            );
        }
        Map<String, Object> properties = immutableObject(source);
        Object rawClientKey = properties.get(CLIENT_WORKER_KEY);
        if (!(rawClientKey instanceof String)
                || ((String) rawClientKey).trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "workerProperties.clientWorkerKey must be a "
                            + "non-blank string"
            );
        }
        return new WorkerPropertiesSnapshot(properties);
    }

    Map<String, Object> properties() {
        return properties;
    }

    private static Map<String, Object> immutableObject(Map<?, ?> source) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException(
                        "workerProperties keys must be strings"
                );
            }
            sorted.put(
                    (String) entry.getKey(),
                    immutableValue(entry.getValue())
            );
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static Object immutableValue(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Number) {
            return value;
        }
        if (value instanceof Map<?, ?>) {
            return immutableObject((Map<?, ?>) value);
        }
        if (value instanceof List<?>) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>) value) {
                result.add(immutableValue(item));
            }
            return Collections.unmodifiableList(result);
        }
        throw new IllegalArgumentException(
                "workerProperties contain a non-JSON value"
        );
    }
}
