package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.runtime.packet.TransportPacketFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransportDeliveryPollResultTest {

    @Test
    void deliveredRequiresAtLeastOneEnvelope() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TransportDeliveryPollResult.delivered(List.of())
        );

        assertEquals("delivered poll result must include at least one envelope", error.getMessage());
    }

    @Test
    void ofNormalizesNonDeliveredStatusesToEmptyEnvelopeList() {
        TransportDeliveryPollResult result = TransportDeliveryPollResult.of(
                TransportDeliveryPollStatus.SHUTDOWN,
                List.of(envelope(item("msg-1", "worker-1")))
        );

        assertEquals(TransportDeliveryPollStatus.SHUTDOWN, result.getStatus());
        assertEquals(List.of(), result.getEnvelopes());
    }

    private static TaskDispatchItem item(String messageId, String workerId) {
        return new TaskDispatchItem(
                "task-1",
                messageId,
                "crawler.fetch-page",
                "task-name",
                "demoApp",
                "agent",
                0,
                workerId,
                null,
                "batch-1",
                Map.of("target", "target-1"),
                Map.of()
        );
    }

    private static TransportDispatchEnvelope envelope(TaskDispatchItem item) {
        String deliveryId = "delivery-" + item.getMessageId();
        return new TransportDispatchEnvelope(
                deliveryId,
                new TransportPacketFactory(() -> deliveryId)
                        .fromDispatchView("polling", item.getWorkerId(), item.attemptId(), item),
                1L
        );
    }
}
