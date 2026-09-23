package com.xa.mass.kernel.pacer.dispatch;

/** A routed Worker identity carrying an optional expected Kernel fence, not execution authority. */
record RoutedWorkerCandidate(
        String workerId,
        String workerGroupId,
        String endpointManagerId,
        long expectedScore
) {
    RoutedWorkerCandidate {
        requireNonBlank(workerId, "workerId");
        requireNonBlank(workerGroupId, "workerGroupId");
        requireNonBlank(endpointManagerId, "endpointManagerId");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }
}
