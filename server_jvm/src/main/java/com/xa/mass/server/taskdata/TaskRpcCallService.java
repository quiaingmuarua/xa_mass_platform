package com.xa.mass.server.taskdata;

import com.xa.mass.kernel.task.TaskCallItemSubmission;
import com.xa.mass.kernel.task.TaskCallItemSubmission.TaskCallSubmissionResult;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendResult;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.server.api.v1.model.TaskItemRequest;
import com.xa.mass.server.api.v1.model.TaskRpcCallRequest;
import com.xa.mass.server.api.v1.model.TaskRpcCallResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.DeferredResult;

@Service
public final class TaskRpcCallService {

    private final TaskCallItemSubmission taskCallSubmission;
    private final TaskRuntime taskRuntime;
    private final TaskResourceCatalog taskCatalog;
    private final TaskRpcWaitRegistry registry;
    private final TaskItemMapper taskItems;
    private final long defaultWaitTimeoutMillis;
    private final long maxWaitTimeoutMillis;

    public TaskRpcCallService(
            TaskCallItemSubmission taskCallSubmission,
            TaskRuntime taskRuntime,
            TaskResourceCatalog taskCatalog,
            TaskRpcWaitRegistry registry,
            TaskItemMapper taskItems,
            TaskRpcProperties properties
    ) {
        this.taskCallSubmission = taskCallSubmission;
        this.taskRuntime = taskRuntime;
        this.taskCatalog = taskCatalog;
        this.registry = registry;
        this.taskItems = taskItems;
        this.defaultWaitTimeoutMillis =
                properties.defaultWaitTimeoutMillis();
        this.maxWaitTimeoutMillis = properties.maxWaitTimeoutMillis();
    }

