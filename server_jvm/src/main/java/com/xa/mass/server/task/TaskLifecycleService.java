package com.xa.mass.server.task;

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

    public TaskLifecycleService(
            TaskLifecycleCommands lifecycle,
            TaskResourceCatalog catalog,
            OperationGuard operations
    ) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    public ActionOutcome approve(String taskId) {
        return operations.taskMutation(taskId, () -> approveObserved(taskId));
    }

    private ActionOutcome approveObserved(String taskId) {
        String operation = "taskLifecycle.approve";
        requireFiniteTask(taskId, operation);
        TaskLifecycleCommands.TaskApprovalResult result;
        try {
            result = lifecycle.approveTask(taskId);
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

    public ActionOutcome close(String taskId) {
        return operations.taskMutation(taskId, () -> closeObserved(taskId));
    }

    private ActionOutcome closeObserved(String taskId) {
        String operation = "taskLifecycle.close";
        requireFiniteTask(taskId, operation);
        TaskLifecycleCommands.TaskCloseResult result;
        try {
            result = lifecycle.closeTask(taskId);
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

    private void requireFiniteTask(String taskId, String operation) {
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
