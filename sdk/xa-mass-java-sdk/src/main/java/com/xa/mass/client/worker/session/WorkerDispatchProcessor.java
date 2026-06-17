package com.xa.mass.client.worker.session;

import com.xa.mass.client.worker.WorkerInvocation;
import com.xa.mass.client.worker.handler.WorkerEventHandler;
import com.xa.mass.client.worker.handler.WorkerResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class WorkerDispatchProcessor {
    private final String workerId;
    private final Map<String, WorkerEventHandler> handlers;
    private final WorkerSessionListener listener;

    WorkerDispatchProcessor(String workerId, Map<String, WorkerEventHandler> handlers, WorkerSessionListener listener) {
        this.workerId = requireText(workerId, "workerId");
        this.handlers = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNullElse(handlers, Map.of())));
        this.listener = Objects.requireNonNull(listener, "listener is required");
    }

    ProcessedDispatch process(WorkerInvocation workerInvocation) {
        Objects.requireNonNull(workerInvocation, "workerInvocation is required");
        String resultCorrelationRef = workerInvocation.resultCorrelationRef();
        WorkerResult result;
        try {
            WorkerEventHandler handler = handlers.get(workerInvocation.eventCode());
            if (handler == null) {
                result = WorkerResult.failure("NO_HANDLER",
                        "No handler registered for eventCode " + workerInvocation.eventCode());
            } else {
                result = handler.handle(workerInvocation);
                if (result == null) {
                    result = WorkerResult.failure("HANDLER_NULL_RESULT", "Handler returned null result");
                }
            }
        } catch (Throwable failure) {
            result = WorkerResult.failure("HANDLER_ERROR",
                    failure.getClass().getName() + ": " + failure.getMessage());
            listener.onHandlerFailure(new WorkerSessionDispatchFailure(
                    workerId,
                    resultCorrelationRef,
                    workerInvocation,
                    failure));
        }
        return new ProcessedDispatch(resultCorrelationRef, workerInvocation, result);
    }

    record ProcessedDispatch(String resultCorrelationRef,
                             WorkerInvocation invocation,
                             WorkerResult result) {
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }
}
