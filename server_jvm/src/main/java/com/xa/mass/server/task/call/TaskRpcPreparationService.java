package com.xa.mass.server.task.call;

import com.xa.mass.server.task.access.TaskCaller;

import com.xa.mass.kernel.task.TaskLifecycleCommands;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskApprovalResult;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.xa.mass.server.api.v1.contract.task.TaskCreateResponse;
import com.xa.mass.server.api.v1.contract.task.TaskRpcPrepareRequest;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.task.directory.TaskAttribution;
import com.xa.mass.server.task.directory.TaskDirectory;
import org.springframework.stereotype.Service;

@Service
public final class TaskRpcPreparationService {

    private static final String REGISTER_OPERATION = "taskRpc.prepare";
    private static final Map<String, String> TASK_CONFIG = Map.of(
            "priority", "0",
            "maxRetryTimes", "3"
    );

    private final ProjectDirectory projects;
    private final WorkerResourceCatalog workerCatalog;
    private final TaskResourceCatalog taskCatalog;
    private final TaskRuntime taskRuntime;
    private final WorkerMatchingCatalog matching;
    private final TaskRpcProperties rpc;
    private final TaskLifecycleCommands taskLifecycle;
    private final OperationGuard operations;
    private final TaskDirectory directory;
    private final TaskScoreBandCore scores;

