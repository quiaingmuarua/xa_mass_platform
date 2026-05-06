package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;

import java.util.Objects;

/**
 * Narrow transport-owned dispatch route context used only for adapter-local
 * route-key resolution.
 */
public record TransportDispatchRouteContext(String taskId,
                                            String messageId,
                                            String eventCode,
                                            String attemptId,
                                            String workerId,
                                            String workerContextId,
                                            String batchId) {

    public static TransportDispatchRouteContext from(TaskDispatchContext task,
                                                     TaskDispatchBinding dispatchBinding) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(dispatchBinding, "dispatchBinding");
        return new TransportDispatchRouteContext(
                task.taskId(),
                dispatchBinding.taskMsg() != null ? dispatchBinding.taskMsg().getMessageId() : null,
                task.eventCode(),
                dispatchBinding.attempt() != null ? dispatchBinding.attempt().getAttemptId() : null,
                dispatchBinding.attempt() != null ? dispatchBinding.attempt().getWorkerId() : null,
                dispatchBinding.attempt() != null ? dispatchBinding.attempt().getWorkerContextId() : null,
                dispatchBinding.attempt() != null ? dispatchBinding.attempt().getBatchId() : null
        );
    }
}
