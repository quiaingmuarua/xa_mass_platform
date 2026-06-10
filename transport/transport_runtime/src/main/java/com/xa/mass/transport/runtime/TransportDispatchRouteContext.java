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
                                            String workerGroupId,
                                            String batchId) {

    public static TransportDispatchRouteContext from(TaskDispatchContext task,
                                                     TaskDispatchBinding dispatchBinding) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(dispatchBinding, "dispatchBinding");
        return new TransportDispatchRouteContext(
                task.taskId(),
                dispatchBinding.messageId(),
                task.eventCode(),
                dispatchBinding.attemptId(),
                dispatchBinding.workerId(),
                dispatchBinding.workerGroupId(),
                dispatchBinding.batchId()
        );
    }
}
