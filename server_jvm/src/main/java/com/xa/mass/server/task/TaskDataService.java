package com.xa.mass.server.task;

import org.springframework.stereotype.Service;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendStatus;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.api.v1.contract.task.TaskItemRequest;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse;
import com.xa.mass.server.api.v1.contract.task.TaskItemStateResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public final class TaskDataService {

    private final com.xa.mass.workermatching.WorkerMatchingCatalog matchingCatalog;
    private final TaskRuntime taskRuntime;
    private final TaskResourceCatalog taskCatalog;
    private final TaskItemMapper taskItems;
    private final TaskItemScoreBandCore itemScores;
    private final TaskItemOutcomeProperties outcomes;

    public TaskDataService(
            TaskRuntime taskRuntime,
            TaskResourceCatalog taskCatalog,
            TaskItemMapper taskItems,
            TaskItemScoreBandCore itemScores,
            TaskItemOutcomeProperties outcomes,
            com.xa.mass.workermatching.WorkerMatchingCatalog matchingCatalog
    ) {
        this.taskRuntime = taskRuntime;
        this.taskCatalog = taskCatalog;
        this.taskItems = taskItems;
        this.itemScores = itemScores;
        this.outcomes = outcomes;
        this.matchingCatalog = matchingCatalog;
    }

    public Map<String, ActionOutcome> appendFiniteTaskItems(
            String taskId,
            List<TaskItemRequest> requestedItems
    ) {
        if (taskId == null || taskId.isBlank() || requestedItems == null || requestedItems.isEmpty() || requestedItems.size() > 100)
            throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST, "taskData.appendItems", "Expected taskId and 1..100 Items", null);
        for (TaskItemRequest item : requestedItems) {
            if (item == null || item.messageId() == null || item.messageId().isBlank() || item.eventCode() == null
                    || item.eventCode().isBlank() || item.payload() == null || item.priority() < 0 || item.priority() > 10
                    || item.ttlMillis() != null && item.ttlMillis() <= 0)
                throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST, "taskData.appendItems", "Invalid TaskItem fields", null);
        }
        return appendResponse(appendItems(
                taskId,
                requestedItems
        ));
    }

    private Map<String, TaskItemAppendResult> appendItems(
            String taskId,
            List<TaskItemRequest> requestedItems
    ) {
        try {
            LinkedHashMap<String, TaskItemRequest> latest =
                    latestItems(requestedItems);
            TaskDescriptor descriptor = taskCatalog
                    .loadTaskAllocationDescriptors(List.of(taskId))
                    .get(taskId);
            if (descriptor == null) {
                throw new ServerException(
                        ServerErrorCode.TASK_NOT_FOUND,
                        "taskData.appendItems",
                        null,
                        null
                );
            }
            if (!isPublicFiniteTask(descriptor)) {
                throw new ServerException(
                        ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED,
                        "taskData.appendItems",
                        "Task does not support ordinary Item append",
                        null
                );
            }

            if (descriptor.workerAllocationMechanism() == WorkerAllocationMechanism.INDEXED_TASK
                    && matchingCatalog.prepareTaskQuery(taskId, descriptor.workerGroupId()) == null) {
                throw new ServerException(ServerErrorCode.TASK_DATA_UNAVAILABLE, "taskData.appendItems",
                        "Task Rule binding is unavailable", null);
            }
            var validItems = new ArrayList<TaskItem>();
            var results = new LinkedHashMap<
                    String,
                    TaskItemAppendResult
                    >();
            long createdAtMillis = taskItems.nowMillis();
            for (Map.Entry<String, TaskItemRequest> entry
                    : latest.entrySet()) {
                try {
                    if (descriptor.workerAllocationMechanism() == WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
                            && entry.getValue().workerSelector() != null) {
                        throw new IllegalArgumentException("DSL TaskItem forbids workerSelector");
                    }
                    TaskItem item = taskItems.finiteItem(entry.getValue(), createdAtMillis);
                    if (descriptor.workerAllocationMechanism() == WorkerAllocationMechanism.INDEXED_TASK
                            || !item.workerSelector().isAny() && !item.workerSelector().hasExplicitWorkerIds()) {
                        matchingCatalog.validateWorkerSelector(descriptor.workerGroupId(), item.workerSelector());
                    }
                    validItems.add(item);
                } catch (IllegalArgumentException error) {
                    results.put(
                            entry.getKey(),
                            new TaskItemAppendResult(
                                    TaskItemAppendStatus.INVALID,
                                    "TaskItem is invalid"
                            )
                    );
                }
            }
            if (!validItems.isEmpty()) {
                results.putAll(taskRuntime.appendItems(taskId, validItems));
            }
            return orderedResults(latest.keySet(), results);
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ServerException(
                    ServerErrorCode.TASK_DATA_UNAVAILABLE,
                    "taskData.appendItems",
                    null,
                    error
            );
        }
    }

    public Map<String, TaskItemResultResponse> loadTaskItemResults(
            String taskId,
            List<String> messageIds
    ) {
        if (taskId == null || taskId.isBlank() || messageIds == null || messageIds.isEmpty()
                || messageIds.size() > 1000
                || messageIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                    "taskData.loadResults", "Expected taskId and 1..1000 non-blank messageIds", null);
        }
        try {
            List<String> uniqueIds = new ArrayList<>(
                    new LinkedHashSet<>(messageIds)
            );
            requireQueryableTask(taskId, "taskData.loadResults");
            return TaskItemResultResponse.fromObservedResults(
                    uniqueIds,
                    taskRuntime.loadTaskItemResults(taskId, uniqueIds)
            );
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ServerException(
                    ServerErrorCode.TASK_DATA_UNAVAILABLE,
                    "taskData.loadResults",
                    null,
                    error
            );
        }
    }

    public Map<String, TaskItemStateResponse> loadTaskItemStates(
            String taskId,
            List<String> messageIds
    ) {
        if (taskId == null || taskId.isBlank() || messageIds == null || messageIds.isEmpty() || messageIds.size() > 100
                || messageIds.stream().anyMatch(id -> id == null || id.isBlank()))
            throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST, "taskData.loadStates", "Expected taskId and 1..100 IDs", null);
        try {
            List<String> uniqueIds = new ArrayList<>(new LinkedHashSet<>(messageIds));
            requireQueryableTask(taskId, "taskData.loadStates");
            var states = itemScores.getItemScoreStates(taskId, uniqueIds);
            var response = new LinkedHashMap<String, TaskItemStateResponse>();
            for (String messageId : uniqueIds) {
                var state = states.get(messageId);
                response.put(messageId, state == null ? null : new TaskItemStateResponse(
                        state.band().wireValue(),
                        state.tag(),
                        state.timeMillis(),
                        outcomes.outcomeName(state.tag())
                ));
            }
            return Collections.unmodifiableMap(response);
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ServerException(
                    ServerErrorCode.TASK_DATA_UNAVAILABLE,
                    "taskData.loadStates",
                    null,
                    error
            );
        }
    }

    private void requireQueryableTask(String taskId, String operation) {
        TaskDescriptor descriptor = taskCatalog
                .loadTaskAllocationDescriptors(List.of(taskId)).get(taskId);
        if (descriptor == null) {
            throw new ServerException(ServerErrorCode.TASK_NOT_FOUND, operation, null, null);
        }
        if (!isPublicFiniteTask(descriptor) && !isManagedCallTask(descriptor)) {
            throw new ServerException(
                    ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED,
                    operation,
                    "Task does not support Item queries",
                    null
            );
        }
    }

    private static boolean isPublicFiniteTask(TaskDescriptor descriptor) {
        return descriptor.idleDisposition()
                == TaskRuntime.TaskIdleDisposition.CLOSE_WHEN_IDLE;
    }

    private static boolean isManagedCallTask(TaskDescriptor descriptor) {
        return descriptor.workerAllocationMechanism()
                == WorkerAllocationMechanism.ON_DEMAND_ITEM_RULE
                && descriptor.idleDisposition()
                == TaskRuntime.TaskIdleDisposition.PARK_WHEN_IDLE;
    }

    private static LinkedHashMap<String, TaskItemRequest> latestItems(
            List<TaskItemRequest> items
    ) {
        var latest = new LinkedHashMap<String, TaskItemRequest>();
        for (TaskItemRequest item : items) {
            if (item == null) {
                throw new ServerException(
                        ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                        "taskData.appendItems",
                        "TaskItem must be present",
                        null
                );
            }
            latest.put(item.messageId(), item);
        }
        return latest;
    }

    private static Map<String, TaskItemAppendResult> orderedResults(
            Set<String> messageIds,
            Map<String, TaskItemAppendResult> results
    ) {
        var ordered = new LinkedHashMap<
                String,
                TaskItemAppendResult
                >();
        messageIds.forEach(messageId ->
                ordered.put(messageId, results.get(messageId)));
        return ordered;
    }

    private static Map<String, ActionOutcome> appendResponse(
            Map<String, TaskItemAppendResult> appended
    ) {
        var results = new LinkedHashMap<String, ActionOutcome>();
        appended.forEach((messageId, result) -> {
            if (result == null) {
                throw new ServerException(
                        ServerErrorCode.TASK_DATA_UNAVAILABLE,
                        "taskData.appendItems",
                        null,
                        null
                );
            }
            ActionOutcome outcome = switch (result.status()) {
                case APPENDED -> ActionOutcome.applied();
                case INVALID -> ActionOutcome.rejected(
                        ServerErrorCode.INVALID_TASK_DATA_REQUEST
                );
                case NOT_FOUND -> ActionOutcome.rejected(
                        ServerErrorCode.TASK_NOT_FOUND
                );
                case RETRYABLE -> ActionOutcome.rejected(
                        ServerErrorCode.TASK_DATA_UNAVAILABLE
                );
            };
            results.put(messageId, outcome);
        });
        return Collections.unmodifiableMap(results);
    }
}
