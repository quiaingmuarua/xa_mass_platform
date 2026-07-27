package com.xa.mass.server.kernelbinding;

import java.util.Objects;

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

        static TaskApprovalStatus fromWireValue(String value) {
            for (TaskApprovalStatus status : values()) {
                if (status.wireValue.equals(value)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("unknown approval status");
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

        static TaskCloseStatus fromWireValue(String value) {
            for (TaskCloseStatus status : values()) {
                if (status.wireValue.equals(value)) {
                    return status;
                }
            }
            throw new IllegalArgumentException("unknown close status");
        }
    }

    record TaskApprovalResult(
            TaskApprovalStatus status,
            String reason
    ) {
        public TaskApprovalResult {
            Objects.requireNonNull(status, "status");
        }
    }

    record TaskCloseResult(TaskCloseStatus status, String reason) {
        public TaskCloseResult {
            Objects.requireNonNull(status, "status");
        }
    }
}
