package com.xa.mass.client.worker.runtime;

import com.xa.mass.client.worker.WorkerAction;
import com.xa.mass.client.worker.handler.WorkerActionHandler;
import com.xa.mass.client.worker.handler.WorkerActionResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class WorkerDispatchProcessor {
    private final String workerId;
    private final Map<String, WorkerActionHandler> handlers;
    private final WorkerRuntimeListener listener;

    WorkerDispatchProcessor(String workerId, Map<String, WorkerActionHandler> handlers, WorkerRuntimeListener listener) {
        this.workerId = requireText(workerId, "workerId");
        this.handlers = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNullElse(handlers, Map.of())));
        this.listener = Objects.requireNonNull(listener, "listener is required");
    }

    ProcessedDispatch process(WorkerAction action) {
        Objects.requireNonNull(action, "action is required");
        String replyRef = action.replyRef();
        WorkerActionResult result;
        try {
            WorkerActionHandler handler = handlers.get(action.eventCode());
            if (handler == null) {
                result = WorkerActionResult.failure("NO_HANDLER",
                        "No handler registered for eventCode " + action.eventCode());
            } else {
                result = handler.handle(action);
                if (result == null) {
                    result = WorkerActionResult.failure("HANDLER_NULL_RESULT", "Handler returned null result");
                }
            }
        } catch (Throwable failure) {
            result = WorkerActionResult.failure("HANDLER_ERROR",
                    failure.getClass().getName() + ": " + failure.getMessage());
            listener.onFailure(WorkerRuntimeFailureEvent.handler(
                    workerId,
                    replyRef,
                    action,
                    failure));
        }
        return new ProcessedDispatch(replyRef, action, result);
    }

    record ProcessedDispatch(String replyRef,
                             WorkerAction action,
                             WorkerActionResult result) {
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }
}
