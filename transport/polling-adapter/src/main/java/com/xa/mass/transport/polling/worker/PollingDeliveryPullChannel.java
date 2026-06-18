package com.xa.mass.transport.polling.worker;

import com.xa.mass.transport.channel.DeliveryPullChannel;
import com.xa.mass.transport.channel.DeliveryPullResult;
import com.xa.mass.transport.channel.DeliveryPullStatus;
import com.xa.mass.transport.channel.PulledDeliveryMessage;
import com.xa.mass.transport.runtime.delivery.QueuedPulledDispatch;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryPollResult;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryPollStatus;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;

import java.util.List;
import java.util.Objects;

/**
 * Polling worker pull channel backed by the transport delivery buffer.
 */
public final class PollingDeliveryPullChannel implements DeliveryPullChannel {

    private final TransportDeliveryService deliveryService;

    public PollingDeliveryPullChannel(TransportDeliveryService deliveryService) {
        this.deliveryService = Objects.requireNonNull(deliveryService, "deliveryService");
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
        TransportDeliveryPollResult result = deliveryService.pollItemResult(
                deliveryBucketId,
                selectedWorkerId,
                maxMessages,
                timeoutMillis
        );
        return DeliveryPullResult.of(mapStatus(result.getStatus()), toPulledItems(result.getItems()));
    }

    private static DeliveryPullStatus mapStatus(TransportDeliveryPollStatus status) {
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

    private static List<PulledDeliveryMessage> toPulledItems(List<QueuedPulledDispatch> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .map(QueuedPulledDispatch::toPulledDeliveryMessage)
                .toList();
    }
}
