package com.xa.mass.transport.channel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeliveryPullResultTest {

    @Test
    void deliveredRequiresAtLeastOneItem() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> DeliveryPullResult.delivered(List.of())
        );

        assertEquals("delivered pull result must include at least one item", error.getMessage());
    }

    @Test
    void ofNormalizesNonDeliveredStatusesToEmptyItems() {
        DeliveryPullResult result = DeliveryPullResult.of(DeliveryPullStatus.UNAVAILABLE, List.of(message("delivery-1")));

        assertEquals(DeliveryPullStatus.UNAVAILABLE, result.getStatus());
        assertEquals(List.of(), result.getItems());
    }

    private static PulledDeliveryMessage message(String deliveryId) {
        return new PulledDeliveryMessage(
                deliveryId,
                "worker-1",
                "{\"messageId\":\"msg-1\"}",
                "corr-1",
                10L
        );
    }
}
