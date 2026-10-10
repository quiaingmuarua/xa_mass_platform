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
        return call(taskId, request, java.util.function.Function.identity());
    }

    /** One servlet waiter, with a pure application response mapping. */
    public <T> DeferredResult<T> call(String taskId, TaskRpcCallRequest request,
            java.util.function.Function<Map<String, TaskItemResultResponse>, T> mapping) {
        long timeoutMillis = resolveTimeout(request.waitTimeoutMillis());
        TaskCallSubmissionService.SubmittedCall submitted = submissions.submitCall(taskId, request.items());
        List<String> messageIds = submitted.messageIds();
        Map<String, TaskItemResult> observed = submitted.observed();
        long immediateStarted = submitted.observationStarted();
        DeferredResult<T> deferred =
                new DeferredResult<>(timeoutMillis);
        if (allObserved(messageIds, observed)) {
            TaskRpcStageEvent.items(immediateStarted, "OBSERVED", taskId, observed.keySet(), observed.size(), false);
            deferred.setResult(mapping.apply(TaskItemResultResponse.fromObservedResults(
                    messageIds,
                    observed
            )));
            return deferred;
        }

        if (!registry.tryRegister(
                taskId,
                messageIds,
                observed,
                deferred, mapping
        )) {
            deferred.setResult(mapping.apply(TaskItemResultResponse.fromObservedResults(
                    messageIds,
                    observed
            )));
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
