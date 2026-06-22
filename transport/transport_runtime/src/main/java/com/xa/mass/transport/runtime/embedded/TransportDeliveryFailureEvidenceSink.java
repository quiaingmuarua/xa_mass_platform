package com.xa.mass.transport.runtime.embedded;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureEvent;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryFailureHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Emits retryable delivery failures to the configured transport failure sink.
 */
public final class TransportDeliveryFailureEvidenceSink implements DeliveryFailureEvidenceSink {

    private static final Logger logger = LoggerFactory.getLogger(TransportDeliveryFailureEvidenceSink.class);

    private final TransportDeliveryFailureHandler failureHandler;

    public TransportDeliveryFailureEvidenceSink(TransportDeliveryFailureHandler failureHandler) {
        this.failureHandler = failureHandler;
    }

    @Override
    public void accept(List<DispatchOutcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            return;
        }
        for (DispatchOutcome outcome : outcomes) {
            if (outcome == null || !outcome.isRetryable()) {
                continue;
            }
            if (failureHandler == null) {
                logger.warn("Delivery failure has no failure handler: deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                        outcome.getDeliveryId(), outcome.getSelectedWorkerId(), outcome.getStatus(), outcome.getReason());
                continue;
            }
            boolean handled = failureHandler.handle(new TransportDeliveryFailureEvent(outcome, outcome.getReason()));
            if (!handled) {
                logger.error("Delivery failure was not handled: deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                        outcome.getDeliveryId(), outcome.getSelectedWorkerId(), outcome.getStatus(), outcome.getReason());
            }
        }
    }
}
