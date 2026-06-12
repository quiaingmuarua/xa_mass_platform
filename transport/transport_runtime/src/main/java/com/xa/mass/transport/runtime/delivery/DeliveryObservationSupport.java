package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;

final class DeliveryObservationSupport {

    private DeliveryObservationSupport() {
    }

    static DispatchOutcome outcome(DeliveryObservationGroupContext groupContext,
                                   DeliveryObservationItemSnapshot itemSnapshot,
                                   DispatchOutcomeStatus status,
                                   boolean retryable,
                                   String reason) {
        return new DispatchOutcome(
                itemSnapshot.commandId(),
                groupContext.adapterId(),
                itemSnapshot.selectedWorkerId(),
                groupContext.deliveryQueueKey(),
                itemSnapshot.routeKey(),
                itemSnapshot.attemptId(),
                status,
                retryable,
                reason,
                groupContext.targetTransportNodeId(),
                itemSnapshot.connectionId(),
                groupContext.occurredAtEpochMillis()
        );
    }

    static DispatchOutcome outcome(DeliveryObservationGroupContext groupContext,
                                   DeliveryCommand command,
                                   EndpointLease endpoint,
                                   DispatchOutcomeStatus status,
                                   boolean retryable,
                                   String reason) {
        return outcome(
                groupContext,
                DeliveryObservationItemSnapshot.from(command, endpoint),
                status,
                retryable,
                reason
        );
    }

    static TransportDeliveryFailureEvent failure(DeliveryObservationGroupContext groupContext,
                                                 DeliveryCommand command,
                                                 EndpointLease endpoint,
                                                 DispatchOutcome outcome,
                                                 String detail) {
        return new TransportDeliveryFailureEvent(
                groupContext,
                DeliveryObservationItemSnapshot.from(command, endpoint),
                outcome,
                detail
        );
    }
}
