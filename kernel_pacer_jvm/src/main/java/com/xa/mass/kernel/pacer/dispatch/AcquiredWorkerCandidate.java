package com.xa.mass.kernel.pacer.dispatch;

/** A Worker that passed exact lease acquisition and canonical rematching. */
record AcquiredWorkerCandidate(
        String workerId,
        String workerGroupId,
        String endpointManagerId,
        long workerLeaseScore
) {
    AcquiredWorkerCandidate {
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
