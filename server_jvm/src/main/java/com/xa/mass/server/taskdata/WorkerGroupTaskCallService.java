package com.xa.mass.server.taskdata;

import com.xa.mass.server.api.v1.model.TaskRpcCallRequest;
import com.xa.mass.server.api.v1.model.TaskRpcCallResponse;
import com.xa.mass.server.api.v1.model.TaskItemResultsLoadResponse;
import java.util.List;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

@Service
public final class WorkerGroupTaskCallService {

    private final WorkerGroupTaskCallRegistrationService registrations;
    private final TaskRpcCallService taskRpc;
    private final TaskDataService taskData;

    public WorkerGroupTaskCallService(
            WorkerGroupTaskCallRegistrationService registrations,
            TaskRpcCallService taskRpc,
            TaskDataService taskData
    ) {
        this.registrations = Objects.requireNonNull(
                registrations,
                "registrations"
        );
        this.taskRpc = Objects.requireNonNull(taskRpc, "taskRpc");
        this.taskData = Objects.requireNonNull(taskData, "taskData");
    }

    public DeferredResult<ResponseEntity<TaskRpcCallResponse>> call(
            String workerGroupId,
            TaskRpcCallRequest request
    ) {
        String taskId = registrations.requireRegisteredTaskId(workerGroupId);
        return taskRpc.call(taskId, request);
    }

    public TaskItemResultsLoadResponse loadSuccessResults(
            String workerGroupId,
            List<String> messageIds
    ) {
        String taskId = registrations.requireRegisteredTaskId(workerGroupId);
        return taskData.loadTaskItemSuccessResults(taskId, messageIds);
    }
}