    public DeferredResult<TaskRpcCallResponse> call(
            String taskId,
            TaskRpcCallRequest request
    ) {
        requireCallableTask(taskId);
        long timeoutMillis = resolveTimeout(request.waitTimeoutMillis());
        LinkedHashMap<String, TaskItemRequest> requestedItems = latestItems(
                request.items()
        );
        List<String> messageIds = List.copyOf(requestedItems.keySet());
        long createdAtMillis = taskItems.nowMillis();
        var submittedItems = new ArrayList<TaskItem>(requestedItems.size());
        try {
            requestedItems.values().forEach(item -> submittedItems.add(
                    taskItems.onDemandItem(item, createdAtMillis)
            ));
        } catch (IllegalArgumentException error) {
            throw new ServerException(
                    ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                    "taskRpc.mapItems",
                    error.getMessage(),
                    error
            );
        }

        TaskCallSubmissionResult submission;
        try {
            submission = taskCallSubmission.submit(taskId, submittedItems);
        } catch (RuntimeException error) {
            throw new ServerException(
                    ServerErrorCode.TASK_DATA_UNAVAILABLE,
                    "taskRpc.submitItems",
                    null,
                    error
            );
        }
        if (submission == null) {
            throw new ServerException(
                    ServerErrorCode.TASK_DATA_UNAVAILABLE,
                    "taskRpc.submitItems",
                    null,
                    null
            );
        }
        requireAcceptedSubmission(submission, messageIds);

        Map<String, String> observed = loadImmediateResults(
                taskId,
                messageIds
        );
        DeferredResult<TaskRpcCallResponse> deferred =
                new DeferredResult<>(timeoutMillis);
        if (allObserved(messageIds, observed)) {
            deferred.setResult(TaskRpcCallResponse.fromObservedResults(
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
            deferred.setResult(TaskRpcCallResponse.fromObservedResults(
                    messageIds,
                    observed
            ));
        }
        return deferred;
    }

    private void requireCallableTask(String taskId) {
        TaskDescriptor descriptor;
        try {
            descriptor = taskCatalog.loadTaskAllocationDescriptors(
                    List.of(taskId)
            ).get(taskId);
        } catch (RuntimeException error) {
            throw new ServerException(
                    ServerErrorCode.TASK_DATA_UNAVAILABLE,
                    "taskRpc.loadDescriptor",
                    null,
                    error
            );
        }
        if (descriptor == null) {
            throw new ServerException(
                    ServerErrorCode.TASK_NOT_FOUND,
                    "taskRpc.loadDescriptor",
                    null,
                    null
            );
        }
        if (descriptor.workerAllocationMechanism()
                != WorkerAllocationMechanism.ON_DEMAND_ITEM_RULE
                || descriptor.idleDisposition()
                != TaskIdleDisposition.PARK_WHEN_IDLE) {
            throw new ServerException(
                    ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED,
                    "taskRpc.validateTask",
                    "Task does not support synchronous Item Call",
                    null
            );
        }
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

    private Map<String, String> loadImmediateResults(
            String taskId,
            List<String> messageIds
    ) {
        try {
            Map<String, String> loaded =
                    taskRuntime.loadTaskItemSuccessResults(
                            taskId,
                            messageIds
                    );
            var observed = new LinkedHashMap<String, String>();
            messageIds.forEach(messageId -> {
                String payload = loaded.get(messageId);
                if (payload != null) {
                    observed.put(messageId, payload);
                }
            });
            return observed;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private static LinkedHashMap<String, TaskItemRequest> latestItems(
            List<TaskItemRequest> items
    ) {
        if (items == null || items.isEmpty() || items.size() > 100) {
            throw new ServerException(
                    ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                    "taskRpc.mapItems",
                    "items must contain 1..100 entries",
                    null
            );
        }
        var latest = new LinkedHashMap<String, TaskItemRequest>();
        for (TaskItemRequest item : items) {
            if (item == null) {
                throw new ServerException(
                        ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                        "taskRpc.mapItems",
                        "TaskItem must be present",
                        null
                );
            }
            latest.put(item.messageId(), item);
        }
        return latest;
    }

    private static boolean allObserved(
            List<String> messageIds,
            Map<String, String> observed
    ) {
        return messageIds.stream().allMatch(messageId ->
                observed.get(messageId) != null);
    }

    private static void requireAcceptedSubmission(
            TaskCallSubmissionResult submission,
            List<String> messageIds
    ) {
        switch (submission.status()) {
            case SUBMITTED -> {
                // Item-level results remain the canonical append outcomes.
            }
            case NOT_FOUND -> throw new ServerException(
                    ServerErrorCode.TASK_NOT_FOUND,
                    "taskRpc.submitItems",
                    submission.reason(),
                    null
            );
            case CLOSED, STALE -> throw new ServerException(
                    ServerErrorCode.TASK_STATE_CONFLICT,
                    "taskRpc.submitItems",
                    null,
                    null
            );
            case INVALID -> throw new ServerException(
                    ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                    "taskRpc.submitItems",
                    null,
                    null
            );
            case RETRYABLE -> throw new ServerException(
                    ServerErrorCode.TASK_DATA_UNAVAILABLE,
                    "taskRpc.submitItems",
                    null,
                    null
            );
        }
        for (String messageId : messageIds) {
            TaskItemAppendResult appended = submission.itemResults().get(
                    messageId
            );
            if (appended == null) {
                throw new ServerException(
                        ServerErrorCode.TASK_DATA_UNAVAILABLE,
                        "taskRpc.submitItems",
                        "Kernel omitted a TaskItem submission result",
                        null
                );
            }
            switch (appended.status()) {
                case APPENDED -> {
                    // Continue validating the bounded submission.
                }
                case NOT_FOUND -> throw new ServerException(
                        ServerErrorCode.TASK_NOT_FOUND,
                        "taskRpc.appendItems",
                        null,
                        null
                );
                case INVALID -> throw new ServerException(
                        ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                        "taskRpc.appendItems",
                        null,
                        null
                );
                case RETRYABLE -> throw new ServerException(
                        ServerErrorCode.TASK_DATA_UNAVAILABLE,
                        "taskRpc.appendItems",
                        null,
                        null
                );
            }
        }
    }
}
