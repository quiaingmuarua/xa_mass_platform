package com.xa.mass.base.runtime.dispatch;

import java.util.List;
import java.util.Objects;

/**
 * Immutable batch submitted through the engine -> transport dispatch handoff.
 */
public record TaskDispatchBatch(TaskDispatchContext task,
                                List<TaskDispatchBinding> dispatchBindings) {

    public TaskDispatchBatch {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(dispatchBindings, "dispatchBindings");
        dispatchBindings = List.copyOf(dispatchBindings);
        if (dispatchBindings.isEmpty()) {
            throw new IllegalArgumentException("dispatchBindings must not be empty");
        }
    }
}
