package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;

import java.util.List;
import java.util.Objects;

/**
 * Process-boundary event for retryable transport dispatch submission failures.
 */
public record TransportDispatchFailureEvent(TaskDispatchContext task,
                                            List<TaskDispatchBinding> dispatchBindings,
                                            String detail) {

    public TransportDispatchFailureEvent {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(dispatchBindings, "dispatchBindings");
        dispatchBindings = List.copyOf(dispatchBindings);
        if (dispatchBindings.isEmpty()) {
            throw new IllegalArgumentException("dispatchBindings must not be empty");
        }
    }
}
