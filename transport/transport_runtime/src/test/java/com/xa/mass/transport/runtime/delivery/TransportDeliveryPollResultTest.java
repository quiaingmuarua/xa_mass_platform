package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.TaskDispatchContent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    private static QueuedPulledDispatch item(String messageId, String workerId) {
        String deliveryId = "delivery-" + messageId;
        return new QueuedPulledDispatch(
                deliveryId,
                workerId,
                new TaskDispatchContent(
                        "task-1",
                        messageId,
                        "crawler.fetch-page",
                        Map.of("target", "target-1"),
                        Map.of()
                ),
                "attempt-" + messageId,
                1,
                0,
                "batch-1",
                1L
        );
    }
}
