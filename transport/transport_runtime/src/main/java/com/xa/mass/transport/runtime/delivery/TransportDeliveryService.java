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

    public List<DispatchOutcome> enqueue(String adapterId, List<DeliveryCommand> commands) {
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
            String deliveryQueueKey;
            try {
                deliveryQueueKey = resolveDeliveryQueueKey(command.getDeliveryBucketId());
            } catch (RuntimeException e) {
                outcomes.add(DispatchOutcome.invalid(
                        command,
                        e.getMessage() == null || e.getMessage().isBlank()
                                ? "delivery bucket id is invalid for transport queue addressing"
                                : e.getMessage()
                ));
                continue;
            }
            outcomes.add(deliveryStore.enqueue(adapterId, deliveryQueueKey, QueuedPulledDispatch.from(command)));
        }
        return Collections.unmodifiableList(outcomes);
    }

    public List<QueuedPulledDispatch> drainItems(String deliveryBucketId, String selectedWorkerId, int maxItems) {
        return deliveryStore.drain(resolveDeliveryQueueKey(deliveryBucketId), selectedWorkerId, maxItems);
    }

    public List<QueuedPulledDispatch> pollItems(String deliveryBucketId,
                                                String selectedWorkerId,
                                                int maxItems,
                                                long timeoutMillis) {
        return pollItemResult(deliveryBucketId, selectedWorkerId, maxItems, timeoutMillis).getItems();
    }

    public TransportDeliveryPollResult pollItemResult(String deliveryBucketId,
                                                      String selectedWorkerId,
                                                      int maxItems,
                                                      long timeoutMillis) {
        TransportDeliveryPollResult result;
        try {
            result = deliveryStore.poll(
                    resolveDeliveryQueueKey(deliveryBucketId),
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

    private static String resolveDeliveryQueueKey(String deliveryBucketId) {
        return AssignedDeliveryCommandQueueKey.queueKeyFor(deliveryBucketId);
    }

}
