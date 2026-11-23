package com.xa.mass.server.task;

import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskDataPhase;
import com.xa.mass.kernel.task.TaskRuntime.TaskDataState;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendStatus;
import com.xa.mass.server.api.v1.contract.ActionOutcome;
import com.xa.mass.server.api.v1.contract.task.TaskItemRequest;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse;
import com.xa.mass.server.api.v1.contract.task.TaskItemStateResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.task.access.TaskCaller;
import com.xa.mass.server.task.result.TaskResultsExportService;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
public final class TaskDataService {

    private final WorkerMatchingCatalog matchingCatalog;
    private final TaskRuntime taskRuntime;
    private final TaskResourceCatalog taskCatalog;
    private final TaskItemMapper taskItems;
    private final TaskItemScoreBandCore itemScores;
    private final TaskItemOutcomeProperties outcomes;
    private final TaskScoreBandCore taskScores;
    private final OperationGuard operations;
    private final TaskResultsExportService results;

    public TaskDataService(
            TaskRuntime taskRuntime, TaskResourceCatalog taskCatalog, TaskItemMapper taskItems,
            TaskItemScoreBandCore itemScores, TaskItemOutcomeProperties outcomes,
            WorkerMatchingCatalog matchingCatalog, TaskScoreBandCore taskScores,
            OperationGuard operations, TaskResultsExportService results) {
        this.taskRuntime = taskRuntime;
        this.taskCatalog = taskCatalog;
        this.taskItems = taskItems;
        this.itemScores = itemScores;
        this.outcomes = outcomes;
        this.matchingCatalog = matchingCatalog;
        this.taskScores = taskScores;
        this.operations = operations;
        this.results = results;
    }

    public Map<String, ActionOutcome> appendFiniteTaskItems(
            TaskCaller caller, String taskId, List<TaskItemRequest> requestedItems) {
        return operations.taskMutation(taskId, () -> appendFiniteTaskItemsObserved(caller, taskId, requestedItems));
    }

    private Map<String, ActionOutcome> appendFiniteTaskItemsObserved(
            TaskCaller caller, String taskId, List<TaskItemRequest> requestedItems) {
        String operation = "taskData.appendItems";
        if (taskId == null || taskId.isBlank()
                || requestedItems == null || requestedItems.isEmpty() || requestedItems.size() > 100) {
            throw invalid(operation, "Expected taskId and 1..100 Items");
        }
        if (requestedItems.stream().anyMatch(item -> isInvalidItem(item, true, false))) {
            throw invalid(operation, "Invalid TaskItem fields");
        }
        return appendResponse(appendItems(caller, taskId, requestedItems));
    }

    private Map<String, TaskItemAppendResult> appendItems(
            TaskCaller caller, String taskId, List<TaskItemRequest> requestedItems) {
        String operation = "taskData.appendItems";
        return withTaskData(operation, null, () -> {
            LinkedHashMap<String, TaskItemRequest> latest = latestItems(requestedItems);
            TaskDescriptor descriptor = loadDescriptor(taskId, operation);
            caller.requireInput(descriptor, TaskCaller.RAW_INPUT);
            if (!isPublicFiniteTask(descriptor)) {
                throw failure(ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED, operation,
                        "Task does not support ordinary Item append");
            }
            var taskState = taskScores.getScoreStates(List.of(taskId)).get(taskId);
            if (taskState == null) {
                throw failure(ServerErrorCode.TASK_NOT_FOUND, operation, null);
            }
            if (taskState.band() == TaskScoreBand.TERMINAL) {
                throw failure(ServerErrorCode.TASK_STATE_CONFLICT, operation, "Task is closed");
            }
            var validItems = new ArrayList<TaskItem>();
            var appended = new LinkedHashMap<String, TaskItemAppendResult>();
            long createdAtMillis = taskItems.nowMillis();
            for (var entry : latest.entrySet()) {
                try {
                    var supplied = entry.getValue().workerSelector();
                    if (supplied == null) {
                        throw new IllegalArgumentException("workerSelector is required");
                    }
                    var normalized = matchingCatalog.normalizeQuery(descriptor.workerGroupId(), supplied);
                    validItems.add(taskItems.finiteItem(entry.getValue(), createdAtMillis, normalized));
                } catch (IllegalArgumentException error) {
                    appended.put(entry.getKey(), new TaskItemAppendResult(TaskItemAppendStatus.INVALID, "TaskItem is invalid"));
                }
            }
            if (!validItems.isEmpty()) {
                appended.putAll(taskRuntime.appendItems(taskId, validItems));
            }
            return orderedResults(latest.keySet(), appended);
        });
    }