    public TaskRpcPreparationService(
            WorkerResourceCatalog workerCatalog,
            TaskResourceCatalog taskCatalog,
            TaskRuntime taskRuntime,
            TaskLifecycleCommands taskLifecycle,
            WorkerMatchingCatalog matching,
            TaskRpcProperties rpc,
            ProjectDirectory projects,
            OperationGuard operations,
            TaskDirectory directory,
            TaskScoreBandCore scores
    ) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.scores = Objects.requireNonNull(scores, "scores");
        this.rpc = Objects.requireNonNull(rpc, "rpc");
        this.matching = Objects.requireNonNull(matching, "matching");
        this.workerCatalog = Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
        this.taskCatalog = Objects.requireNonNull(taskCatalog, "taskCatalog");
        this.taskRuntime = Objects.requireNonNull(taskRuntime, "taskRuntime");
        this.taskLifecycle = Objects.requireNonNull(
                taskLifecycle,
                "taskLifecycle"
        );
    }

    public TaskCreateResponse prepare(TaskCaller caller, String inputOwner, String taskId, TaskRpcPrepareRequest request) {
        if (taskId == null || taskId.isBlank() || request == null
                || request.projectId() == null || request.projectId().isBlank()
                || request.workerGroupId() == null || request.workerGroupId().isBlank())
            throw failure(ServerErrorCode.INVALID_TASK_DATA_REQUEST, REGISTER_OPERATION, "Invalid RPC Task preparation", null);
        caller.requireProject(request.projectId());
        projects.requireInputOwner(request.projectId(), inputOwner);
        final TaskRpcPrepareRequest admitted;
        try { admitted = new TaskRpcPrepareRequest(request.projectId(), request.workerGroupId(), request.name(), TaskAttribution.input(request.metadata()), request.refill()); }
        catch (IllegalArgumentException invalid) { throw failure(ServerErrorCode.INVALID_TASK_DATA_REQUEST, REGISTER_OPERATION, invalid.getMessage(), invalid); }
        return operations.taskMutation(taskId, () -> prepareObserved(caller, inputOwner, taskId, admitted));
    }

    private TaskCreateResponse prepareObserved(TaskCaller caller, String inputOwner, String taskId, TaskRpcPrepareRequest request) {
        projects.requireWorkerGroup(request.projectId(), request.workerGroupId());
        requireWorkerGroup(request.workerGroupId(), REGISTER_OPERATION);
        TaskDescriptor existing = loadDescriptor(
                taskId,
                REGISTER_OPERATION
        );
        if (existing != null) caller.requireInput(existing, inputOwner);
        TaskDescriptor expected = descriptor(caller, inputOwner, taskId, request, existing);
        if (existing == null) {
            try {
                if (scores.getScoreStates(List.of(taskId)).get(taskId) != null || directory.contains(request.projectId(), taskId))
                    throw new IllegalStateException("RPC identity has incomplete Task resources");
            } catch (RuntimeException failure) {
                throw unavailable(REGISTER_OPERATION, "RPC Task resources are unconfirmed", failure);
            }
            TaskCreationResult creation = create(expected);
            if (creation.status() == TaskCreationStatus.CONFLICT) {
                existing = loadDescriptor(
                        expected.taskId(),
                        REGISTER_OPERATION
                );
                if (existing == null) {
                    throw unavailable(
                            REGISTER_OPERATION,
                            "conflicting Task is not observable",
                            null
                    );
                }
                caller.requireInput(existing, inputOwner);
                requireEquivalent(
                        descriptor(caller, inputOwner, taskId, request, existing),
                        existing,
                        REGISTER_OPERATION
                );
            } else if (creation.status() == TaskCreationStatus.INVALID) {
                throw unavailable(
                        REGISTER_OPERATION,
                        creation.reason(),
                        null
                );
            } else if (creation.status() != TaskCreationStatus.CREATED) {
                throw unavailable(REGISTER_OPERATION, creation.reason(), null);
            }
        } else {
            requireEquivalent(expected, existing, REGISTER_OPERATION);
        }

        try { directory.ensure(caller, taskId); }
        catch (RuntimeException failure) { throw unavailable(REGISTER_OPERATION, "Task directory is unconfirmed", failure); }
        TaskApprovalResult approval = approve(expected.taskId());
        return switch (approval.status()) {
            case APPROVED, ALREADY_APPROVED -> new TaskCreateResponse(expected.taskId());
            case NOT_FOUND, RETRYABLE -> throw unavailable(
                    REGISTER_OPERATION,
                    approval.reason(),
                    null
            );
            case CONFLICT -> throw failure(
                    ServerErrorCode.TASK_CALL_REGISTRATION_CONFLICT,
                    REGISTER_OPERATION,
                    approval.reason(),
                    null
            );
            case INVALID -> throw unavailable(
                    REGISTER_OPERATION,
                    approval.reason(),
                    null
            );
        };
    }

    private TaskCreationResult create(TaskDescriptor descriptor) {
        try {
            TaskCreationResult result = taskRuntime.createTask(descriptor);
            if (result == null) {
                throw unavailable(REGISTER_OPERATION, null, null);
            }
            return result;
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(REGISTER_OPERATION, null, error);
        }
    }

    private TaskApprovalResult approve(String taskId) {
        try {
            TaskApprovalResult result = taskLifecycle.approveTask(taskId);
            if (result == null) {
                throw unavailable(REGISTER_OPERATION, null, null);
            }
            return result;
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(REGISTER_OPERATION, null, error);
        }
    }

    private void requireWorkerGroup(String workerGroupId, String operation) {
        requireNonBlank(workerGroupId);
        try {
            if (workerCatalog.getWorkerGroupDescriptors(
                    List.of(workerGroupId)
            ).get(workerGroupId) == null) {
                throw failure(
                        ServerErrorCode.WORKER_GROUP_NOT_FOUND,
                        operation,
                        null,
                        null
                );
            }
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(operation, null, error);
        }
    }

    private TaskDescriptor loadDescriptor(String taskId, String operation) {
        try {
            return taskCatalog.loadTaskAllocationDescriptors(
                    List.of(taskId)
            ).get(taskId);
        } catch (RuntimeException error) {
            throw unavailable(operation, null, error);
        }
    }

    private static void requireEquivalent(
            TaskDescriptor expected,
            TaskDescriptor existing,
            String operation
    ) {
        if (!expected.equals(existing)) {
            throw failure(
                    ServerErrorCode.TASK_CALL_REGISTRATION_CONFLICT,
                    operation,
                    "Task identity has different configuration",
                    null
            );
        }
    }

    private TaskDescriptor descriptor(TaskCaller caller, String inputOwner, String taskId, TaskRpcPrepareRequest request, TaskDescriptor existing) {
        java.util.List<com.xa.mass.kernel.assignment.RefillTarget> refill;
        try {
            refill = Objects.requireNonNull(matching.normalizeRefill(request.workerGroupId(),
                    request.refill() == null
                            ? rpc.refillByWorkerGroup().getOrDefault(request.workerGroupId(), List.of())
                            : request.refill()));
        } catch (IllegalArgumentException invalid) {
            if (request.refill() != null) throw failure(ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                    REGISTER_OPERATION, invalid.getMessage(), invalid);
            throw unavailable(REGISTER_OPERATION, "Call Task targets unavailable", invalid);
        } catch (RuntimeException error) {
            throw unavailable(REGISTER_OPERATION, "Call Task targets unavailable", error);
        }
        try {
            var saved = existing == null ? null : TaskAttribution.require(existing.metadata());
            long created = saved == null ? System.currentTimeMillis() : saved.createdAtMillis();
            String creator = saved == null ? caller.userId() : saved.userId();
            var metadata = new java.util.LinkedHashMap<>(new TaskAttribution(request.projectId(), created, creator).stamp(request.metadata()));
            metadata.put(TaskCaller.INPUT_OWNER, inputOwner);
            return new TaskDescriptor(taskId, request.workerGroupId(), TaskIdleDisposition.PARK_WHEN_IDLE,
                    TASK_CONFIG, refill, request.name(), metadata);
        } catch (IllegalArgumentException error) {
            throw failure(ServerErrorCode.INVALID_TASK_DATA_REQUEST, REGISTER_OPERATION, null, error);
        }
    }

    private static void requireNonBlank(String workerGroupId) {
        if (workerGroupId == null || workerGroupId.isBlank()) {
            throw failure(
                    ServerErrorCode.INVALID_WORKER_GROUP_REQUEST,
                    REGISTER_OPERATION,
                    "workerGroupId must be non-blank",
                    null
            );
        }
    }

    private static ServerException unavailable(
            String operation,
            String message,
            Throwable cause
    ) {
        return failure(
                ServerErrorCode.TASK_CALL_REGISTRATION_UNAVAILABLE,
                operation,
                message,
                cause
        );
    }

    private static ServerException failure(
            ServerErrorCode code,
            String operation,
            String message,
            Throwable cause
    ) {
        return new ServerException(code, operation, message, cause);
    }

}
