package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;

import java.util.Objects;

/**
 * Process-boundary event for retryable delivery failures.
 */
public record TransportDeliveryFailureEvent(DeliveryCommand command,
                                            DispatchOutcome outcome,
                                            String detail) {

    public TransportDeliveryFailureEvent {
        command = Objects.requireNonNull(command, "command");
        outcome = Objects.requireNonNull(outcome, "outcome");
    }
}
