package com.xa.mass.transport.model;

import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InboundEnvelopeTest {

    @Test
    void normalizesAdapterAndCarriesPayloadWithoutLifecycleValidation() {
        InboundEnvelope envelope = new InboundEnvelope(
                " envelope-1 ",
                " WebSocket ",
                " worker-1 ",
                " route-1 ",
                " conn-1 ",
                packet(),
                Map.of(" messageId ", " msg-1 ", "blank", " "),
                20L
        );

        assertEquals("envelope-1", envelope.getEnvelopeId());
        assertEquals("websocket", envelope.getAdapterId());
        assertEquals("worker-1", envelope.getSourceWorkerId());
        assertEquals("route-1", envelope.getRouteKey());
        assertEquals("conn-1", envelope.getConnectionId());
        assertEquals(packet(), envelope.getPayload());
        assertEquals(Map.of("messageId", "msg-1"), envelope.getCorrelation());
        assertEquals(20L, envelope.getReceivedAtEpochMillis());
    }

    @Test
    void rejectsMissingRequiredEnvelopeFields() {
        TransportPacket packet = packet();

        assertThrows(IllegalArgumentException.class, () -> new InboundEnvelope(
                " ",
                "websocket",
                "worker-1",
                null,
                null,
                packet,
                Map.of(),
                0L
        ));
        assertThrows(IllegalArgumentException.class, () -> new InboundEnvelope(
                "envelope-1",
                " ",
                "worker-1",
                null,
                null,
                packet,
                Map.of(),
                0L
        ));
        assertThrows(IllegalArgumentException.class, () -> new InboundEnvelope(
                "envelope-1",
                "websocket",
                " ",
                null,
                null,
                packet,
                Map.of(),
                0L
        ));
    }

    private static TransportPacket packet() {
        return new TransportPacket(
                TransportPacket.CURRENT_VERSION,
                "packet-1",
                "trace-1",
                PacketType.TASK_RESULT,
                "websocket",
                "route-1",
                "task-1",
                "msg-1",
                "attempt-1",
                null,
                TransportPacket.JSON_CONTENT_TYPE,
                Map.of("success", true)
        );
    }
}
