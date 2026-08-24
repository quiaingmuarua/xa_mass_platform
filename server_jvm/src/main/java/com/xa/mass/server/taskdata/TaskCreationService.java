package com.xa.mass.server.taskdata;

import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.api.v1.model.RuntimeCommandStatus;
import com.xa.mass.server.api.v1.model.TaskCreateRequest;
import com.xa.mass.server.api.v1.model.TaskCreateResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public final class TaskCreationService {

    private static final String OPERATION = "taskCreation.create";

    private final WorkerResourceCatalog workerCatalog;
    private final TaskRuntime taskRuntime;
    private final TaskIdGenerator taskIds;

    public TaskCreationService(
            WorkerResourceCatalog workerCatalog,
            TaskRuntime taskRuntime,
            TaskIdGenerator taskIds
    ) {
        this.workerCatalog = Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
        this.taskRuntime = Objects.requireNonNull(taskRuntime, "taskRuntime");
        this.taskIds = Objects.requireNonNull(taskIds, "taskIds");
    }

    public TaskCreateResponse create(TaskCreateRequest request) {
        requireWorkerGroup(request.workerGroupId());
        String taskId = taskIds.nextTaskId();
        TaskDescriptor descriptor = new TaskDescriptor(
                taskId,
                request.workerGroupId(),
                WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE,
                TaskIdleDisposition.CLOSE_WHEN_IDLE,
                request.allocationRule(),
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
        return new TaskCreateResponse(
                taskId,
                RuntimeCommandStatus.fromWireValue(
                        result.status().wireValue()
                ),
                result.reason()
        );
    }

    private void requireWorkerGroup(String workerGroupId) {
        try {
            if (workerCatalog.getWorkerGroupDescriptors(
                    List.of(workerGroupId)
            ).get(workerGroupId) == null) {
                throw new ServerException(
                        ServerErrorCode.WORKER_GROUP_NOT_FOUND,
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
