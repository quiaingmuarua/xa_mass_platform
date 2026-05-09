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
final class TaskMessageCompatibilityState {

    private TaskMessageCompatibilityState() {
    }

    static boolean isFinalReasonCompatible(MessageStatus status,
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

    enum MessageStatus {
        INIT,
        ASSIGNED,
        RUNNING,
        SUCCESS,
        FAILED,
        EXPIRED;

        boolean isFinal() {
            return this == SUCCESS || this == FAILED || this == EXPIRED;
        }

        boolean isProcessing() {
            return this == ASSIGNED || this == RUNNING;
        }

        TaskMessageProjectionStatus toProjection() {
            return TaskMessageProjectionStatus.valueOf(name());
        }

        static MessageStatus fromProjection(TaskMessageProjectionStatus status) {
            return status == null ? null : MessageStatus.valueOf(status.name());
        }
    }

    enum MessageFinalReason {
        BUSINESS_SUCCESS,
        BUSINESS_FAILED,
        TIMEOUT,
        WORKER_LOST,
        MANUAL_CANCELLED,
        LEASE_EXPIRED,
        RETRY_EXHAUSTED;

        TaskMessageProjectionFinalReason toProjection() {
            return TaskMessageProjectionFinalReason.valueOf(name());
        }

        static MessageFinalReason fromProjection(TaskMessageProjectionFinalReason finalReason) {
            return finalReason == null ? null : MessageFinalReason.valueOf(finalReason.name());
        }
    }

    enum AttemptStatus {
        CREATED,
        LEASED,
        DISPATCHED,
        ACKED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        EXPIRED,
        REVOKED;

        boolean isFinal() {
            return this == SUCCEEDED || this == FAILED || this == EXPIRED || this == REVOKED;
        }

        boolean isActive() {
            return !isFinal();
        }

        TaskMessageAttemptProjectionStatus toProjection() {
            return TaskMessageAttemptProjectionStatus.valueOf(name());
        }

        static AttemptStatus fromProjection(TaskMessageAttemptProjectionStatus status) {
            return status == null ? null : AttemptStatus.valueOf(status.name());
        }
    }

    enum AttemptFinalReason {
        SUCCESS,
        BUSINESS_FAILURE,
        TIMEOUT,
        WORKER_LOST,
        MANUAL_CANCELLED,
        LEASE_EXPIRED,
        REVOKED_FOR_RETRY;

        TaskMessageAttemptProjectionFinalReason toProjection() {
            return TaskMessageAttemptProjectionFinalReason.valueOf(name());
        }

        static AttemptFinalReason fromProjection(TaskMessageAttemptProjectionFinalReason finalReason) {
            return finalReason == null ? null : AttemptFinalReason.valueOf(finalReason.name());
        }
    }
}
