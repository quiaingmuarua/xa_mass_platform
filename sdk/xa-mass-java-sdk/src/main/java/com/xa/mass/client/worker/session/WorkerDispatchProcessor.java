package com.xa.mass.client.worker.session;

import com.xa.mass.client.payload.MassPayload;
import com.xa.mass.client.worker.WorkerDispatchItem;
import com.xa.mass.client.worker.ResultCorrelationRef;
import com.xa.mass.client.worker.handler.WorkerEventHandlerRuntime;
import com.xa.mass.client.worker.handler.WorkerEventInvocation;
import com.xa.mass.client.worker.handler.WorkerInvocation;
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
        WorkerInvocation workerInvocation = new WorkerInvocation(
                item.eventCode(),
                MassPayload.of(item.input()),
                MassPayload.of(item.sharedConfig())
        );
        ResultCorrelationRef resultCorrelationRef = ResultCorrelationRef.of(item.resultCorrelationRef());
        WorkerEventInvocation invocation = handlerRuntime.invoke(workerInvocation);
        if (invocation.handlerFailed()) {
            listener.onHandlerFailure(new WorkerSessionDispatchFailure(
                    workerId,
                    resultCorrelationRef,
                    workerInvocation,
                    invocation.failure()));
        }
        return new ProcessedDispatch(resultCorrelationRef, workerInvocation, invocation.result());
    }

    record ProcessedDispatch(ResultCorrelationRef resultCorrelationRef,
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
