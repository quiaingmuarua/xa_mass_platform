package com.xa.mass.transport.channel;

import java.util.List;

/**
 * Pull-based opaque delivery channel for transport consumers.
 */
public interface DeliveryPullChannel {

    default List<PulledDeliveryMessage> pollDeliveryMessages(String deliveryBucketId,
                                                             String selectedWorkerId,
                                                             int maxMessages) {
        return pollDeliveryMessages(deliveryBucketId, selectedWorkerId, maxMessages, 0L);
    }

    default List<PulledDeliveryMessage> pollDeliveryMessages(String deliveryBucketId,
                                                             String selectedWorkerId,
                                                             int maxMessages,
                                                             long timeoutMillis) {
        return pollDeliveryMessagesResult(deliveryBucketId, selectedWorkerId, maxMessages, timeoutMillis).getItems();
    }

    default DeliveryPullResult pollDeliveryMessagesResult(String deliveryBucketId,
                                                          String selectedWorkerId,
                                                          int maxMessages) {
        return pollDeliveryMessagesResult(deliveryBucketId, selectedWorkerId, maxMessages, 0L);
    }

    DeliveryPullResult pollDeliveryMessagesResult(String deliveryBucketId,
                                                  String selectedWorkerId,
                                                  int maxMessages,
                                                  long timeoutMillis);
}
