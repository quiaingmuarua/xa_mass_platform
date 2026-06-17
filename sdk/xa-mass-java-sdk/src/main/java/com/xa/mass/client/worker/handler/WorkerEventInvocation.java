package com.xa.mass.client.worker.handler;

public record WorkerEventInvocation(
        WorkerInvocation invocation,
        WorkerResult result,
        Throwable failure
) {
    public boolean handlerFailed() {
        return failure != null;
    }
}
