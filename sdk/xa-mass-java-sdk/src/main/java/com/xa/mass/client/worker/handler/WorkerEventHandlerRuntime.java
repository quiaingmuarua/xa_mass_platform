package com.xa.mass.client.worker.handler;

import java.util.Map;
import java.util.Objects;

public final class WorkerEventHandlerRuntime {
    private final WorkerEventHandlers handlers;

    public WorkerEventHandlerRuntime(WorkerEventHandlers handlers) {
        this.handlers = handlers == null ? WorkerEventHandlers.empty() : handlers;
    }

    public WorkerEventInvocation invoke(WorkerInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation is required");
        try {
            WorkerEventHandler handler = handlers.find(invocation.eventCode()).orElse(null);
            if (handler == null) {
                return new WorkerEventInvocation(invocation, WorkerResult.failure("NO_HANDLER",
                        "No handler registered for eventCode " + invocation.eventCode()), null);
            }
            WorkerResult result = handler.handle(invocation);
            if (result == null) {
                return new WorkerEventInvocation(invocation, WorkerResult.failure("HANDLER_NULL_RESULT",
                        "Handler returned null result"), null);
            }
            return new WorkerEventInvocation(invocation, result, null);
        } catch (Throwable failure) {
            return new WorkerEventInvocation(invocation, WorkerResult.failure("HANDLER_ERROR",
                    failure.getMessage(), Map.of("exception", failure.getClass().getName())), failure);
        }
    }
}
