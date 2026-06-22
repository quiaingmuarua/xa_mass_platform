package com.xa.mass.transport.runtime.delivery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransportDeliveryPollResultTest {

    @Test
    void deliveredRequiresAtLeastOneItem() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TransportDeliveryPollResult.delivered(List.of())
        );

        assertEquals("delivered poll result must include at least one item", error.getMessage());
    }

    @Test
    void ofNormalizesNonDeliveredStatusesToEmptyItemList() {
        TransportDeliveryPollResult result = TransportDeliveryPollResult.of(
                TransportDeliveryPollStatus.SHUTDOWN,
                List.of(item("msg-1", "worker-1"))
        );

        assertEquals(TransportDeliveryPollStatus.SHUTDOWN, result.getStatus());
        assertEquals(List.of(), result.getItems());
    }

    private static DispatchRoutingItem item(String messageId, String workerId) {
        String deliveryId = "delivery-" + messageId;
        return new DispatchRoutingItem(
                deliveryId,
                workerId,
                "{\"messageId\":\"" + messageId + "\"}",
                "corr-" + messageId,
                0L,
                1L
        );
    }
}
