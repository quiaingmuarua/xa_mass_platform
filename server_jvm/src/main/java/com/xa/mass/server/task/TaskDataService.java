package com.xa.mass.server.task;

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
import org.springframework.stereotype.Service;

@Service
public final class TaskDataService {

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
            TaskItemOutcomeProperties outcomes
    ) {
        this.taskRuntime = taskRuntime;
        this.taskCatalog = taskCatalog;
        this.taskItems = taskItems;
        this.itemScores = itemScores;
        this.outcomes = outcomes;
    }

    public Map<String, ActionOutcome> appendFiniteTaskItems(
            String taskId,
            List<TaskItemRequest> requestedItems
    ) {
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
                    validItems.add(taskItems.finiteItem(
                            entry.getValue(),
                            createdAtMillis
                    ));
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
        return descriptor.workerAllocationMechanism()
                == WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE
                && descriptor.idleDisposition()
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
