package com.xa.mass.server.kernelbinding;

import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.task.TaskRuntime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HttpTaskRuntime implements TaskRuntime {

    private final PythonKernelHttpTransport transport;

    public HttpTaskRuntime(PythonKernelHttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public TaskCreationResult createTask(
            TaskDescriptor descriptor,
            int suffix
    ) {
        if (suffix != 0) {
            throw new IllegalArgumentException(
                    "Python application create supports only initial suffix 0"
            );
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", descriptor.taskId());
        body.put("workerGroupId", descriptor.workerGroupId());
        body.put(
                "workerAllocationMechanism",
                descriptor.workerAllocationMechanism().name()
        );
        body.put("idleDisposition", descriptor.idleDisposition().name());
        body.put("allocationRule", descriptor.allocationRule());
        body.put("config", descriptor.config());
        Map<String, Object> response = transport.postBody("/tasks", body);
        TaskCreationStatus status = KernelHttpResultDecoder.status(
                response,
                HttpTaskRuntime::creationStatus
        );
        return new TaskCreationResult(
                status,
                KernelHttpResultDecoder.reason(response)
        );
    }

    @Override
    public Map<String, TaskItemAppendResult> appendItems(
            String taskId,
            List<TaskItem> items
    ) {
        throw notImplemented("append_items");
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
        throw notImplemented("load_task_item_success_results");
    }

    private static TaskCreationStatus creationStatus(String value) {
        return switch (value) {
            case "created" -> TaskCreationStatus.CREATED;
            case "retryable" -> TaskCreationStatus.RETRYABLE;
            case "conflict" -> TaskCreationStatus.CONFLICT;
            case "invalid" -> TaskCreationStatus.INVALID;
            default -> throw new IllegalArgumentException(
                    "unknown Task creation status"
            );
        };
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
