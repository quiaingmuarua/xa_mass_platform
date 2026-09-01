package com.xa.mass.server.task;

import com.xa.mass.kernel.task.TaskLifecycleCommands;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.server.api.v1.model.TaskApprovalResponse;
import com.xa.mass.server.api.v1.model.TaskCloseResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class TaskLifecycleService {

    private final TaskLifecycleCommands lifecycle;
    private final TaskResourceCatalog catalog;

    public TaskLifecycleService(
            TaskLifecycleCommands lifecycle,
            TaskResourceCatalog catalog
    ) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public TaskApprovalResponse approve(String taskId) {
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
            case APPROVED -> new TaskApprovalResponse(
                    TaskApprovalResponse.Status.APPROVED
            );
            case ALREADY_APPROVED -> new TaskApprovalResponse(
                    TaskApprovalResponse.Status.ALREADY_APPROVED
            );
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

    public TaskCloseResponse close(String taskId) {
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
            case CLOSED -> new TaskCloseResponse(
                    TaskCloseResponse.Status.CLOSED
            );
            case ALREADY_CLOSED -> new TaskCloseResponse(
                    TaskCloseResponse.Status.ALREADY_CLOSED
            );
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
        if (descriptor.workerAllocationMechanism()
                != WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
                || descriptor.idleDisposition()
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
