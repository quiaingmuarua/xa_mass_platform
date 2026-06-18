package com.xa.mass.client.worker.runtime;

public record WorkerRuntimeQueuedResultFailure(
        String workerId,
        String resultCorrelationRef,
        Reason reason,
        Throwable cause
) {
    public enum Reason {
        QUEUE_FULL,
        SESSION_CLOSED,
        RECONNECT_EXHAUSTED,
        REQUEUE_FAILED
    }
}
