package com.xa.mass.server.taskdata;

import com.xa.mass.server.api.v1.model.TaskRpcCallRequest;
import com.xa.mass.server.api.v1.model.TaskRpcCallResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

@Service
public final class WorkerGroupTaskCallService {

    private final WorkerGroupTaskCatalog taskCatalog;
    private final TaskRpcCallService taskRpc;

    public WorkerGroupTaskCallService(
            WorkerGroupTaskCatalog taskCatalog,
            TaskRpcCallService taskRpc
    ) {
        this.taskCatalog = Objects.requireNonNull(taskCatalog, "taskCatalog");
        this.taskRpc = Objects.requireNonNull(taskRpc, "taskRpc");
    }

    public DeferredResult<ResponseEntity<TaskRpcCallResponse>> call(
            String workerGroupId,
            TaskRpcCallRequest request
    ) {
        String taskId = taskCatalog.taskIdFor(workerGroupId);
        if (taskId == null) {
            throw new ServerException(
                    ServerErrorCode.WORKER_GROUP_NOT_FOUND,
                    "workerGroupTask.call",
                    null,
                    null
            );
        }
        return taskRpc.call(taskId, request);
    }
}
