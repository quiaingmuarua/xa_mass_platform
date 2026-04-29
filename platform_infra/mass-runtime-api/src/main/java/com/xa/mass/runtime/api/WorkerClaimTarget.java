package com.xa.mass.runtime.api;

public record WorkerClaimTarget(String workerId, String workerContextId, String batchId, int capacity) {
    public WorkerClaimTarget {
        capacity = Math.max(0, capacity);
    }
}

