package com.xa.mass.client.worker.session;

import com.xa.mass.client.worker.ResultCorrelationRef;

public record WorkerSessionQueuedResultFailure(
        String workerId,
        ResultCorrelationRef resultCorrelationRef,
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