    public TaskDescriptor requireFiniteInputTask(TaskCaller caller, String inputOwner, String taskId) {
        String operation = "taskData.inputTask";
        return withTaskData(operation, null, () -> {
            TaskDescriptor task = loadDescriptor(taskId, operation);
            caller.requireInput(task, inputOwner);
            if (!isPublicFiniteTask(task)) {
                throw failure(ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED, operation, null);
            }
            requireActiveDataState(taskId, operation);
            return task;
        });
    }

    /** Scans a stable PRE_REVIEW input under the shared mutation gate, checking every Item has its Score. */
    public long visitFiniteTaskInputs(
            TaskCaller caller, String inputOwner, String taskId, Consumer<TaskItem> visitor) {
        String operation = "taskData.visitInputs";
        return operations.taskMutation(taskId, () -> withTaskData(operation, "输入尚未完整确认，请核对或重传导入", () -> {
            TaskDescriptor task = loadDescriptor(taskId, operation);
            caller.requireInput(task, inputOwner);
            var state = taskScores.getScoreStates(List.of(taskId)).get(taskId);
            if (!isPublicFiniteTask(task) || state == null || state.band() != TaskScoreBand.PRE_REVIEW) {
                throw failure(ServerErrorCode.TASK_STATE_CONFLICT, operation, "Only PRE_REVIEW input can be quoted");
            }
            var phase = taskRuntime.loadTaskDataStates(List.of(taskId)).get(taskId);
            if (phase == null || phase.phase() != TaskDataPhase.OPEN) {
                throw new IllegalStateException("Task data is not open");
            }
            var seen = new HashSet<String>();
            String cursor = "0";
            do {
                var page = taskRuntime.scanTaskItems(taskId, cursor, 1000);
                var entries = page.items().entrySet().stream().filter(e -> seen.add(e.getKey())).toList();
                for (int from = 0; from < entries.size(); from += 100) {
                    var batch = entries.subList(from, Math.min(from + 100, entries.size()));
                    var scores = itemScores.getItemScoreStates(taskId, batch.stream().map(Map.Entry::getKey).toList());
                    for (var entry : batch) {
                        if (!entry.getKey().equals(entry.getValue().messageId()) || scores.get(entry.getKey()) == null) {
                            throw new IllegalStateException("Input and Item Score differ; reconcile the import before starting");
                        }
                        visitor.accept(entry.getValue());
                    }
                }
                cursor = page.nextCursor();
            } while (!cursor.equals("0"));
            var counts = itemScores.observeItemScoreCounts(List.of(taskId)).get(taskId);
            if (counts == null || counts.total() != seen.size()) {
                throw new IllegalStateException("Input and Item Score counts differ");
            }
            return (long) seen.size();
        }));
    }

