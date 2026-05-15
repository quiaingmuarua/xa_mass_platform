package com.xa.mass.engine.assignment;

import com.xa.mass.base.model.Task;

import java.util.function.BooleanSupplier;

public record AssignmentRefillRequest(
        Task task,
        BooleanSupplier dispatchReadyWork
) {
    public AssignmentRefillRequest {
        if (dispatchReadyWork == null) {
            dispatchReadyWork = () -> false;
        }
    }

    public boolean hasDispatchReadyWork() {
        return dispatchReadyWork.getAsBoolean();
    }
}
