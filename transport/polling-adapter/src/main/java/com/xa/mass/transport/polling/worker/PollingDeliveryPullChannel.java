package com.xa.mass.transport.polling.worker;

import com.xa.mass.transport.channel.DeliveryPullChannel;
import com.xa.mass.transport.channel.DeliveryPullResult;
import com.xa.mass.transport.channel.DeliveryPullStatus;
import com.xa.mass.transport.channel.PulledDeliveryMessage;
import com.xa.mass.transport.polling.delivery.PollingPendingDeliveryBuffer;
import com.xa.mass.transport.polling.delivery.PollingPendingDeliveryPollResult;
import com.xa.mass.transport.polling.delivery.PollingPendingDeliveryPollStatus;
import com.xa.mass.transport.runtime.delivery.DispatchRoutingItem;

import java.util.List;
import java.util.Objects;

/**
 * Polling worker pull channel backed by the polling-adapter pending buffer.
 */
public final class PollingDeliveryPullChannel implements DeliveryPullChannel {

    private final String adapterMailboxKey;
    private final PollingPendingDeliveryBuffer deliveryBuffer;

    public PollingDeliveryPullChannel(String adapterMailboxKey, PollingPendingDeliveryBuffer deliveryBuffer) {
        if (adapterMailboxKey == null || adapterMailboxKey.isBlank()) {
            throw new IllegalArgumentException("adapterMailboxKey must not be blank");
        }
        this.adapterMailboxKey = adapterMailboxKey.trim();
        this.deliveryBuffer = Objects.requireNonNull(deliveryBuffer, "deliveryBuffer");
    }

    @Override
    public DeliveryPullResult pollDeliveryMessagesResult(String deliveryBucketId,
                                                         String selectedWorkerId,
                                                         int maxMessages,
                                                         long timeoutMillis) {
        if (deliveryBucketId == null || deliveryBucketId.isBlank()
                || selectedWorkerId == null || selectedWorkerId.isBlank()
                || maxMessages <= 0) {
            return DeliveryPullResult.invalidRequest();
        }
        PollingPendingDeliveryPollResult result;
        try {
            result = deliveryBuffer.poll(
                    adapterMailboxKey,
                    selectedWorkerId,
                    maxMessages,
                    timeoutMillis
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = PollingPendingDeliveryPollResult.unavailable();
        }
        return DeliveryPullResult.of(mapStatus(result.getStatus()), toPulledItems(result.getItems()));
    }

    private static DeliveryPullStatus mapStatus(PollingPendingDeliveryPollStatus status) {
        if (status == null) {
            return DeliveryPullStatus.UNAVAILABLE;
        }
        return switch (status) {
            case DELIVERED -> DeliveryPullStatus.DELIVERED;
            case EMPTY -> DeliveryPullStatus.EMPTY;
            case INVALID_REQUEST -> DeliveryPullStatus.INVALID_REQUEST;
            case UNAVAILABLE -> DeliveryPullStatus.UNAVAILABLE;
            case SHUTDOWN -> DeliveryPullStatus.SHUTDOWN;
        };
    }

    private static List<PulledDeliveryMessage> toPulledItems(List<DispatchRoutingItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .map(item -> new PulledDeliveryMessage(
                        item.deliveryId(),
                        item.selectedWorkerId(),
                        item.payload(),
                        item.correlationRef(),
                        item.createdAtEpochMillis()
                ))
                .toList();
    }
}
