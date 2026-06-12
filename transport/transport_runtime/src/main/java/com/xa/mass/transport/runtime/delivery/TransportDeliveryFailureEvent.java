package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;

import java.util.Objects;

/**
 * Process-boundary event for retryable delivery failures.
 */
public record TransportDeliveryFailureEvent(DeliveryObservationGroupContext groupContext,
                                            DeliveryObservationItemSnapshot itemSnapshot,
                                            DispatchOutcome outcome,
                                            String detail) {

    public TransportDeliveryFailureEvent {
        groupContext = Objects.requireNonNull(groupContext, "groupContext");
        itemSnapshot = Objects.requireNonNull(itemSnapshot, "itemSnapshot");
        outcome = Objects.requireNonNull(outcome, "outcome");
    }
}
