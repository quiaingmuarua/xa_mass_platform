package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Transport-owned final-hop submitter for commands already assigned to a
 * selected worker by the scheduling plane.
 */
public final class TransportAssignedDeliverySubmitter {

    private static final Logger logger = LoggerFactory.getLogger(TransportAssignedDeliverySubmitter.class);

    private final TransportDispatchQueue dispatchQueue;
    public TransportAssignedDeliverySubmitter(TransportDispatchQueue dispatchQueue) {
        this.dispatchQueue = Objects.requireNonNull(dispatchQueue, "dispatchQueue");
    }

    public List<DispatchOutcome> submit(List<AdapterMailboxDispatchBatch> batches) {
        Objects.requireNonNull(batches, "batches");
        if (batches.isEmpty()) {
            return List.of();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>();

        for (AdapterMailboxDispatchBatch batch : batches) {
            AdapterMailboxDispatchBatch normalized = Objects.requireNonNull(batch, "batch");
            List<DispatchOutcome> offeredOutcomes = offerBatch(batch);
            outcomes.addAll(offeredOutcomes);
        }
        return Collections.unmodifiableList(outcomes);
    }

    private List<DispatchOutcome> offerBatch(AdapterMailboxDispatchBatch batch) {
        try {
            List<DispatchOutcome> offered = dispatchQueue.offer(batch.adapterMailboxKey(), batch.items());
            if (offered == null || offered.isEmpty()) {
                return List.of();
            }
            return offered.stream()
                    .filter(Objects::nonNull)
                    .toList();
        } catch (RuntimeException e) {
            logger.warn("Dispatch handoff offer failed: adapterMailboxKey={}, items={}, reason={}",
                    batch.adapterMailboxKey(), batch.items().size(), e.getMessage());
            String reason = e.getMessage() == null || e.getMessage().isBlank()
                    ? "dispatch handoff offer failed"
                    : "dispatch handoff offer failed: " + e.getMessage();
            return batch.items().stream()
                    .map(item -> DispatchOutcomeFactory.fromItem(
                            item,
                            DispatchOutcomeStatus.UNAVAILABLE,
                            true,
                            reason))
                    .toList();
        }
    }

}
