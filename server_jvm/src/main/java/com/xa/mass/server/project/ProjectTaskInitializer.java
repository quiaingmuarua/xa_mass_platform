package com.xa.mass.server.project;

import com.xa.mass.kernel.task.TaskLifecycleCommands;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskApprovalResult;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskApprovalStatus;
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
import com.xa.mass.server.task.call.TaskRpcProperties;
import org.springframework.stereotype.Service;

@Service
public final class ProjectTaskInitializer {

    private static final String REGISTER_OPERATION = "project.initialize";
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

    public ProjectTaskInitializer(
            WorkerResourceCatalog workerCatalog,
            TaskResourceCatalog taskCatalog,
            TaskRuntime taskRuntime,
            TaskLifecycleCommands taskLifecycle,
            WorkerMatchingCatalog matching,
            TaskRpcProperties rpc,
            ProjectDirectory projects
    ) {
        this.projects = Objects.requireNonNull(projects, "projects");
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

    public void initialize() {
        for (var project : projects.projects().values()) {
            for (String group : project.managedTaskIds().keySet()) {
                prepare(project.projectId(), group);
            }
        }
    }

    public Registration prepare(String projectId, String workerGroupId) {
        projects.requireManagedTaskId(projectId, workerGroupId);
        requireWorkerGroup(workerGroupId, REGISTER_OPERATION);
        TaskDescriptor expected = descriptor(projectId, workerGroupId);
        TaskDescriptor existing = loadDescriptor(
                expected.taskId(),
                REGISTER_OPERATION
        );
        boolean created = false;
        if (existing == null) {
            TaskCreationResult creation = create(expected);
            if (creation.status() == TaskCreationStatus.CREATED) {
                created = true;
            } else if (creation.status() == TaskCreationStatus.CONFLICT) {
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
                requireEquivalent(
                        expected,
                        existing,
                        REGISTER_OPERATION
                );
            } else if (creation.status() == TaskCreationStatus.INVALID) {
                throw unavailable(
                        REGISTER_OPERATION,
                        creation.reason(),
                        null
                );
            } else {
                throw unavailable(REGISTER_OPERATION, creation.reason(), null);
            }
        } else {
            requireEquivalent(expected, existing, REGISTER_OPERATION);
        }

        TaskApprovalResult approval = approve(expected.taskId());
        return switch (approval.status()) {
            case APPROVED -> new Registration(
                    workerGroupId,
                    expected.taskId(),
                    true
            );
            case ALREADY_APPROVED -> new Registration(
                    workerGroupId,
                    expected.taskId(),
                    created
            );
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
                    "derived Task descriptor conflicts with registration",
                    null
            );
        }
    }

    private TaskDescriptor descriptor(String projectId, String workerGroupId) {
        try {
            return new TaskDescriptor(
                    projects.requireManagedTaskId(projectId, workerGroupId), projectId, workerGroupId, TaskIdleDisposition.PARK_WHEN_IDLE,
                    TASK_CONFIG, matching.normalizeRefill(workerGroupId, rpc.refillByWorkerGroup().getOrDefault(workerGroupId,
                            List.of())), null, java.util.Map.of());
        } catch (RuntimeException error) {
            throw unavailable(REGISTER_OPERATION, "Call Task targets unavailable", error);
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

    public record Registration(
            String workerGroupId,
            String taskId,
            boolean newlyRegistered
    ) {
    }
}
