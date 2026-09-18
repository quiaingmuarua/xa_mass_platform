package com.xa.mass.server.task;

import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.api.v1.contract.task.TaskCreateRequest;
import com.xa.mass.server.api.v1.contract.task.TaskCreateResponse;
import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import com.xa.mass.kernel.assignment.RefillTarget;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class TaskCreationService {

    private static final String OPERATION = "taskCreation.create";

    private final WorkerResourceCatalog workerCatalog;
    private final WorkerMatchingCatalog matchingCatalog;
    private final TaskRuntime taskRuntime;
    private final TaskIdGenerator taskIds;
    private final ProjectDirectory projects;

    public TaskCreationService(
            WorkerResourceCatalog workerCatalog,
            WorkerMatchingCatalog matchingCatalog,
            TaskRuntime taskRuntime,
            TaskIdGenerator taskIds,
            ProjectDirectory projects
    ) {
        this.workerCatalog = Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
        this.matchingCatalog = Objects.requireNonNull(
                matchingCatalog,
                "matchingCatalog"
        );
        this.taskRuntime = Objects.requireNonNull(taskRuntime, "taskRuntime");
        this.taskIds = Objects.requireNonNull(taskIds, "taskIds");
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    public TaskCreateResponse create(TaskCreateRequest request) {
        if (request == null || request.workerGroupId() == null || request.workerGroupId().isBlank()
                || request.priority() < 0 || request.priority() > 99
                || request.maxRetryTimes() < 0 || request.maxRetryTimes() > 98) {
            throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST, OPERATION, "Invalid Task creation request", null);
        }
        projects.requireManagedTaskId(request.projectId(), request.workerGroupId());
        requireWorkerGroup(request.workerGroupId());
        String taskId = taskIds.nextTaskId();
        List<RefillTarget> targets = resolveTargets(request);
        var config = new java.util.LinkedHashMap<String, String>();
        config.put("priority", Integer.toString(request.priority()));
        config.put("maxRetryTimes", Integer.toString(request.maxRetryTimes()));
        TaskDescriptor descriptor = new TaskDescriptor(
                taskId,
                request.projectId(),
                request.workerGroupId(),
                TaskIdleDisposition.CLOSE_WHEN_IDLE,
                config,
                targets
        );
        TaskCreationResult result;
        try {
            result = taskRuntime.createTask(descriptor);
        } catch (RuntimeException error) {
            throw unavailable(error);
        }
        if (result == null) {
            throw unavailable(null);
        }
        return switch (result.status()) {
            case CREATED -> new TaskCreateResponse(taskId);
            case CONFLICT -> throw new ServerException(
                    ServerErrorCode.TASK_STATE_CONFLICT,
                    OPERATION,
                    null,
                    null
            );
            case INVALID -> throw new ServerException(
                    ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                    OPERATION,
                    null,
                    null
            );
            case RETRYABLE -> throw unavailable(null);
        };
    }

    private List<RefillTarget> resolveTargets(TaskCreateRequest request) {
        try {
            return java.util.Objects.requireNonNull(matchingCatalog.normalizeRefill(
                    request.workerGroupId(), request.refill()));
        } catch (IllegalArgumentException error) {
            throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST, OPERATION,
                    error.getMessage(), error);
        } catch (RuntimeException error) {
            throw unavailable(error);
        }
    }

    private void requireWorkerGroup(String workerGroupId) {
        try {
            if (workerCatalog.getWorkerGroupDescriptors(
                    List.of(workerGroupId)
            ).get(workerGroupId) == null) {
                throw new ServerException(
                        ServerErrorCode.TASK_WORKER_GROUP_NOT_FOUND,
                        OPERATION,
                        null,
                        null
                );
            }
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(error);
        }
    }

    private static ServerException unavailable(Throwable cause) {
        return new ServerException(
                ServerErrorCode.TASK_DATA_UNAVAILABLE,
                OPERATION,
                null,
                cause
        );
    }
}