    /** Consumer access is bound to the application's immutable input ownership. */
    public Map<String, TaskItemResultResponse> takeTaskItemResults(
            TaskCaller caller, String inputOwner, String taskId, List<String> messageIds) {
        String operation = "taskData.takeResults";
        requireMessageIdBatch(taskId, messageIds, 1000, operation, "Expected taskId and 1..1000 non-blank messageIds");
        return withTaskData(operation, "Result consumption is unconfirmed", () -> {
            TaskDescriptor task = loadDescriptor(taskId, operation);
            caller.requireInput(task, inputOwner);
            if (!isManagedCallTask(task)) {
                throw failure(ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED, operation,
                        "Only RPC Tasks support Result consumption");
            }
            var ids = List.copyOf(new LinkedHashSet<>(messageIds));
            return TaskItemResultResponse.fromObservedResults(ids, taskRuntime.takeTaskItemResults(taskId, ids));
        });
    }

    public Map<String, TaskItemResultResponse> loadTaskItemResults(
            TaskCaller caller, String taskId, List<String> messageIds) {
        String operation = "taskData.loadResults";
        requireMessageIdBatch(taskId, messageIds, 1000, operation, "Expected taskId and 1..1000 non-blank messageIds");
        return withTaskData(operation, null, () -> {
            List<String> uniqueIds = List.copyOf(new LinkedHashSet<>(messageIds));
            TaskDataState before = requireQueryableTask(caller, taskId, operation);
            requireRedisPayloads(before);
            var loaded = TaskItemResultResponse.fromObservedResults(uniqueIds, taskRuntime.loadTaskItemResults(taskId, uniqueIds));
            requireUnchanged(taskId, before);
            return loaded;
        });
    }

    public Map<String, TaskItemStateResponse> loadTaskItemStates(
            TaskCaller caller, String taskId, List<String> messageIds) {
        String operation = "taskData.loadStates";
        requireMessageIdBatch(taskId, messageIds, 100, operation, "Expected taskId and 1..100 IDs");
        return withTaskData(operation, null, () -> {
            List<String> uniqueIds = List.copyOf(new LinkedHashSet<>(messageIds));
            TaskDataState before = requireQueryableTask(caller, taskId, operation);
            var states = itemScores.getItemScoreStates(taskId, uniqueIds);
            var response = new LinkedHashMap<String, TaskItemStateResponse>();
            for (String messageId : uniqueIds) {
                var state = states.get(messageId);
                response.put(messageId, state == null ? null : new TaskItemStateResponse(
                        state.band().wireValue(), state.tag(), state.timeMillis(), outcomes.outcomeName(state.tag())));
            }
            requireUnchanged(taskId, before);
            return Collections.unmodifiableMap(response);
        });
    }

