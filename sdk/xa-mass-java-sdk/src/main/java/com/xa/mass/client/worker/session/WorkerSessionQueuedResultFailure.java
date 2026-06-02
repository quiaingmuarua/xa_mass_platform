package com.xa.mass.client.worker.session;

import com.xa.mass.client.worker.handler.DispatchContext;

public record WorkerSessionQueuedResultFailure(
        String workerId,
        DispatchContext dispatch,
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
