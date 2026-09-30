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
import com.xa.mass.kernel.task.TaskRuntime.TaskItemResult;
import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.server.api.v1.contract.task.TaskItemRequest;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.task.TaskItemMapper;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared bounded admission for HTTP Call and in-process product callers.
 *
 * <p>Each call is validated and normalized on its own. Concurrent calls to one Task
 * then share one Kernel submission and one immediate Result read through the
 * per-Task {@link TaskCallSubmissionBatcher}; a call alone submits directly.</p>
 */
@Service
public final class TaskCallSubmissionService {
    /** Bound of the immutable Task descriptor cache; reaching it resets the cache. */
    static final int DESCRIPTOR_CACHE_CAPACITY = 1024;

    private final TaskCallItemSubmission taskCallSubmission;
    private final TaskResourceCatalog taskCatalog;
    private final TaskItemMapper taskItems;
    private final WorkerMatchingCatalog matching;
    private final TaskRuntime taskRuntime;
    /** Descriptors are immutable; a removed Task still fails at Kernel submission. */
    private final ConcurrentHashMap<String, TaskDescriptor> descriptors = new ConcurrentHashMap<>();
    private final TaskCallSubmissionBatcher batcher = new TaskCallSubmissionBatcher(this::submitBatch);

    public TaskCallSubmissionService(TaskCallItemSubmission taskCallSubmission,
            TaskResourceCatalog taskCatalog, TaskItemMapper taskItems,
            WorkerMatchingCatalog matching, TaskRuntime taskRuntime) {
        this.taskCallSubmission = taskCallSubmission;
        this.taskCatalog = taskCatalog;
        this.taskItems = taskItems;
        this.matching = matching;
        this.taskRuntime = taskRuntime;
    }

    /** One submitted call: its message IDs, Results already observed and their read start. */
    record SubmittedCall(List<String> messageIds, Map<String, TaskItemResult> observed, long observationStarted) {
    }

    public List<String> submit(String taskId, List<TaskItemRequest> items) {
        return submit(taskId, items, false).messageIds();
    }

    /** Submits a synchronous Item Call and reads its already stored Results once. */
    SubmittedCall submitCall(String taskId, List<TaskItemRequest> items) {
        return submit(taskId, items, true);
    }

    private SubmittedCall submit(String taskId, List<TaskItemRequest> items, boolean observe) {
        if (taskId == null || taskId.isBlank()) {
            throw invalid("taskId must be non-blank");
        }
        // Capture every input, including duplicates, before any Owner call.
        List<WorkerQuery> selectors = captureSelectors(items);
        TaskDescriptor descriptor = requireCallableTask(taskId);
        long createdAtMillis = taskItems.nowMillis();
        var latest = new LinkedHashMap<String, TaskItem>();
        try {
            for (int i = 0; i < items.size(); i++) {
                WorkerQuery selector = matching.normalizeQuery(descriptor.workerGroupId(), selectors.get(i));
                TaskItemRequest item = items.get(i);
                latest.put(item.messageId(), taskItems.callItem(item, createdAtMillis, selector));
            }
        } catch (IllegalArgumentException error) {
            throw invalid(error.getMessage());
        } catch (RuntimeException error) {
            throw new ServerException(ServerErrorCode.TASK_DATA_UNAVAILABLE, "taskRpc.prepareQuery", null, error);
        }
        // No mutation until every original input has passed Matching admission.
        List<String> messageIds = List.copyOf(latest.keySet());
        TaskCallSubmissionBatcher.BatchOutcome outcome;
        try {
            outcome = batcher.submit(taskId, List.copyOf(latest.values()), observe);
        } catch (RuntimeException error) {
            throw new ServerException(
                    ServerErrorCode.TASK_DATA_UNAVAILABLE,
                    "taskRpc.submitItems",
                    null,
                    error
            );
        }
        TaskCallSubmissionResult submission = outcome.submission();
        if (submission == null) {
            throw new ServerException(
                    ServerErrorCode.TASK_DATA_UNAVAILABLE,
                    "taskRpc.submitItems",
                    null,
                    null
            );
        }
        requireAcceptedSubmission(submission, messageIds);

        var observed = new LinkedHashMap<String, TaskItemResult>();
        messageIds.forEach(messageId -> {
            TaskItemResult result = outcome.observed().get(messageId);
            if (result != null) {
                observed.put(messageId, result);
            }
        });
        return new SubmittedCall(messageIds, observed, outcome.observationStarted());
    }

    /** One Kernel submission and, when requested, one Result read for a whole batch. */
    private TaskCallSubmissionBatcher.BatchOutcome submitBatch(String taskId, List<TaskItem> items, boolean observe) {
        List<String> messageIds = items.stream().map(TaskItem::messageId).toList();
        TaskCallSubmissionResult submission = null;
        long submissionStarted = TaskRpcStageEvent.start();
        boolean submitted = false;
        try {
            submission = taskCallSubmission.submit(taskId, items);
            submitted = submission != null && submission.status() == TaskCallSubmissionStatus.SUBMITTED;
        } finally {
            TaskRpcStageEvent.items(submissionStarted, "SUBMISSION", taskId, messageIds, submitted ? messageIds.size() : 0, !submitted);
        }
        long observationStarted = TaskRpcStageEvent.start();
        if (!observe || !submitted) {
            return new TaskCallSubmissionBatcher.BatchOutcome(submission, Map.of(), observationStarted);
        }
        Map<String, TaskItemResult> observed;
        try {
            observed = taskRuntime.loadTaskItemResults(taskId, messageIds);
        } catch (RuntimeException ignored) {
            observed = Map.of();
        }
        TaskRpcStageEvent.batch(observationStarted, "IMMEDIATE_PROBE", messageIds.size(), observed.size(), false);
        return new TaskCallSubmissionBatcher.BatchOutcome(submission, observed, observationStarted);
    }

    private TaskDescriptor requireCallableTask(String taskId) {
        TaskDescriptor descriptor = descriptors.get(taskId);
        if (descriptor == null) {
            descriptor = loadDescriptor(taskId);
        }
        if (descriptor.idleDisposition()
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

    private TaskDescriptor loadDescriptor(String taskId) {
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
        if (descriptors.size() >= DESCRIPTOR_CACHE_CAPACITY) {
            descriptors.clear();
        }
        descriptors.put(taskId, descriptor);
        return descriptor;
    }

    private static List<WorkerQuery> captureSelectors(
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
        var selectors = new ArrayList<WorkerQuery>(items.size());
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
                if (item.workerSelector() == null) throw new IllegalArgumentException("WorkerGroup Task Call requires workerSelector");
                selectors.add(item.workerSelector());
            } catch (IllegalArgumentException error) {
                throw invalid(error.getMessage());
            }
        }
        return List.copyOf(selectors);
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
