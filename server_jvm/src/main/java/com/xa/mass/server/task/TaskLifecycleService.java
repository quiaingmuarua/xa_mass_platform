package com.xa.mass.server.task;

import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.task.TaskLifecycleCommands;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskApprovalResult;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskCloseResult;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskDataPhase;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.credit.TaskCreditConfirmation;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public final class TaskLifecycleService {

    private final TaskLifecycleCommands lifecycle;
    private final Map<String, FiniteTaskApprovalAdmission> admissions;
    private final TaskResourceCatalog catalog;
    private final OperationGuard operations;
    private final TaskItemScoreBandCore itemScores;
    private final TaskScoreBandCore taskScores;
    private final TaskRuntime runtime;

    public TaskLifecycleService(
            TaskLifecycleCommands lifecycle, TaskResourceCatalog catalog, OperationGuard operations,
            TaskItemScoreBandCore itemScores, TaskScoreBandCore taskScores, TaskRuntime runtime,
            List<FiniteTaskApprovalAdmission> admissions) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.admissions = admissions.stream().collect(
                Collectors.toUnmodifiableMap(FiniteTaskApprovalAdmission::inputOwner, a -> a));
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.itemScores = Objects.requireNonNull(itemScores, "itemScores");
        this.taskScores = Objects.requireNonNull(taskScores, "taskScores");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public ActionOutcome approve(
            TaskCaller caller, String taskId, long expectedItemCount, TaskCreditConfirmation credits) {
        if (expectedItemCount <= 0) {
            throw failure(ServerErrorCode.INVALID_TASK_DATA_REQUEST, "taskLifecycle.approve");
        }
        return operations.taskMutation(taskId, () -> approveObserved(caller, taskId, expectedItemCount, credits));
    }

    private ActionOutcome approveObserved(
            TaskCaller caller, String taskId, long expectedItemCount, TaskCreditConfirmation credits) {
        String operation = "taskLifecycle.approve";
        TaskDescriptor task = requireFiniteTask(caller, taskId, operation);
        TaskApprovalResult result = withTaskData(operation, () -> {
            var state = taskScores.getScoreStates(List.of(taskId)).get(taskId);
            if (state == null) {
                throw unavailable(operation, null);
            }
            var admission = admissions.get(task.metadata().getOrDefault(TaskCaller.INPUT_OWNER, ""));
            if (admission != null) {
                admission.beforeApprove(caller, task, expectedItemCount, credits);
            } else if (credits != null) {
                throw failure(ServerErrorCode.INVALID_CREDIT_REQUEST, operation);
            }
            if (state.band() == TaskScoreBand.PRE_REVIEW) {
                var count = itemScores.observeItemScoreCounts(List.of(taskId)).get(taskId);
                if (count == null) {
                    throw unavailable(operation, null);
                }
                if (count.total() != expectedItemCount) {
                    throw failure(ServerErrorCode.TASK_STATE_CONFLICT, operation);
                }
            }
            return lifecycle.approveTask(taskId);
        });
        if (result == null) {
            throw unavailable(operation, null);
        }
        return switch (result.status()) {
            case APPROVED -> ActionOutcome.applied();
            case ALREADY_APPROVED -> ActionOutcome.unchanged();
            case NOT_FOUND -> throw failure(ServerErrorCode.TASK_NOT_FOUND, operation);
            case CONFLICT, INVALID -> throw failure(ServerErrorCode.TASK_STATE_CONFLICT, operation);
            case RETRYABLE -> throw failure(ServerErrorCode.TASK_DATA_UNAVAILABLE, operation);
        };
    }

    public ActionOutcome close(TaskCaller caller, String taskId) {
        return operations.taskMutation(taskId, () -> closeObserved(caller, taskId));
    }

    private ActionOutcome closeObserved(TaskCaller caller, String taskId) {
        String operation = "taskLifecycle.close";
        TaskDescriptor task = requireFiniteTask(caller, taskId, operation);
        var admission = admissions.get(task.metadata().getOrDefault(TaskCaller.INPUT_OWNER, ""));
        if (admission != null) {
            admission.beforeClose(caller, task);
        }
        TaskCloseResult result = withTaskData(operation, () -> lifecycle.closeTask(taskId));
        if (result == null) {
            throw unavailable(operation, null);
        }
        return switch (result.status()) {
            case CLOSED -> ActionOutcome.applied();
            case ALREADY_CLOSED -> ActionOutcome.unchanged();
            case NOT_FOUND -> throw failure(ServerErrorCode.TASK_NOT_FOUND, operation);
            case INVALID -> throw failure(ServerErrorCode.TASK_STATE_CONFLICT, operation);
            case RETRYABLE -> throw failure(ServerErrorCode.TASK_DATA_UNAVAILABLE, operation);
        };
    }

    private TaskDescriptor requireFiniteTask(TaskCaller caller, String taskId, String operation) {
        TaskDescriptor descriptor = withTaskData(
                operation, () -> catalog.loadTaskAllocationDescriptors(List.of(taskId)).get(taskId));
        if (descriptor == null) {
            throw failure(ServerErrorCode.TASK_NOT_FOUND, operation);
        }
        caller.requireTask(descriptor);
        var data = runtime.loadTaskDataStates(List.of(taskId)).get(taskId);
        if (data == null) {
            throw failure(ServerErrorCode.TASK_NOT_FOUND, operation);
        }
        if (data.phase() == TaskDataPhase.PURGING) {
            throw failure(ServerErrorCode.TASK_CLEANUP_IN_PROGRESS, operation);
        }
        if (descriptor.idleDisposition() != TaskIdleDisposition.CLOSE_WHEN_IDLE) {
            throw failure(ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED, operation);
        }
        return descriptor;
    }

    private static <T> T withTaskData(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (ServerException known) {
            throw known;
        } catch (RuntimeException error) {
            throw unavailable(operation, error);
        }
    }

    private static ServerException failure(ServerErrorCode code, String operation) {
        return new ServerException(code, operation, null, null);
    }

    private static ServerException unavailable(String operation, Throwable cause) {
        return new ServerException(ServerErrorCode.TASK_DATA_UNAVAILABLE, operation, null, cause);
    }
}
