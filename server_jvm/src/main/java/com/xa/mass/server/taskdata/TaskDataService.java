package com.xa.mass.server.taskdata;

import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskType;
import com.xa.mass.server.api.v1.model.CommandResultResponse;
import com.xa.mass.server.api.v1.model.RuntimeCommandStatus;
import com.xa.mass.server.api.v1.model.TaskItemRequest;
import com.xa.mass.server.api.v1.model.TaskItemResultsLoadResponse;
import com.xa.mass.server.api.v1.model.TaskItemsAppendRequest;
import com.xa.mass.server.api.v1.model.TaskItemsAppendResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.ArrayList;
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
    private final TaskDispatchWakeSink dispatchWake;

    public TaskDataService(
            TaskRuntime taskRuntime,
            TaskResourceCatalog taskCatalog,
            TaskDispatchWakeSink dispatchWake
    ) {
        this.taskRuntime = taskRuntime;
        this.taskCatalog = taskCatalog;
        this.dispatchWake = dispatchWake;
    }

    public TaskItemsAppendResponse appendTaskItems(
            String taskId,
            TaskItemsAppendRequest request
    ) {
        return appendResponse(appendTaskItems(
                taskId,
                request.items()
        ));
    }

    public TaskItemAppendResult appendTaskItem(
            String taskId,
            TaskItemRequest request
    ) {
        return appendTaskItems(taskId, List.of(request))
                .get(request.messageId());
    }

    private Map<String, TaskItemAppendResult> appendTaskItems(
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
                return uniformResults(
                        latest.keySet(),
                        TaskItemAppendStatus.NOT_FOUND
                );
            }

            var validItems = new ArrayList<TaskItem>();
            var results = new LinkedHashMap<
                    String,
                    TaskItemAppendResult
                    >();
            for (Map.Entry<String, TaskItemRequest> entry
                    : latest.entrySet()) {
                try {
                    validateRuleLocation(
                            descriptor,
                            entry.getValue().allocationRule()
                    );
                    validItems.add(toItem(entry.getValue()));
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
            Map<String, TaskItemAppendResult> ordered =
                    orderedResults(latest.keySet(), results);
            if (ordered.values().stream().anyMatch(
                    result -> result.status()
                            == TaskItemAppendStatus.APPENDED
            )) {
                try {
                    dispatchWake.offer(taskId);
                } catch (RuntimeException ignored) {
                    // A wake hint is never part of append acceptance.
                }
            }
            return ordered;
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

    public TaskItemResultsLoadResponse loadTaskItemSuccessResults(
            String taskId,
            List<String> messageIds
    ) {
        try {
            List<String> uniqueIds = new ArrayList<>(
                    new LinkedHashSet<>(messageIds)
            );
            TaskDescriptor descriptor = taskCatalog
                    .loadTaskAllocationDescriptors(List.of(taskId))
                    .get(taskId);
            if (descriptor == null) {
                throw new ServerException(
                        ServerErrorCode.TASK_NOT_FOUND,
                        "taskData.loadSuccessResults",
                        null,
                        null
                );
            }
            return new TaskItemResultsLoadResponse(
                    taskRuntime.loadTaskItemSuccessResults(
                            taskId,
                            uniqueIds
                    )
            );
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ServerException(
                    ServerErrorCode.TASK_DATA_UNAVAILABLE,
                    "taskData.loadSuccessResults",
                    null,
                    error
            );
        }
    }

    private static void validateRuleLocation(
            TaskDescriptor descriptor,
            Map<String, Object> rule
    ) {
        if (descriptor.taskType() == TaskType.TASK_DRIVEN) {
            if (rule != null) {
                throw new IllegalArgumentException(
                        "TASK_DRIVEN forbids TaskItem allocationRule"
                );
            }
            return;
        }
        if (rule == null || rule.isEmpty()) {
            throw new IllegalArgumentException(
                    "ITEM_DRIVEN requires a non-empty "
                            + "TaskItem allocationRule"
            );
        }
    }

    private static TaskItem toItem(TaskItemRequest item) {
        return new TaskItem(
                item.messageId(),
                item.eventCode(),
                item.createdAtMillis(),
                item.payload(),
                item.priority(),
                item.expireAtMillis(),
                item.allocationRule()
        );
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

    private static Map<String, TaskItemAppendResult> uniformResults(
            Set<String> messageIds,
            TaskItemAppendStatus status
    ) {
        var results = new LinkedHashMap<
                String,
                TaskItemAppendResult
                >();
        messageIds.forEach(messageId -> results.put(
                messageId,
                new TaskItemAppendResult(status)
        ));
        return results;
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

    private static TaskItemsAppendResponse appendResponse(
            Map<String, TaskItemAppendResult> appended
    ) {
        var results = new LinkedHashMap<String, CommandResultResponse>();
        appended.forEach((messageId, result) -> results.put(
                messageId,
                new CommandResultResponse(
                        RuntimeCommandStatus.fromWireValue(
                                result.status().wireValue()
                        ),
                        result.reason()
                )
        ));
        return new TaskItemsAppendResponse(results);
    }
}
