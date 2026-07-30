package com.xa.mass.server.taskdata;

import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendResult;
import com.xa.mass.server.api.v1.model.TaskRpcCallRequest;
import com.xa.mass.server.api.v1.model.TaskRpcCallResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

@Service
public final class TaskRpcCallService {

    private final TaskDataService taskData;
    private final TaskRuntime taskRuntime;
    private final TaskRpcWaitRegistry registry;
    private final long defaultWaitTimeoutMillis;
    private final long maxWaitTimeoutMillis;

    public TaskRpcCallService(
            TaskDataService taskData,
            TaskRuntime taskRuntime,
            TaskRpcWaitRegistry registry,
            TaskRpcProperties properties
    ) {
        this.taskData = taskData;
        this.taskRuntime = taskRuntime;
        this.registry = registry;
        this.defaultWaitTimeoutMillis =
                properties.defaultWaitTimeoutMillis();
        this.maxWaitTimeoutMillis = properties.maxWaitTimeoutMillis();
    }

    public DeferredResult<ResponseEntity<TaskRpcCallResponse>> call(
            String taskId,
            TaskRpcCallRequest request
    ) {
        long timeoutMillis = resolveTimeout(request.waitTimeoutMillis());
        String messageId = request.item().messageId();
        TaskItemAppendResult appended = taskData.appendTaskItem(
                taskId,
                request.item()
        );
        requireAcceptedAppend(appended);

        String existingResult = loadImmediateResult(taskId, messageId);
        DeferredResult<ResponseEntity<TaskRpcCallResponse>> deferred =
                new DeferredResult<>(timeoutMillis);
        if (existingResult != null) {
            deferred.setResult(ResponseEntity.ok(
                    TaskRpcCallResponse.succeeded(
                            taskId,
                            messageId,
                            existingResult
                    )
            ));
            return deferred;
        }

        registry.register(taskId, messageId, deferred);
        return deferred;
    }

    private long resolveTimeout(Long requested) {
        long timeout = requested == null
                ? defaultWaitTimeoutMillis
                : requested;
        if (timeout <= 0 || timeout > maxWaitTimeoutMillis) {
            throw new ServerException(
                    ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                    "taskRpc.resolveTimeout",
                    "waitTimeoutMillis is outside the configured bound",
                    null
            );
        }
        return timeout;
    }

    private String loadImmediateResult(
            String taskId,
            String messageId
    ) {
        try {
            Map<String, String> results =
                    taskRuntime.loadTaskItemSuccessResults(
                            taskId,
                            List.of(messageId)
                    );
            return results.get(messageId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void requireAcceptedAppend(
            TaskItemAppendResult appended
    ) {
        switch (appended.status()) {
            case APPENDED -> {
                return;
            }
            case NOT_FOUND -> throw new ServerException(
                    ServerErrorCode.TASK_NOT_FOUND,
                    "taskRpc.appendItem",
                    appended.reason(),
                    null
            );
            case INVALID -> throw new ServerException(
                    ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                    "taskRpc.appendItem",
                    appended.reason(),
                    null
            );
            case RETRYABLE -> throw new ServerException(
                    ServerErrorCode.TASK_DATA_UNAVAILABLE,
                    "taskRpc.appendItem",
                    appended.reason(),
                    null
            );
        }
    }
}
