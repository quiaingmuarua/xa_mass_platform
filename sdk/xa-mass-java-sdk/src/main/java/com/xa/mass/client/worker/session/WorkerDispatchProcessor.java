package com.xa.mass.client.worker.session;

import com.xa.mass.client.worker.WorkerDispatchItem;
import com.xa.mass.client.worker.handler.DispatchContext;
import com.xa.mass.client.worker.handler.WorkerEventHandlerRuntime;
import com.xa.mass.client.worker.handler.WorkerEventInvocation;
import com.xa.mass.client.worker.handler.WorkerResult;

import java.util.Objects;

final class WorkerDispatchProcessor {
    private final String workerId;
    private final WorkerEventHandlerRuntime handlerRuntime;
    private final WorkerSessionListener listener;

    WorkerDispatchProcessor(String workerId, WorkerEventHandlerRuntime handlerRuntime, WorkerSessionListener listener) {
        this.workerId = requireText(workerId, "workerId");
        this.handlerRuntime = Objects.requireNonNull(handlerRuntime, "handlerRuntime is required");
        this.listener = Objects.requireNonNull(listener, "listener is required");
    }

    ProcessedDispatch process(WorkerDispatchItem item) {
        DispatchContext dispatch = DispatchContext.from(item, workerId);
        WorkerEventInvocation invocation = handlerRuntime.invoke(dispatch);
        if (invocation.handlerFailed()) {
            listener.onHandlerFailure(new WorkerSessionDispatchFailure(dispatch, invocation.failure()));
        }
        return new ProcessedDispatch(dispatch, invocation.result());
    }

    record ProcessedDispatch(DispatchContext dispatch, WorkerResult result) {
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }
}
