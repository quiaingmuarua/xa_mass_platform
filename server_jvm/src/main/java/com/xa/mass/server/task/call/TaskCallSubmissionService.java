package com.xa.mass.server.task.call;

import org.springframework.stereotype.Service;
import com.xa.mass.kernel.task.TaskCallItemSubmission;
import com.xa.mass.kernel.task.TaskCallItemSubmission.TaskCallSubmissionStatus;
import com.xa.mass.kernel.task.TaskCallItemSubmission.TaskCallSubmissionResult;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendResult;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import com.xa.mass.server.api.v1.contract.task.TaskItemRequest;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.task.TaskItemMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared bounded admission for HTTP Call and in-process product callers. */
@Service
public final class TaskCallSubmissionService {
    private final TaskCallItemSubmission taskCallSubmission;
    private final TaskResourceCatalog taskCatalog;
    private final TaskItemMapper taskItems;

    public TaskCallSubmissionService(TaskCallItemSubmission taskCallSubmission,
            TaskResourceCatalog taskCatalog, TaskItemMapper taskItems) {
        this.taskCallSubmission = taskCallSubmission;
        this.taskCatalog = taskCatalog;
        this.taskItems = taskItems;
    }

    public List<String> submit(String taskId, List<TaskItemRequest> items) {
        if (taskId == null || taskId.isBlank()) {
            throw invalid("taskId must be non-blank");
        }
        // Validate the complete input before any Owner call, including overwritten duplicates.
        LinkedHashMap<String, TaskItemRequest> requestedItems = latestItems(items);
        requireCallableTask(taskId);
        List<String> messageIds = List.copyOf(requestedItems.keySet());
        long createdAtMillis = taskItems.nowMillis();
        var submittedItems = new ArrayList<TaskItem>(requestedItems.size());
        try {
            requestedItems.values().forEach(item -> submittedItems.add(
                    taskItems.onDemandItem(
                            item,
                            createdAtMillis,
                            TaskItemWorkerSelector.targetWorkerIds(
                                    item.workerSelector()
                            )
                    )
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
        long submissionStarted = TaskRpcStageEvent.start();
        boolean submitted = false;
        try {
            submission = taskCallSubmission.submit(taskId, submittedItems);
            submitted = submission != null && submission.status() == TaskCallSubmissionStatus.SUBMITTED;
        } catch (RuntimeException error) {
            throw new ServerException(
                    ServerErrorCode.TASK_DATA_UNAVAILABLE,
                    "taskRpc.submitItems",
                    null,
                    error
            );
        } finally {
            TaskRpcStageEvent.items(submissionStarted, "SUBMISSION", taskId, messageIds, submitted ? messageIds.size() : 0, !submitted);
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

        return messageIds;
    }

    private TaskDescriptor requireCallableTask(String taskId) {
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
        return descriptor;
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
            if (item.messageId() == null || item.messageId().isBlank()
                    || item.eventCode() == null || item.eventCode().isBlank()
                    || item.payload() == null || item.priority() < 0 || item.priority() > 10
                    || (item.ttlMillis() != null && item.ttlMillis() <= 0)) {
                throw invalid("Invalid TaskItem fields");
            }
            try {
                if (item.workerSelector() == null) throw new IllegalArgumentException("workerSelector is required");
                TaskItemWorkerSelector.targetWorkerIds(item.workerSelector());
            } catch (IllegalArgumentException error) {
                throw invalid(error.getMessage());
            }
            latest.put(item.messageId(), item);
        }
        return latest;
    }

    private static ServerException invalid(String message) {
        return new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                "taskRpc.mapItems", message, null);
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
