package com.xa.mass.kernel.task;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

public interface TaskLifecycleCommands {

    TaskApprovalResult approveTask(String taskId);

    TaskCloseResult closeTask(String taskId);

    enum TaskApprovalStatus {
        APPROVED("approved"),
        ALREADY_APPROVED("already_approved"),
        NOT_FOUND("not_found"),
        CONFLICT("conflict"),
        INVALID("invalid"),
        RETRYABLE("retryable");

        private final String wireValue;

        TaskApprovalStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    enum TaskCloseStatus {
        CLOSED("closed"),
        ALREADY_CLOSED("already_closed"),
        NOT_FOUND("not_found"),
        INVALID("invalid"),
        RETRYABLE("retryable");

        private final String wireValue;

        TaskCloseStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    record TaskApprovalResult(
            TaskApprovalStatus status,
            @Nullable String reason
    ) {
        public TaskApprovalResult {
            Objects.requireNonNull(status, "status");
        }

        public TaskApprovalResult(TaskApprovalStatus status) {
            this(status, null);
        }
    }

    record TaskCloseResult(
            TaskCloseStatus status,
            @Nullable String reason
    ) {
        public TaskCloseResult {
            Objects.requireNonNull(status, "status");
        }

        public TaskCloseResult(TaskCloseStatus status) {
            this(status, null);
        }
    }
}
