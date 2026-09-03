package com.xa.mass.kernel.pacer.dispatch;

/** A routed Worker candidate carrying Kernel's exact held score. */
record HeldWorkerCandidate(
        String workerId,
        String workerGroupId,
        String endpointManagerId,
        long heldWorkerLeaseScore
) {
    HeldWorkerCandidate {
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
