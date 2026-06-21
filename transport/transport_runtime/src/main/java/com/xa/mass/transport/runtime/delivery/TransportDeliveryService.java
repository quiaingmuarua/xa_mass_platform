package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Runtime-owned delivery service shared by concrete transport adapters.
 *
 * <p>Adapters use this service for queueing and draining pull delivery.
 * This service reports queue outcomes only; task lifecycle mutation remains
 * in engine result and assignment services.</p>
 */
public final class TransportDeliveryService {

    private final TransportDeliveryStore deliveryStore;

    public TransportDeliveryService(TransportDeliveryStore deliveryStore) {
        this.deliveryStore = Objects.requireNonNull(deliveryStore, "deliveryStore");
    }

    public List<DispatchOutcome> enqueueForMailbox(String adapterMailboxKey, List<DeliveryCommand> commands) {
        String normalizedMailboxKey = requireText(adapterMailboxKey, "adapterMailboxKey");
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        List<DispatchOutcome> outcomes = new ArrayList<>(commands.size());
        for (DeliveryCommand command : commands) {
            if (command == null) {
                outcomes.add(DispatchOutcome.invalid(
                        null,
                        null,
                        null,
                        "request must not be null"
                ));
                continue;
            }
            outcomes.add(deliveryStore.enqueue(normalizedMailboxKey, QueuedPulledDispatch.from(command)));
        }
        return Collections.unmodifiableList(outcomes);
    }

    public TransportDeliveryPollResult pollMailboxItemResult(String adapterMailboxKey,
                                                             String selectedWorkerId,
                                                             int maxItems,
                                                             long timeoutMillis) {
        return pollResolvedQueue(requireText(adapterMailboxKey, "adapterMailboxKey"),
                selectedWorkerId, maxItems, timeoutMillis);
    }

    private TransportDeliveryPollResult pollResolvedQueue(String adapterMailboxKey,
                                                          String selectedWorkerId,
                                                          int maxItems,
                                                          long timeoutMillis) {
        TransportDeliveryPollResult result;
        try {
            result = deliveryStore.poll(
                    adapterMailboxKey,
                    selectedWorkerId,
                    maxItems,
                    Math.max(0L, timeoutMillis),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TransportDeliveryPollResult.unavailable();
        }
        return result;
    }

    public TransportDeliveryServiceStats stats() {
        TransportDeliveryStoreStats storeStats = deliveryStore.stats();
        return new TransportDeliveryServiceStats(storeStats);
    }

    public void shutdown() {
        deliveryStore.shutdown();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

}
