package com.xa.mass.engine.command;

/**
 * Owner-level command acknowledgement/status input.
 *
 * <p>This is deliberately command-specific. It must not reuse task result
 * payloads or task-work attempt status as command lifecycle truth.</p>
 */
public record WorkerCommandAcknowledgement(
        String commandId,
        WorkerCommandStatus targetStatus,
        String reason
) {

    public WorkerCommandAcknowledgement {
        commandId = requireNonBlank(commandId, "commandId");
        if (targetStatus == null) {
            throw new IllegalArgumentException("targetStatus must not be null");
        }
        reason = normalizeNullable(reason);
    }

    public static WorkerCommandAcknowledgement deliveryAccepted(String commandId, String reason) {
        return new WorkerCommandAcknowledgement(commandId, WorkerCommandStatus.DELIVERY_ACCEPTED, reason);
    }

    public static WorkerCommandAcknowledgement executionAccepted(String commandId, String reason) {
        return new WorkerCommandAcknowledgement(commandId, WorkerCommandStatus.EXECUTION_ACCEPTED, reason);
    }

    public static WorkerCommandAcknowledgement succeeded(String commandId, String reason) {
        return new WorkerCommandAcknowledgement(commandId, WorkerCommandStatus.SUCCEEDED, reason);
    }

    public static WorkerCommandAcknowledgement failed(String commandId, String reason) {
        return new WorkerCommandAcknowledgement(commandId, WorkerCommandStatus.FAILED, reason);
    }

    public static WorkerCommandAcknowledgement expired(String commandId, String reason) {
        return new WorkerCommandAcknowledgement(commandId, WorkerCommandStatus.EXPIRED, reason);
    }

    private static String requireNonBlank(String value, String fieldName) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
