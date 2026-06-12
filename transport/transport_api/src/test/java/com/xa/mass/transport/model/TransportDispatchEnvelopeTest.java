package com.xa.mass.transport.model;

import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TransportDispatchEnvelopeTest {

    @Test
    void constructorUsesNormalizedPacketAddressAndAttemptIdentity() {
        TransportDispatchEnvelope envelope = new TransportDispatchEnvelope(
                "delivery-1",
                " worker-1 ",
                new TransportPacket(
                        TransportPacket.CURRENT_VERSION,
                        "delivery-1",
                        " trace-1 ",
                        PacketType.TASK_DISPATCH,
                        " WebSocket ",
                        " group-route-1 ",
                        "task-1",
                        "msg-1",
                        " attempt-1 ",
                        "crawler.fetch-page",
                        TransportPacket.JSON_CONTENT_TYPE,
                        Map.of()
                ),
                10L
        );

        assertEquals("websocket", envelope.getAdapterId());
        assertEquals("worker-1", envelope.getSelectedWorkerId());
        assertEquals("group-route-1", envelope.getRouteKey());
        assertEquals("attempt-1", envelope.getAttemptId());
        assertEquals("trace-1", envelope.getTraceId());
        assertEquals("delivery-1", envelope.getPacket().packetId());
        assertEquals("task-1", envelope.getPacket().taskId());
    }

    @Test
    void constructorCarriesNullForBlankPacketAddressAndIdentityFields() {
        TransportDispatchEnvelope envelope = new TransportDispatchEnvelope(
                "delivery-1",
                " ",
                new TransportPacket(
                        TransportPacket.CURRENT_VERSION,
                        "delivery-1",
                        " ",
                        PacketType.TASK_DISPATCH,
                        " ",
                        " ",
                        "task-1",
                        "msg-1",
                        null,
                        "crawler.fetch-page",
                        TransportPacket.JSON_CONTENT_TYPE,
                        Map.of()
                ),
                10L
        );

        assertNull(envelope.getAdapterId());
        assertNull(envelope.getSelectedWorkerId());
        assertNull(envelope.getRouteKey());
        assertNull(envelope.getAttemptId());
        assertNull(envelope.getTraceId());
    }

    @Test
    void packetViewCarriesDispatchPayloadWithoutWorkerPullProjection() {
        TransportDispatchEnvelope envelope = new TransportDispatchEnvelope(
                "delivery-1",
                "worker-1",
                new TransportPacket(
                        TransportPacket.CURRENT_VERSION,
                        "packet-1",
                        "trace-1",
                        PacketType.TASK_DISPATCH,
                        "websocket",
                        "group-route-1",
                        "task-1",
                        "msg-1",
                        "attempt-1",
                        "crawler.fetch-page",
                        TransportPacket.JSON_CONTENT_TYPE,
                        Map.of(
                                TransportPacket.PAYLOAD_INPUT, Map.of("target", "target-1"),
                                TransportPacket.PAYLOAD_SHARED_CONFIG, Map.of("debug", true)
                        )
                ),
                10L
        );

        assertEquals("msg-1", envelope.getPacket().messageId());
        assertEquals("attempt-1", envelope.getPacket().attemptId());
        assertEquals("target-1", envelope.getPacket().payloadObject(TransportPacket.PAYLOAD_INPUT).get("target"));
    }
}

