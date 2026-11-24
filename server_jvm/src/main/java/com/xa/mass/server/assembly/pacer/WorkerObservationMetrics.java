package com.xa.mass.server.assembly.pacer;

/** Read-only metrics view of the bounded Worker observation consumer. */
public interface WorkerObservationMetrics {
    long enqueuedBatches();
    long droppedBatches();
    long failedBatches();
    int queueSize();
}
