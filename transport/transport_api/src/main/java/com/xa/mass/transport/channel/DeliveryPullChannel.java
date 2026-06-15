package com.xa.mass.transport.channel;

import java.util.List;

/**
 * Pull-based opaque delivery channel for transport consumers.
 */
public interface DeliveryPullChannel {

    default List<PulledDeliveryMessage> pollDeliveryMessages(String selectedWorkerId, int maxMessages) {
        return pollDeliveryMessages(selectedWorkerId, maxMessages, 0L);
    }

    default List<PulledDeliveryMessage> pollDeliveryMessages(String selectedWorkerId,
                                                             int maxMessages,
                                                             long timeoutMillis) {
        return pollDeliveryMessagesResult(selectedWorkerId, maxMessages, timeoutMillis).getItems();
    }

    default DeliveryPullResult pollDeliveryMessagesResult(String selectedWorkerId, int maxMessages) {
        return pollDeliveryMessagesResult(selectedWorkerId, maxMessages, 0L);
    }

    DeliveryPullResult pollDeliveryMessagesResult(String selectedWorkerId, int maxMessages, long timeoutMillis);
}
