package com.xa.mass.sdk;

import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.sdk.model.TaskActiveLeaseSnapshot;
import com.xa.mass.sdk.model.TaskWorkStatsSnapshot;
import com.xa.mass.starter.MassApplication;

import java.util.List;
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

    @Override
    public TaskWorkStatsSnapshot getTaskWorkStats(String taskId) {
        TaskWorkStatsSnapshot snapshot = delegate.getTaskWorkStats(taskId);
        return snapshot == null ? TaskWorkStatsSnapshot.EMPTY : snapshot;
    }

    @Override
    public List<TaskActiveLeaseSnapshot> getActiveLeases(String taskId) {
        return List.copyOf(delegate.getActiveLeases(taskId));
    }

}
