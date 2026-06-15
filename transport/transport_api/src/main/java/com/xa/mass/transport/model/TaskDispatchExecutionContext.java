package com.xa.mass.transport.model;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;

/**
 * Typed attempt-correlation context for assigned delivery.
 */
public final class TaskDispatchExecutionContext {

    private final String attemptId;
    private final int attemptNo;
    private final int retryCount;
    private final String batchId;

    public TaskDispatchExecutionContext(String attemptId,
                                        int attemptNo,
                                        int retryCount,
                                        String batchId) {
        this.attemptId = optionalText(attemptId);
        this.attemptNo = Math.max(0, attemptNo);
        this.retryCount = Math.max(0, retryCount);
        this.batchId = optionalText(batchId);
    }

    public static TaskDispatchExecutionContext from(TaskDispatchBinding binding) {
        java.util.Objects.requireNonNull(binding, "binding");
        return new TaskDispatchExecutionContext(
                binding.attemptId(),
                binding.attemptNo(),
                binding.retryCount(),
                binding.batchId()
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

    private static String optionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
