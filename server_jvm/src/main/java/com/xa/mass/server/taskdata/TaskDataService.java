package com.xa.mass.server.taskdata;

import com.xa.mass.server.api.v1.model.CommandResultResponse;
import com.xa.mass.server.api.v1.model.RuntimeCommandStatus;
import com.xa.mass.server.api.v1.model.TaskItemRequest;
import com.xa.mass.server.api.v1.model.TaskItemResultsLoadResponse;
import com.xa.mass.server.api.v1.model.TaskItemsAppendRequest;
import com.xa.mass.server.api.v1.model.TaskItemsAppendResponse;
import com.xa.mass.server.taskdata.TaskDataRuntime.TaskItemAppendResult;
import com.xa.mass.server.taskdata.TaskDataRuntime.TaskItemRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public final class TaskDataService {

    private final TaskDataRuntime runtime;

    public TaskDataService(TaskDataRuntime runtime) {
        this.runtime = runtime;
    }

    public TaskItemsAppendResponse appendTaskItems(
            String taskId,
            TaskItemsAppendRequest request
    ) {
        try {
            List<TaskItemRecord> items = request.items().stream()
                    .map(TaskDataService::toRecord)
                    .toList();
            Map<String, TaskItemAppendResult> appended =
                    runtime.appendTaskItems(taskId, items);
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
                    new java.util.LinkedHashSet<>(messageIds)
            );
            return new TaskItemResultsLoadResponse(
                    runtime.loadTaskItemSuccessResults(taskId, uniqueIds)
            );
        } catch (TaskDataException error) {
            throw error;
        } catch (RuntimeException error) {
            throw TaskDataException.unavailable(error);
        }
    }

    private static TaskItemRecord toRecord(TaskItemRequest item) {
        return new TaskItemRecord(
                item.messageId(),
                item.eventCode(),
                item.createdAtMillis(),
                item.payload(),
                item.priority(),
                item.expireAtMillis(),
                item.allocationRule()
        );
    }
}
