package com.xa.mass.worker.javase;

import com.xa.mass.worker.runtime.WorkerPropertiesProvider;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;

import java.util.Collections;
import java.util.LinkedHashMap;
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
        return () -> WorkerDeliveryCodec.copyWorkerProperties(supplied.loadProperties());
    }

    private static Map<String, String> complete(
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
        Map<String, String> complete = new LinkedHashMap<>();
        complete.put(CLIENT_WORKER_KEY, clientWorkerKey);
        complete.putAll(WorkerDeliveryCodec.copyWorkerProperties(supplied));
        return Collections.unmodifiableMap(complete);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }
}
