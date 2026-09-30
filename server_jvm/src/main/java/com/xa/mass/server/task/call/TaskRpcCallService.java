package com.xa.mass.server.task.call;

import com.xa.mass.server.task.call.TaskCallSubmissionService;
import com.xa.mass.server.task.call.TaskRpcStageEvent;

import com.xa.mass.kernel.task.TaskRuntime.TaskItemResult;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse;
import com.xa.mass.server.api.v1.contract.task.TaskRpcCallRequest;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

@Service
public final class TaskRpcCallService {
    private final TaskCallSubmissionService submissions;
    private final TaskRpcWaitRegistry registry;
    private final long defaultWaitTimeoutMillis;
    private final long maxWaitTimeoutMillis;

    public TaskRpcCallService(TaskCallSubmissionService submissions,
            TaskRpcWaitRegistry registry, TaskRpcProperties properties) {
        this.submissions = submissions;
        this.registry = registry;
        this.defaultWaitTimeoutMillis = properties.defaultWaitTimeoutMillis();
        this.maxWaitTimeoutMillis = properties.maxWaitTimeoutMillis();
    }

    public DeferredResult<Map<String, TaskItemResultResponse>> call(String taskId, TaskRpcCallRequest request) {
        long timeoutMillis = resolveTimeout(request.waitTimeoutMillis());
        TaskCallSubmissionService.SubmittedCall submitted = submissions.submitCall(taskId, request.items());
        List<String> messageIds = submitted.messageIds();
        Map<String, TaskItemResult> observed = submitted.observed();
        long immediateStarted = submitted.observationStarted();
        DeferredResult<Map<String, TaskItemResultResponse>> deferred =
                new DeferredResult<>(timeoutMillis);
        if (allObserved(messageIds, observed)) {
            TaskRpcStageEvent.items(immediateStarted, "OBSERVED", taskId, observed.keySet(), observed.size(), false);
            deferred.setResult(TaskItemResultResponse.fromObservedResults(
                    messageIds,
                    observed
            ));
            return deferred;
        }

        if (!registry.tryRegister(
                taskId,
                messageIds,
                observed,
                deferred
        )) {
            deferred.setResult(TaskItemResultResponse.fromObservedResults(
                    messageIds,
                    observed
            ));
        }
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

    private static boolean allObserved(
            List<String> messageIds,
            Map<String, TaskItemResult> observed
    ) {
        return messageIds.stream().allMatch(messageId ->
                observed.get(messageId) != null);
    }

}
