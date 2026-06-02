package com.xa.mass.sdk;

import com.xa.mass.engine.TaskQueryService;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.engine.model.TaskStateValidationResult;

import java.util.Objects;
import java.util.function.Supplier;

final class DefaultTaskDiagnosticOperations implements TaskDiagnosticOperations {

    private final Supplier<TaskQueryService> taskQueriesSupplier;

    DefaultTaskDiagnosticOperations(Supplier<TaskQueryService> taskQueriesSupplier) {
        this.taskQueriesSupplier = Objects.requireNonNull(taskQueriesSupplier, "taskQueriesSupplier");
    }

    @Override
    public TaskStateValidationResult validateTaskState(String taskId) {
        return taskQueriesSupplier.get().validateTaskState(taskId);
    }

    @Override
    public TaskStateResolutionResult resolveTaskState(String taskId) {
        return taskQueriesSupplier.get().resolveTaskState(taskId);
    }
}
