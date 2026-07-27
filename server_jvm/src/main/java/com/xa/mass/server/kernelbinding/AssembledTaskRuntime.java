package com.xa.mass.server.kernelbinding;

import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.redis.RedisTaskRuntime;
import java.util.List;
import java.util.Map;

public final class AssembledTaskRuntime implements TaskRuntime {

    private final HttpTaskRuntime controlProvider;
    private final RedisTaskRuntime dataProvider;

    public AssembledTaskRuntime(
            HttpTaskRuntime controlProvider,
            RedisTaskRuntime dataProvider
    ) {
        this.controlProvider = controlProvider;
        this.dataProvider = dataProvider;
    }

    @Override
    public TaskCreationResult createTask(
            TaskDescriptor descriptor,
            int suffix
    ) {
        return controlProvider.createTask(descriptor, suffix);
    }

    @Override
    public Map<String, TaskItemAppendResult> appendItems(
            String taskId,
            List<TaskItem> items
    ) {
        return dataProvider.appendItems(taskId, items);
    }

    @Override
    public Map<String, TaskItem> loadTaskItems(
            String taskId,
            List<String> messageIds
    ) {
        throw notImplemented("load_task_items");
    }

    @Override
    public void storeTaskItemSuccessResults(
            String taskId,
            Map<String, String> results
    ) {
        throw notImplemented("store_task_item_success_results");
    }

    @Override
    public Map<String, String> loadTaskItemSuccessResults(
            String taskId,
            List<String> messageIds
    ) {
        return dataProvider.loadTaskItemSuccessResults(taskId, messageIds);
    }

    private static KernelOperationNotImplementedException notImplemented(
            String operation
    ) {
        return new KernelOperationNotImplementedException(
                "TaskRuntime",
                operation
        );
    }
}
