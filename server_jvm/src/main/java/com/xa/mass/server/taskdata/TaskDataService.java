package com.xa.mass.server.taskdata;

import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskType;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.server.api.v1.model.CommandResultResponse;
import com.xa.mass.server.api.v1.model.RuntimeCommandStatus;
import com.xa.mass.server.api.v1.model.TaskItemRequest;
import com.xa.mass.server.api.v1.model.TaskItemResultsLoadResponse;
import com.xa.mass.server.api.v1.model.TaskItemsAppendRequest;
import com.xa.mass.server.api.v1.model.TaskItemsAppendResponse;
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
    private final WorkerResourceCatalog workerCatalog;

    public TaskDataService(
            TaskRuntime taskRuntime,
            TaskResourceCatalog taskCatalog,
            WorkerResourceCatalog workerCatalog
    ) {
        this.taskRuntime = taskRuntime;
        this.taskCatalog = taskCatalog;
        this.workerCatalog = workerCatalog;
    }

    public TaskItemsAppendResponse appendTaskItems(
            String taskId,
            TaskItemsAppendRequest request
    ) {
        try {
            LinkedHashMap<String, TaskItemRequest> latest =
                    latestItems(request.items());
            TaskDescriptor descriptor = taskCatalog
                    .loadTaskAllocationDescriptors(List.of(taskId))
                    .get(taskId);
            if (descriptor == null) {
                return appendResponse(uniformResults(
                        latest.keySet(),
                        TaskItemAppendStatus.NOT_FOUND
                ));
            }

            WorkerGroupDescriptor workerGroup = null;
            if (descriptor.taskType() == TaskType.ITEM_DRIVEN) {
                workerGroup = workerCatalog.getWorkerGroupDescriptors(
                        List.of(descriptor.workerGroupId())
                ).get(descriptor.workerGroupId());
            }

            var validItems = new ArrayList<TaskItem>();
            var results = new LinkedHashMap<
                    String,
                    TaskItemAppendResult
                    >();
            for (Map.Entry<String, TaskItemRequest> entry
                    : latest.entrySet()) {
                try {
                    validateAllocation(
                            descriptor,
                            workerGroup,
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
            return appendResponse(orderedResults(latest.keySet(), results));
        } catch (TaskDataException error) {
            throw error;
        } catch (RuntimeException error) {
            throw TaskDataException.unavailable(error);
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
                throw TaskDataException.notFound("Task was not found");
            }
            return new TaskItemResultsLoadResponse(
                    taskRuntime.loadTaskItemSuccessResults(
                            taskId,
                            uniqueIds
                    )
            );
        } catch (TaskDataException error) {
            throw error;
        } catch (RuntimeException error) {
            throw TaskDataException.unavailable(error);
        }
    }

    private static void validateAllocation(
            TaskDescriptor descriptor,
            WorkerGroupDescriptor workerGroup,
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
        if (workerGroup == null
                || !workerGroup.itemAllocationFields().contains("workerId")) {
            throw new IllegalArgumentException(
                    "WorkerGroup does not allow workerId targeting"
            );
        }
        validateWorkerIdRule(rule);
    }

    private static void validateWorkerIdRule(Map<String, Object> rule) {
        if (rule == null || rule.size() != 1 || !rule.containsKey("workerId")) {
            throw new IllegalArgumentException(
                    "ITEM_DRIVEN requires a workerId allocationRule"
            );
        }
        Object rawOperatorRule = rule.get("workerId");
        if (!(rawOperatorRule instanceof Map<?, ?> operatorRule)
                || operatorRule.size() != 1) {
            throw new IllegalArgumentException(
                    "workerId target requires exactly one operator"
            );
        }
        Map.Entry<?, ?> operator = operatorRule.entrySet().iterator().next();
        if ("$eq".equals(operator.getKey())
                && operator.getValue() instanceof String workerId
                && !workerId.isBlank()) {
            return;
        }
        if ("$in".equals(operator.getKey())
                && operator.getValue() instanceof List<?> workerIds
                && !workerIds.isEmpty()
                && workerIds.stream().allMatch(
                        value -> value instanceof String workerId
                                && !workerId.isBlank()
                )) {
            return;
        }
        throw new IllegalArgumentException(
                "workerId target only supports $eq or $in"
        );
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
                throw TaskDataException.invalid(
                        "TaskItem must be present"
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
