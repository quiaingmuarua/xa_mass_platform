package com.xa.mass.client.worker.handler;

import java.util.Map;
import java.util.Objects;

public final class WorkerEventHandlerRuntime {
    private final WorkerEventHandlers handlers;

    public WorkerEventHandlerRuntime(WorkerEventHandlers handlers) {
        this.handlers = handlers == null ? WorkerEventHandlers.empty() : handlers;
    }

    public WorkerEventInvocation invoke(DispatchContext dispatch) {
        Objects.requireNonNull(dispatch, "dispatch is required");
        try {
            WorkerEventHandler handler = handlers.find(dispatch.eventCode()).orElse(null);
            if (handler == null) {
                return new WorkerEventInvocation(dispatch, WorkerResult.failure("NO_HANDLER",
                        "No handler registered for eventCode " + dispatch.eventCode()), null);
            }
            WorkerResult result = handler.handle(dispatch);
            if (result == null) {
                return new WorkerEventInvocation(dispatch, WorkerResult.failure("HANDLER_NULL_RESULT",
                        "Handler returned null result"), null);
            }
            return new WorkerEventInvocation(dispatch, result, null);
        } catch (Throwable failure) {
            return new WorkerEventInvocation(dispatch, WorkerResult.failure("HANDLER_ERROR",
                    failure.getMessage(), Map.of("exception", failure.getClass().getName())), failure);
        }
    }
}
