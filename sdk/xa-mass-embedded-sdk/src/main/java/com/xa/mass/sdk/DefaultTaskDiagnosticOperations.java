package com.xa.mass.sdk;

import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.starter.MassApplication;

import java.util.Objects;

final class DefaultTaskDiagnosticOperations implements TaskDiagnosticOperations {

    private final MassApplication delegate;

    DefaultTaskDiagnosticOperations(MassApplication delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public TaskStateValidationResult validateTaskState(String taskId) {
        return delegate.validateTaskState(taskId);
    }

    @Override
    public TaskStateResolutionResult resolveTaskState(String taskId) {
        return delegate.resolveTaskState(taskId);
    }
}
