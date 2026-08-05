package com.xa.mass.integration.workercapability;

import java.util.Objects;

record WorkerTarget(
        String clientWorkerKey,
        String workerId
) {
    WorkerTarget {
        requireNonBlank(clientWorkerKey, "clientWorkerKey");
        requireNonBlank(workerId, "workerId");
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
