package com.xa.mass.client.worker.session;

public record WorkerSessionQueuedResultFailure(
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
