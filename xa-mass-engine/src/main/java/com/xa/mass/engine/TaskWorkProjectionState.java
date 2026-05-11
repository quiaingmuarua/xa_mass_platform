package com.xa.mass.engine;

import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionStatus;
import com.xa.mass.storage.api.projection.TaskMessageProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;

/**
 * Engine-owned compatibility residue state for bounded message/attempt
 * projections.
 *
 * <p>These enums exist so runtime/result/trace code does not use storage-edge
 * projection enums as its native state model. Conversion to storage projection
 * types happens only at persistence and diagnostic boundaries.</p>
 */
public final class TaskWorkProjectionState {

    private TaskWorkProjectionState() {
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

        public TaskMessageProjectionStatus toProjection() {
            return TaskMessageProjectionStatus.valueOf(name());
        }

        public static MessageStatus fromProjection(TaskMessageProjectionStatus status) {
            return status == null ? null : MessageStatus.valueOf(status.name());
        }
    }

    public enum MessageFinalReason {
        BUSINESS_SUCCESS,
        BUSINESS_FAILED,
        TIMEOUT,
        WORKER_LOST,
        MANUAL_CANCELLED,
        LEASE_EXPIRED,
        RETRY_EXHAUSTED;

        public TaskMessageProjectionFinalReason toProjection() {
            return TaskMessageProjectionFinalReason.valueOf(name());
        }

        public static MessageFinalReason fromProjection(TaskMessageProjectionFinalReason finalReason) {
            return finalReason == null ? null : MessageFinalReason.valueOf(finalReason.name());
        }
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

        public TaskMessageAttemptProjectionStatus toProjection() {
            return TaskMessageAttemptProjectionStatus.valueOf(name());
        }

        public static AttemptStatus fromProjection(TaskMessageAttemptProjectionStatus status) {
            return status == null ? null : AttemptStatus.valueOf(status.name());
        }
    }

    public enum AttemptFinalReason {
        SUCCESS,
        BUSINESS_FAILURE,
        TIMEOUT,
        WORKER_LOST,
        MANUAL_CANCELLED,
        LEASE_EXPIRED,
        REVOKED_FOR_RETRY;

        public TaskMessageAttemptProjectionFinalReason toProjection() {
            return TaskMessageAttemptProjectionFinalReason.valueOf(name());
        }

        public static AttemptFinalReason fromProjection(TaskMessageAttemptProjectionFinalReason finalReason) {
            return finalReason == null ? null : AttemptFinalReason.valueOf(finalReason.name());
        }
    }
}
