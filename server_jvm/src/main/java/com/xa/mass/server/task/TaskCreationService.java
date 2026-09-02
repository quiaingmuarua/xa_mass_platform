package com.xa.mass.server.task;

import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.api.v1.contract.task.TaskCreateRequest;
import com.xa.mass.server.api.v1.contract.task.TaskCreateResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import com.xa.mass.workermatching.WorkerMatchingCatalog.MutationResult;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class TaskCreationService {

    private static final String OPERATION = "taskCreation.create";

    private final WorkerResourceCatalog workerCatalog;
    private final WorkerMatchingCatalog matchingCatalog;
    private final TaskRuntime taskRuntime;
    private final TaskIdGenerator taskIds;

    public TaskCreationService(
            WorkerResourceCatalog workerCatalog,
            WorkerMatchingCatalog matchingCatalog,
            TaskRuntime taskRuntime,
            TaskIdGenerator taskIds
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
    }

    public TaskCreateResponse create(TaskCreateRequest request) {
        requireWorkerGroup(request.workerGroupId());
        String taskId = taskIds.nextTaskId();
        createTaskRule(taskId, request);
        TaskDescriptor descriptor = new TaskDescriptor(
                taskId,
                request.workerGroupId(),
                WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE,
                TaskIdleDisposition.CLOSE_WHEN_IDLE,
                Map.of(
                        "priority", Integer.toString(request.priority()),
                        "maximumCandidateWorkers",
                        Integer.toString(request.maximumCandidateWorkers()),
                        "maxRetryTimes",
                        Integer.toString(request.maxRetryTimes())
                )
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

    private void createTaskRule(
            String taskId,
            TaskCreateRequest request
    ) {
        MutationResult result;
        try {
            result = matchingCatalog.createTaskRule(
                    taskId,
                    request.workerGroupId(),
                    request.allocationRule()
            );
        } catch (RuntimeException error) {
            throw unavailable(error);
        }
        if (result == null) {
            throw unavailable(null);
        }
        switch (result.status()) {
            case APPLIED, UNCHANGED -> {
                return;
            }
            case INVALID -> throw new ServerException(
                    ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                    OPERATION,
                    result.reason(),
                    null
            );
            case CONFLICT -> throw new ServerException(
                    ServerErrorCode.TASK_STATE_CONFLICT,
                    OPERATION,
                    result.reason(),
                    null
            );
            case NOT_FOUND -> throw unavailable(null);
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
