package com.xa.mass.client.worker.handler;

public record WorkerEventInvocation(
        DispatchContext dispatch,
        WorkerResult result,
        Throwable failure
) {
    public boolean handlerFailed() {
        return failure != null;
    }
}
