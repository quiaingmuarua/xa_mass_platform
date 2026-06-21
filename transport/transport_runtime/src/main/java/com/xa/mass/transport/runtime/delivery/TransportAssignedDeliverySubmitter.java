package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Transport-owned final-hop submitter for commands already assigned to a
 * selected worker by the scheduling plane.
 */
public final class TransportAssignedDeliverySubmitter {

    private static final Logger logger = LoggerFactory.getLogger(TransportAssignedDeliverySubmitter.class);

    private final TransportDeliveryCommandHandoff handoff;
    private final TransportDeliveryFailureHandler failureHandler;

    public TransportAssignedDeliverySubmitter(TransportDeliveryCommandHandoff handoff,
                                              TransportDeliveryFailureHandler failureHandler) {
        this.handoff = Objects.requireNonNull(handoff, "handoff");
        this.failureHandler = failureHandler;
    }

    public List<DispatchOutcome> submit(List<AdapterMailboxDeliveryCommand> commands) {
        Objects.requireNonNull(commands, "commands");
        if (commands.isEmpty()) {
            return List.of();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>();
        Map<String, DeliveryBatchBuilder> batches = new LinkedHashMap<>();

        for (AdapterMailboxDeliveryCommand routedCommand : commands) {
            AdapterMailboxDeliveryCommand normalized = Objects.requireNonNull(routedCommand, "routedCommand");
            DeliveryCommand normalizedCommand = normalized.command();
            String adapterMailboxKey = normalized.adapterMailboxKey();
            DeliveryBatchBuilder batch = batches.computeIfAbsent(
                    adapterMailboxKey,
                    DeliveryBatchBuilder::new
            );
            batch.items.add(normalizedCommand);
        }

        for (DeliveryBatchBuilder builder : batches.values()) {
            DeliveryCommandBatch batch = builder.toBatch();
            List<DispatchOutcome> offeredOutcomes = offerBatch(batch);
            outcomes.addAll(offeredOutcomes);
            handleRetryableFailures(offeredOutcomes);
        }
        return Collections.unmodifiableList(outcomes);
    }

    private List<DispatchOutcome> offerBatch(DeliveryCommandBatch batch) {
        try {
            List<DispatchOutcome> offered = handoff.offer(new AdapterMailboxDeliveryOffer(batch.adapterMailboxKey(), batch.items()));
            if (offered == null || offered.isEmpty()) {
                return List.of();
            }
            return offered.stream()
                    .filter(Objects::nonNull)
                    .toList();
        } catch (RuntimeException e) {
            logger.warn("Delivery command handoff offer failed: adapterMailboxKey={}, items={}, reason={}",
                    batch.adapterMailboxKey(), batch.items().size(), e.getMessage());
            String reason = e.getMessage() == null || e.getMessage().isBlank()
                    ? "delivery command handoff offer failed"
                    : "delivery command handoff offer failed: " + e.getMessage();
            return batch.items().stream()
                    .map(item -> DispatchOutcome.fromCommand(
                            item,
                            DispatchOutcomeStatus.UNAVAILABLE,
                            true,
                            reason))
                    .toList();
        }
    }

    private void handleRetryableFailures(List<DispatchOutcome> outcomes) {
        for (DispatchOutcome outcome : outcomes) {
            if (outcome != null && outcome.isRetryable()) {
                handleRetryableFailure(outcome, outcome.getReason());
            }
        }
    }

    private DispatchOutcome handleRetryableFailure(DispatchOutcome outcome,
                                                   String detail) {
        if (outcome == null || !outcome.isRetryable()) {
            return outcome;
        }
        if (failureHandler == null) {
            logger.warn("Delivery failure has no failure handler: deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                    outcome.getDeliveryId(), outcome.getSelectedWorkerId(), outcome.getStatus(), detail);
            return outcome;
        }
        boolean handled = failureHandler.handle(new TransportDeliveryFailureEvent(outcome, detail));
        if (!handled) {
            logger.error("Delivery failure was not handled: deliveryId={}, selectedWorkerId={}, status={}, reason={}",
                    outcome.getDeliveryId(), outcome.getSelectedWorkerId(), outcome.getStatus(), detail);
        }
        return outcome;
    }

    private static final class DeliveryBatchBuilder {
        private final String adapterMailboxKey;
        private final List<DeliveryCommand> items = new ArrayList<>();

        private DeliveryBatchBuilder(String adapterMailboxKey) {
            this.adapterMailboxKey = adapterMailboxKey;
        }

        private DeliveryCommandBatch toBatch() {
            return new DeliveryCommandBatch(adapterMailboxKey, items);
        }
    }
}
