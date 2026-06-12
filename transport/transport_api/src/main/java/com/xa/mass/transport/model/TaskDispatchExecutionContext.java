package com.xa.mass.transport.model;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;

import java.util.Objects;

/**
 * Typed worker-frame and result-correlation context for assigned delivery.
 */
public final class TaskDispatchExecutionContext {

    private final String attemptId;
    private final int attemptNo;
    private final int retryCount;
    private final String batchId;
    private final String taskName;
    private final String project;
    private final String userId;

    public TaskDispatchExecutionContext(String attemptId,
                                        int attemptNo,
                                        int retryCount,
                                        String batchId,
                                        String taskName,
                                        String project,
                                        String userId) {
        this.attemptId = optionalText(attemptId);
        this.attemptNo = Math.max(0, attemptNo);
        this.retryCount = Math.max(0, retryCount);
        this.batchId = optionalText(batchId);
        this.taskName = optionalText(taskName);
        this.project = optionalText(project);
        this.userId = optionalText(userId);
    }

    public static TaskDispatchExecutionContext from(TaskDispatchContext task, TaskDispatchBinding binding) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(binding, "binding");
        return new TaskDispatchExecutionContext(
                binding.attemptId(),
                binding.attemptNo(),
                binding.retryCount(),
                binding.batchId(),
                task.taskName(),
                task.project(),
                task.userId()
        );
    }

    public String attemptId() {
        return attemptId;
    }

    public int attemptNo() {
        return attemptNo;
    }

    public int retryCount() {
        return retryCount;
    }

    public String batchId() {
        return batchId;
    }

    public String taskName() {
        return taskName;
    }

    public String project() {
        return project;
    }

    public String userId() {
        return userId;
    }

    private static String optionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
