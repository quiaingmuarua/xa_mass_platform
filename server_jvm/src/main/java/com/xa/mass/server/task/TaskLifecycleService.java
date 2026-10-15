package com.xa.mass.server.task;

import com.xa.mass.server.task.access.TaskCaller;

import com.xa.mass.kernel.task.TaskLifecycleCommands;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.operation.OperationGuard;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class TaskLifecycleService {

    private final TaskLifecycleCommands lifecycle;
    private final TaskResourceCatalog catalog;
    private final OperationGuard operations;
    private final com.xa.mass.kernel.score.TaskItemScoreBandCore itemScores;
    private final com.xa.mass.kernel.score.TaskScoreBandCore taskScores;
    private final com.xa.mass.kernel.task.TaskRuntime runtime;

    public TaskLifecycleService(
            TaskLifecycleCommands lifecycle,
            TaskResourceCatalog catalog,
            OperationGuard operations,
            com.xa.mass.kernel.score.TaskItemScoreBandCore itemScores,
            com.xa.mass.kernel.score.TaskScoreBandCore taskScores,
            com.xa.mass.kernel.task.TaskRuntime runtime
    ) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.itemScores = Objects.requireNonNull(itemScores, "itemScores");
        this.taskScores = Objects.requireNonNull(taskScores, "taskScores");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public ActionOutcome approve(TaskCaller caller, String taskId, long expectedItemCount) {
        if (expectedItemCount <= 0) throw failure(ServerErrorCode.INVALID_TASK_DATA_REQUEST, "taskLifecycle.approve");
        return operations.taskMutation(taskId, () -> approveObserved(caller, taskId, expectedItemCount));
    }

    private ActionOutcome approveObserved(TaskCaller caller, String taskId, long expectedItemCount) {
        String operation = "taskLifecycle.approve";
        requireFiniteTask(caller, taskId, operation);
        TaskLifecycleCommands.TaskApprovalResult result;
        try {
            var state = taskScores.getScoreStates(List.of(taskId)).get(taskId);
            if (state == null) throw unavailable(operation, null);
            if (state.band() == com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand.PRE_REVIEW) {
                var count = itemScores.observeItemScoreCounts(List.of(taskId)).get(taskId);
                if (count == null) throw unavailable(operation, null);
                if (count.total() != expectedItemCount) throw failure(ServerErrorCode.TASK_STATE_CONFLICT, operation);
            }
            result = lifecycle.approveTask(taskId);
        } catch (ServerException known) {
            throw known;
        } catch (RuntimeException error) {
            throw unavailable(operation, error);
        }
        if (result == null) {
            throw unavailable(operation, null);
        }
        return switch (result.status()) {
            case APPROVED -> ActionOutcome.applied();
            case ALREADY_APPROVED -> ActionOutcome.unchanged();
            case NOT_FOUND -> throw failure(
                    ServerErrorCode.TASK_NOT_FOUND,
                    "taskLifecycle.approve"
            );
            case CONFLICT, INVALID -> throw failure(
                    ServerErrorCode.TASK_STATE_CONFLICT,
                    "taskLifecycle.approve"
            );
            case RETRYABLE -> throw failure(
                    ServerErrorCode.TASK_DATA_UNAVAILABLE,
                    "taskLifecycle.approve"
            );
        };
    }

    public ActionOutcome close(TaskCaller caller, String taskId) {
        return operations.taskMutation(taskId, () -> closeObserved(caller, taskId));
    }

    private ActionOutcome closeObserved(TaskCaller caller, String taskId) {
        String operation = "taskLifecycle.close";
        requireFiniteTask(caller, taskId, operation);
        TaskLifecycleCommands.TaskCloseResult result;
        try {
            result = lifecycle.closeTask(taskId);
        } catch (ServerException known) {
            throw known;
        } catch (RuntimeException error) {
            throw unavailable(operation, error);
        }
        if (result == null) {
            throw unavailable(operation, null);
        }
        return switch (result.status()) {
            case CLOSED -> ActionOutcome.applied();
            case ALREADY_CLOSED -> ActionOutcome.unchanged();
            case NOT_FOUND -> throw failure(
                    ServerErrorCode.TASK_NOT_FOUND,
                    "taskLifecycle.close"
            );
            case INVALID -> throw failure(
                    ServerErrorCode.TASK_STATE_CONFLICT,
                    "taskLifecycle.close"
            );
            case RETRYABLE -> throw failure(
                    ServerErrorCode.TASK_DATA_UNAVAILABLE,
                    "taskLifecycle.close"
            );
        };
    }

    private void requireFiniteTask(TaskCaller caller, String taskId, String operation) {
        TaskDescriptor descriptor;
        try {
            descriptor = catalog.loadTaskAllocationDescriptors(
                    List.of(taskId)
            ).get(taskId);
        } catch (RuntimeException error) {
            throw new ServerException(
                    ServerErrorCode.TASK_DATA_UNAVAILABLE,
                    operation,
                    null,
                    error
            );
        }
        if (descriptor == null) {
            throw failure(ServerErrorCode.TASK_NOT_FOUND, operation);
        }
        caller.requireTask(descriptor);
        var data = runtime.loadTaskDataStates(List.of(taskId)).get(taskId);
        if (data == null) throw failure(ServerErrorCode.TASK_NOT_FOUND, operation);
        if (data.phase() == com.xa.mass.kernel.task.TaskRuntime.TaskDataPhase.PURGING)
            throw failure(ServerErrorCode.TASK_CLEANUP_IN_PROGRESS, operation);
        if (descriptor.idleDisposition()
                != TaskIdleDisposition.CLOSE_WHEN_IDLE) {
            throw failure(
                    ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED,
                    operation
            );
        }
    }

    private static ServerException failure(
            ServerErrorCode code,
            String operation
    ) {
        return new ServerException(code, operation, null, null);
    }

    private static ServerException unavailable(
            String operation,
            Throwable cause
    ) {
        return new ServerException(
                ServerErrorCode.TASK_DATA_UNAVAILABLE,
                operation,
                null,
                cause
        );
    }
}
