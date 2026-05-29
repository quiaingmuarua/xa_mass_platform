package com.xa.mass.engine;

/**
 * Engine-owned lifecycle state for runtime work items and attempts.
 */
public final class TaskWorkLifecycleState {

    private TaskWorkLifecycleState() {
    }

    public static boolean isFinalReasonCompatible(MessageStatus status,
                                                  MessageFinalReason finalReason) {
        if (status == null || !status.isFinal() || finalReason == null) {
            return false;
        }
        return switch (status) {
            case SUCCESS -> finalReason == MessageFinalReason.BUSINESS_SUCCESS;
            case FAILED -> finalReason == MessageFinalReason.BUSINESS_FAILED
                    || finalReason == MessageFinalReason.MANUAL_CANCELLED
                    || finalReason == MessageFinalReason.RETRY_EXHAUSTED;
            case EXPIRED -> finalReason == MessageFinalReason.TIMEOUT
                    || finalReason == MessageFinalReason.WORKER_LOST
                    || finalReason == MessageFinalReason.MANUAL_CANCELLED
                    || finalReason == MessageFinalReason.LEASE_EXPIRED;
            default -> false;
        };
    }

    public enum MessageStatus {
        INIT,
        ASSIGNED,
        RUNNING,
        SUCCESS,
        FAILED,
        EXPIRED;

        public boolean isFinal() {
            return this == SUCCESS || this == FAILED || this == EXPIRED;
        }

        public boolean isProcessing() {
            return this == ASSIGNED || this == RUNNING;
        }
    }

    public enum MessageFinalReason {
        BUSINESS_SUCCESS,
        BUSINESS_FAILED,
        TIMEOUT,
        WORKER_LOST,
        MANUAL_CANCELLED,
        LEASE_EXPIRED,
        RETRY_EXHAUSTED
    }

    public enum AttemptStatus {
        CREATED,
        LEASED,
        DISPATCHED,
        ACKED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        EXPIRED,
        REVOKED;

        public boolean isFinal() {
            return this == SUCCEEDED || this == FAILED || this == EXPIRED || this == REVOKED;
        }

        public boolean isActive() {
            return !isFinal();
        }
    }

    public enum AttemptFinalReason {
        SUCCESS,
        BUSINESS_FAILURE,
        TIMEOUT,
        WORKER_LOST,
        MANUAL_CANCELLED,
        LEASE_EXPIRED,
        REVOKED_FOR_RETRY
    }
}
