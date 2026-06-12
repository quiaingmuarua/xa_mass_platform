package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;
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
                List.of(envelope("msg-1", "worker-1"))
        );

        assertEquals(TransportDeliveryPollStatus.SHUTDOWN, result.getStatus());
        assertEquals(List.of(), result.getEnvelopes());
    }

    private static TransportDispatchEnvelope envelope(String messageId, String workerId) {
        String deliveryId = "delivery-" + messageId;
        return new TransportDispatchEnvelope(
                deliveryId,
                workerId,
                new TransportPacket(
                        TransportPacket.CURRENT_VERSION,
                        deliveryId,
                        "trace-" + messageId,
                        PacketType.TASK_DISPATCH,
                        "polling",
                        "group-route-1",
                        "task-1",
                        messageId,
                        "attempt-" + messageId,
                        "crawler.fetch-page",
                        TransportPacket.JSON_CONTENT_TYPE,
                        Map.of(
                                TransportPacket.PAYLOAD_WORKER_ID, workerId,
                                TransportPacket.PAYLOAD_INPUT, Map.of("target", "target-1"),
                                TransportPacket.PAYLOAD_SHARED_CONFIG, Map.of()
                        )
                ),
                1L
        );
    }
}
