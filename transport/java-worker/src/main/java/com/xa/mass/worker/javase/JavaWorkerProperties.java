package com.xa.mass.worker.javase;

import com.xa.mass.worker.runtime.WorkerPropertiesProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared Java Worker complete-properties assembly. */
final class JavaWorkerProperties {

    private static final String CLIENT_WORKER_KEY = "clientWorkerKey";

    private JavaWorkerProperties() {
    }

    static WorkerPropertiesProvider completeProvider(
            String clientWorkerKey,
            WorkerPropertiesProvider suppliedProperties
    ) {
        String key = requireNonBlank(clientWorkerKey, CLIENT_WORKER_KEY);
        WorkerPropertiesProvider supplied = Objects.requireNonNull(
                suppliedProperties,
                "workerProperties"
        );
        return () -> complete(key, supplied.loadProperties());
    }

    static WorkerPropertiesProvider snapshotProvider(
            WorkerPropertiesProvider suppliedProperties
    ) {
        WorkerPropertiesProvider supplied = Objects.requireNonNull(
                suppliedProperties,
                "workerProperties"
        );
        return () -> immutableJsonMap(supplied.loadProperties());
    }

    private static Map<String, Object> complete(
            String clientWorkerKey,
            Map<?, ?> supplied
    ) {
        if (supplied == null) {
            throw new IllegalArgumentException(
                    "workerProperties must be present"
            );
        }
        if (supplied.containsKey(CLIENT_WORKER_KEY)) {
            throw new IllegalArgumentException(
                    "workerProperties must not override "
                            + CLIENT_WORKER_KEY
            );
        }
        Map<String, Object> complete = new LinkedHashMap<>();
        complete.put(CLIENT_WORKER_KEY, clientWorkerKey);
        for (Map.Entry<?, ?> entry : supplied.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException(
                        "workerProperties keys must be strings"
                );
            }
            complete.put(
                    (String) entry.getKey(),
                    immutableValue(entry.getValue())
            );
        }
        return Collections.unmodifiableMap(complete);
    }

    private static Map<String, Object> immutableJsonMap(Map<?, ?> supplied) {
        if (supplied == null) {
            throw new IllegalArgumentException(
                    "workerProperties must be present"
            );
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : supplied.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException(
                        "workerProperties keys must be strings"
                );
            }
            result.put(
                    (String) entry.getKey(),
                    immutableValue(entry.getValue())
            );
        }
        return Collections.unmodifiableMap(result);
    }

    private static Object immutableValue(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Number) {
            return value;
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException(
                            "workerProperties keys must be strings"
                    );
                }
                result.put(
                        (String) entry.getKey(),
                        immutableValue(entry.getValue())
                );
            }
            return Collections.unmodifiableMap(result);
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

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }
}
