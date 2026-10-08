package com.xa.mass.server.task;

import com.xa.mass.kernel.assignment.WorkerQuery;

import org.springframework.stereotype.Service;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendStatus;
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
    private final TaskScoreBandCore taskScores;
    private final OperationGuard operations;

    public TaskDataService(
            TaskRuntime taskRuntime,
            TaskResourceCatalog taskCatalog,
            TaskItemMapper taskItems,
            TaskItemScoreBandCore itemScores,
            TaskItemOutcomeProperties outcomes,
            com.xa.mass.workermatching.WorkerMatchingCatalog matchingCatalog,
            TaskScoreBandCore taskScores,
            OperationGuard operations
    ) {
        this.taskRuntime = taskRuntime;
        this.taskCatalog = taskCatalog;
        this.taskItems = taskItems;
        this.itemScores = itemScores;
        this.outcomes = outcomes;
        this.matchingCatalog = matchingCatalog;
        this.taskScores = taskScores;
        this.operations = operations;
    }

    public Map<String, ActionOutcome> appendFiniteTaskItems(
            String taskId,
            List<TaskItemRequest> requestedItems
    ) {
        return operations.taskMutation(taskId, () -> appendFiniteTaskItemsObserved(taskId, requestedItems));
    }

    private Map<String, ActionOutcome> appendFiniteTaskItemsObserved(String taskId, List<TaskItemRequest> requestedItems) {
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

            var validItems = new ArrayList<TaskItem>();
            var results = new LinkedHashMap<
                    String,
                    TaskItemAppendResult
                    >();
            long createdAtMillis = taskItems.nowMillis();
            for (Map.Entry<String, TaskItemRequest> entry
                    : latest.entrySet()) {
                try {
                    var supplied = entry.getValue().workerSelector();
                    if (supplied == null) throw new IllegalArgumentException("workerSelector is required");
                    var normalized = matchingCatalog.normalizeQuery(descriptor.workerGroupId(), supplied);
                    TaskItem item = taskItems.finiteItem(entry.getValue(), createdAtMillis, normalized);
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

    /** Admitted project roots are supplied by the calling query service. */
    public Map<String, TaskItemScoreBandCore.TaskItemScoreCounts> observeItemScoreCounts(List<String> taskIds) {
        try { return itemScores.observeItemScoreCounts(taskIds); }
        catch (RuntimeException error) {
            throw new ServerException(ServerErrorCode.TASK_DATA_UNAVAILABLE, "taskData.observeCounts", null, error);
        }
    }

    /** Caller owns a whole-file Task mutation; nested batches retain that same admission. */
    public Map<String, ActionOutcome> importFiniteTaskItems(String taskId, List<TaskItemRequest> requestedItems) {
        return operations.taskMutation(taskId, () -> {
            if (requestedItems == null || requestedItems.isEmpty() || requestedItems.size() > 100
                    || requestedItems.stream().anyMatch(item -> item == null || item.messageId() == null || item.messageId().isBlank()
                    || item.eventCode() == null || item.eventCode().isBlank() || item.payload() == null || item.workerSelector() == null
                    || item.priority() < 0 || item.priority() > 10 || item.ttlMillis() != null))
                throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST, "taskData.importItems", "Expected 1..100 Items using default TTL", null);
            try {
                var descriptor = taskCatalog.loadTaskAllocationDescriptors(List.of(taskId)).get(taskId);
                if (descriptor == null || !isPublicFiniteTask(descriptor))
                    throw new ServerException(ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED, "taskData.importItems", null, null);
                var taskState = taskScores.getScoreStates(List.of(taskId)).get(taskId);
                if (taskState == null) throw new IllegalStateException("Task state is unavailable");
                if (taskState.band() != TaskScoreBandCore.TaskScoreBand.PRE_REVIEW)
                    throw new ServerException(ServerErrorCode.TASK_STATE_CONFLICT, "taskData.importItems", "Task must await review", null);
                var inputs = latestItems(requestedItems);
                if (inputs.size() != requestedItems.size()) throw new IllegalArgumentException("Duplicate input identity");
                var ids = List.copyOf(inputs.keySet());
                var existing = taskRuntime.loadTaskItems(taskId, ids);
                var states = itemScores.getItemScoreStates(taskId, ids);
                var writes = new ArrayList<TaskItem>();
                var result = new LinkedHashMap<String, ActionOutcome>();
                long now = taskItems.nowMillis();
                for (var input : inputs.values()) {
                    var normalized = matchingCatalog.normalizeQuery(descriptor.workerGroupId(), input.workerSelector());
                    var item = existing.get(input.messageId());
                    var state = states.get(input.messageId());
                    if (item == null) {
                        if (state != null) throw new IllegalStateException("Item Score has no execution data");
                        writes.add(taskItems.finiteItem(input, now, normalized));
                    } else {
                        if (!item.eventCode().equals(input.eventCode()) || !sameJson(item.payload(), input.payload())
                                || item.priority() != input.priority()
                                || !item.workerSelector().executorName().equals(normalized.executorName())
                                || !sameJson(item.workerSelector().input(), normalized.input()))
                            throw new ServerException(ServerErrorCode.TASK_STATE_CONFLICT, "taskData.importItems", "Existing Item content differs", null);
                        if (state != null) result.put(input.messageId(), ActionOutcome.unchanged());
                        else writes.add(item); // Explicit re-import reuses the original absolute expiry and creation time.
                    }
                }
                if (!writes.isEmpty()) result.putAll(appendResponse(taskRuntime.appendItems(taskId, writes)));
                return Collections.unmodifiableMap(result);
            } catch (ServerException known) { throw known;
            } catch (RuntimeException error) {
                throw new ServerException(ServerErrorCode.TASK_DATA_UNAVAILABLE, "taskData.importItems", null, error);
            }
        });
    }

    private static boolean sameJson(Object left, Object right) {
        // Stored JSON can materialize an integral value as Integer rather than the caller's Long.
        return com.xa.mass.workerdelivery.json.Jsons.parseObject(com.xa.mass.workerdelivery.json.Jsons.toJson(Collections.singletonMap("value", left)))
                .equals(com.xa.mass.workerdelivery.json.Jsons.parseObject(com.xa.mass.workerdelivery.json.Jsons.toJson(Collections.singletonMap("value", right))));
    }

    public Map<String, TaskItem> loadTaskItems(String taskId, List<String> messageIds) {
        if (taskId == null || taskId.isBlank() || messageIds == null || messageIds.isEmpty() || messageIds.size() > 100
                || messageIds.stream().anyMatch(id -> id == null || id.isBlank()))
            throw new ServerException(ServerErrorCode.INVALID_TASK_DATA_REQUEST, "taskData.loadItems", "Expected 1..100 IDs", null);
        try {
            requireQueryableTask(taskId, "taskData.loadItems");
            return taskRuntime.loadTaskItems(taskId, messageIds);
        } catch (ServerException known) { throw known;
        } catch (RuntimeException error) {
            throw new ServerException(ServerErrorCode.TASK_DATA_UNAVAILABLE, "taskData.loadItems", null, error);
        }
    }

    public ResultPreview previewTaskResults(String taskId) {
        try {
            requireQueryableTask(taskId, "taskData.previewResults");
            var page = taskRuntime.scanTaskItemResults(taskId, "0", 100);
            var ids = page.results().keySet().stream().limit(100).toList();
            var items = ids.isEmpty() ? Map.<String, TaskItem>of() : taskRuntime.loadTaskItems(taskId, ids);
            var results = TaskItemResultResponse.fromObservedResults(ids, page.results());
            return new ResultPreview(ids.stream().map(id -> new ResultEntry(id, items.get(id), results.get(id))).toList(),
                    !"0".equals(page.nextCursor()) || page.results().size() > 100);
        } catch (ServerException error) { throw error;
        } catch (RuntimeException error) {
            throw new ServerException(ServerErrorCode.TASK_DATA_UNAVAILABLE, "taskData.previewResults", null, error);
        }
    }

    public record ResultEntry(String messageId, @org.jspecify.annotations.Nullable TaskItem item, TaskItemResultResponse result) {}
    public record ResultPreview(List<ResultEntry> results, boolean truncated) {}

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
        return descriptor.idleDisposition()
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
