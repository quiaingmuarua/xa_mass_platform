package com.xa.mass.engine.work;

public record WorkerClaimTarget(String workerId, String workerContextId, String batchId, int capacity) {
    public WorkerClaimTarget {
        capacity = Math.max(0, capacity);
    }
}