    /** Caller owns a whole-file Task mutation; nested batches retain that same admission. */
    public Map<String, ActionOutcome> importFiniteTaskItems(
            TaskCaller caller, String inputOwner, String taskId, List<TaskItemRequest> requestedItems) {
        String operation = "taskData.importItems";
        return operations.taskMutation(taskId, () -> {
            if (requestedItems == null || requestedItems.isEmpty() || requestedItems.size() > 100
                    || requestedItems.stream().anyMatch(item -> isInvalidItem(item, false, true))) {
                throw invalid(operation, "Expected 1..100 Items using default TTL");
            }
            return withTaskData(operation, null, () -> {
                TaskDescriptor descriptor = loadDescriptor(taskId, operation);
                caller.requireInput(descriptor, inputOwner);
                if (!isPublicFiniteTask(descriptor)) {
                    throw failure(ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED, operation, null);
                }
                var taskState = taskScores.getScoreStates(List.of(taskId)).get(taskId);
                if (taskState == null) {
                    throw new IllegalStateException("Task state is unavailable");
                }
                if (taskState.band() != TaskScoreBand.PRE_REVIEW) {
                    throw failure(ServerErrorCode.TASK_STATE_CONFLICT, operation, "Task must await review");
                }
                var inputs = latestItems(requestedItems);
                if (inputs.size() != requestedItems.size()) {
                    throw new IllegalArgumentException("Duplicate input identity");
                }
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
                        if (state != null) {
                            throw new IllegalStateException("Item Score has no execution data");
                        }
                        writes.add(taskItems.finiteItem(input, now, normalized));
                    } else {
                        if (!item.eventCode().equals(input.eventCode())
                                || !sameJson(item.payload(), input.payload())
                                || item.priority() != input.priority()
                                || !item.workerSelector().executorName().equals(normalized.executorName())
                                || !sameJson(item.workerSelector().input(), normalized.input())) {
                            throw failure(ServerErrorCode.TASK_STATE_CONFLICT, operation, "Existing Item content differs");
                        }
                        if (state != null) {
                            result.put(input.messageId(), ActionOutcome.unchanged());
                        } else {
                            // Explicit re-import reuses the original absolute expiry and creation time.
                            writes.add(item);
                        }
                    }
                }
                if (!writes.isEmpty()) {
                    result.putAll(appendResponse(taskRuntime.appendItems(taskId, writes)));
                }
                return Collections.unmodifiableMap(result);
            });
        });
    }

    public Map<String, TaskItem> loadTaskItems(TaskCaller caller, String taskId, List<String> messageIds) {
        String operation = "taskData.loadItems";
        requireMessageIdBatch(taskId, messageIds, 100, operation, "Expected 1..100 IDs");
        return withTaskData(operation, null, () -> {
            TaskDataState before = requireQueryableTask(caller, taskId, operation);
            requireRedisPayloads(before);
            var loaded = taskRuntime.loadTaskItems(taskId, messageIds);
            requireUnchanged(taskId, before);
            return loaded;
        });
    }

    public ResultPreview previewTaskResults(TaskCaller caller, String taskId) {
        return results.preview(caller, taskId);
    }

    public record ResultEntry(String messageId, @Nullable TaskItem item, TaskItemResultResponse result) {}
    public record ResultPreview(List<ResultEntry> results, boolean truncated) {}

    private TaskDescriptor loadDescriptor(String taskId, String operation) {
        TaskDescriptor descriptor = taskCatalog.loadTaskAllocationDescriptors(List.of(taskId)).get(taskId);
        if (descriptor == null) {
            throw failure(ServerErrorCode.TASK_NOT_FOUND, operation, null);
        }
        return descriptor;
    }

    private TaskDataState requireActiveDataState(String taskId, String operation) {
        TaskDataState dataState = taskRuntime.loadTaskDataStates(List.of(taskId)).get(taskId);
        if (dataState == null) {
            throw failure(ServerErrorCode.TASK_NOT_FOUND, operation, null);
        }
        if (dataState.phase() == TaskDataPhase.PURGING) {
            throw failure(ServerErrorCode.TASK_CLEANUP_IN_PROGRESS, operation, null);
        }
        return dataState;
    }

    private TaskDataState requireQueryableTask(TaskCaller caller, String taskId, String operation) {
        TaskDescriptor descriptor = loadDescriptor(taskId, operation);
        caller.requireTask(descriptor);
        TaskDataState dataState = requireActiveDataState(taskId, operation);
        if (!isPublicFiniteTask(descriptor) && !isManagedCallTask(descriptor)) {
            throw failure(ServerErrorCode.TASK_OPERATION_NOT_SUPPORTED, operation, "Task does not support Item queries");
        }
        return dataState;
    }

    private void requireUnchanged(String taskId, TaskDataState before) {
        if (!before.equals(taskRuntime.loadTaskDataStates(List.of(taskId)).get(taskId))) {
            throw failure(ServerErrorCode.TASK_STATE_CONFLICT, "taskData.read", "Task storage changed; refresh the view");
        }
    }

    private static void requireRedisPayloads(TaskDataState state) {
        if (state.phase() == TaskDataPhase.RELEASED) {
            throw failure(ServerErrorCode.TASK_RESULTS_ARCHIVED, "taskData.pointRead",
                    "Archived results support preview and sequential export, not point queries");
        }
    }

    private static void requireMessageIdBatch(
            String taskId, List<String> messageIds, int maxSize, String operation, String message) {
        if (taskId == null || taskId.isBlank()
                || messageIds == null || messageIds.isEmpty() || messageIds.size() > maxSize
                || messageIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw invalid(operation, message);
        }
    }

    private static boolean isInvalidItem(TaskItemRequest item, boolean allowCustomTtl, boolean requireSelector) {
        if (item == null || item.messageId() == null || item.messageId().isBlank()
                || item.eventCode() == null || item.eventCode().isBlank()
                || item.payload() == null || item.priority() < 0 || item.priority() > 10) {
            return true;
        }
        if (requireSelector && item.workerSelector() == null) {
            return true;
        }
        return allowCustomTtl ? item.ttlMillis() != null && item.ttlMillis() <= 0 : item.ttlMillis() != null;
    }

    private static <T> T withTaskData(String operation, @Nullable String unavailableMessage, Supplier<T> action) {
        try {
            return action.get();
        } catch (ServerException known) {
            throw known;
        } catch (RuntimeException error) {
            throw new ServerException(ServerErrorCode.TASK_DATA_UNAVAILABLE, operation, unavailableMessage, error);
        }
    }

    private static ServerException invalid(String operation, String message) {
        return failure(ServerErrorCode.INVALID_TASK_DATA_REQUEST, operation, message);
    }

    private static ServerException failure(ServerErrorCode code, String operation, @Nullable String message) {
        return new ServerException(code, operation, message, null);
    }

    private static boolean sameJson(Object left, Object right) {
        // Stored JSON can materialize an integral value as Integer rather than the caller's Long.
        return Jsons.parseObject(Jsons.toJson(Collections.singletonMap("value", left)))
                .equals(Jsons.parseObject(Jsons.toJson(Collections.singletonMap("value", right))));
    }

    private static boolean isPublicFiniteTask(TaskDescriptor descriptor) {
        return descriptor.idleDisposition() == TaskIdleDisposition.CLOSE_WHEN_IDLE;
    }

    private static boolean isManagedCallTask(TaskDescriptor descriptor) {
        return descriptor.idleDisposition() == TaskIdleDisposition.PARK_WHEN_IDLE;
    }

    private static LinkedHashMap<String, TaskItemRequest> latestItems(List<TaskItemRequest> items) {
        var latest = new LinkedHashMap<String, TaskItemRequest>();
        for (TaskItemRequest item : items) {
            if (item == null) {
                throw invalid("taskData.appendItems", "TaskItem must be present");
            }
            latest.put(item.messageId(), item);
        }
        return latest;
    }

    private static Map<String, TaskItemAppendResult> orderedResults(
            Set<String> messageIds, Map<String, TaskItemAppendResult> results) {
        var ordered = new LinkedHashMap<String, TaskItemAppendResult>();
        messageIds.forEach(messageId -> ordered.put(messageId, results.get(messageId)));
        return ordered;
    }

    private static Map<String, ActionOutcome> appendResponse(Map<String, TaskItemAppendResult> appended) {
        var mapped = new LinkedHashMap<String, ActionOutcome>();
        appended.forEach((messageId, result) -> {
            if (result == null) {
                throw failure(ServerErrorCode.TASK_DATA_UNAVAILABLE, "taskData.appendItems", null);
            }
            ActionOutcome outcome = switch (result.status()) {
                case APPENDED -> ActionOutcome.applied();
                case INVALID -> ActionOutcome.rejected(ServerErrorCode.INVALID_TASK_DATA_REQUEST);
                case NOT_FOUND -> ActionOutcome.rejected(ServerErrorCode.TASK_NOT_FOUND);
                case RETRYABLE -> ActionOutcome.rejected(ServerErrorCode.TASK_DATA_UNAVAILABLE);
            };
            mapped.put(messageId, outcome);
        });
        return Collections.unmodifiableMap(mapped);
    }
}
