package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;

/**
 * Runtime-local conversion from flat dispatch items to transport outcomes.
 */
public final class DispatchOutcomeFactory {

    private DispatchOutcomeFactory() {
    }

    public static DispatchOutcome delivered(DispatchMessage item) {
        return fromItem(item, DispatchOutcomeStatus.DELIVERED, false, null);
    }

    public static DispatchOutcome noEndpoint(DispatchMessage item, String reason) {
        return fromItem(item, DispatchOutcomeStatus.NO_ENDPOINT, true, reason);
    }

    public static DispatchOutcome invalid(DispatchMessage item, String reason) {
        return fromItem(item, DispatchOutcomeStatus.INVALID, false, reason);
    }

    public static DispatchOutcome unavailable(DispatchMessage item, String reason) {
        return fromItem(item, DispatchOutcomeStatus.UNAVAILABLE, true, reason);
    }

    public static DispatchOutcome failed(DispatchMessage item, String reason, boolean retryable) {
        return fromItem(item, DispatchOutcomeStatus.FAILED, retryable, reason);
    }

    public static DispatchOutcome shutdown(DispatchMessage item, String reason) {
        return fromItem(item, DispatchOutcomeStatus.SHUTDOWN, true, reason);
    }

    public static DispatchOutcome queued(DispatchMessage item) {
        return fromItem(item, DispatchOutcomeStatus.QUEUED, false, null);
    }

    public static DispatchOutcome backpressure(DispatchMessage item, String reason) {
        return fromItem(item, DispatchOutcomeStatus.BACKPRESSURE, true, reason);
    }

    public static DispatchOutcome fromItem(DispatchMessage item,
                                           DispatchOutcomeStatus status,
                                           boolean retryable,
                                           String reason) {
        return new DispatchOutcome(
                item != null ? item.deliveryId() : null,
                item != null ? item.selectedWorkerId() : null,
                item != null ? item.correlationRef() : null,
                status,
                retryable,
                reason,
                System.currentTimeMillis()
        );
    }
}
