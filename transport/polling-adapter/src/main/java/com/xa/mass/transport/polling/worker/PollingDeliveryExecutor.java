package com.xa.mass.transport.polling.worker;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
import com.xa.mass.transport.runtime.embedded.AdapterCommandExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Polling final-hop executor: assigned delivery commands are queued for later
 * selected-worker pull.
 */
public final class PollingDeliveryExecutor implements AdapterCommandExecutor {

    private static final Logger logger = LoggerFactory.getLogger(PollingDeliveryExecutor.class);

    private final String adapterMailboxKey;
    private final TransportDeliveryService deliveryService;

    public PollingDeliveryExecutor(String adapterMailboxKey, TransportDeliveryService deliveryService) {
        this.adapterMailboxKey = requireText(adapterMailboxKey, "adapterMailboxKey");
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
    }

    @Override
    public List<DispatchOutcome> dispatch(List<DeliveryCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        List<DispatchOutcome> outcomes = deliveryService.enqueueForMailbox(adapterMailboxKey, commands);
        for (DispatchOutcome outcome : outcomes) {
            if (outcome.isRetryable()) {
                logger.warn("Polling delivery rejected: selectedWorkerId={}, deliveryId={}, status={}, reason={}",
                        outcome.getSelectedWorkerId(), outcome.getDeliveryId(),
                        outcome.getStatus(), outcome.getReason());
            }
        }
        return outcomes;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
