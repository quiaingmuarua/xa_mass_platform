package com.xa.mass.transport.model;

import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.packet.TransportPacketViews;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TransportDispatchEnvelopeTest {

    @Test
    void constructorUsesNormalizedPacketAddressAndAttemptIdentity() {
        TransportDispatchEnvelope envelope = new TransportDispatchEnvelope(
                "delivery-1",
                new TransportPacket(
                        TransportPacket.CURRENT_VERSION,
                        "delivery-1",
                        " trace-1 ",
                        PacketType.TASK_DISPATCH,
                        " WebSocket ",
                        " worker-1 ",
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
        assertEquals("worker-1", envelope.getRouteKey());
        assertEquals("attempt-1", envelope.getAttemptId());
        assertEquals("trace-1", envelope.getTraceId());
        assertEquals("delivery-1", envelope.getPacket().packetId());
        assertEquals("task-1", envelope.getPacket().taskId());
    }

    @Test
    void constructorCarriesNullForBlankPacketAddressAndIdentityFields() {
        TransportDispatchEnvelope envelope = new TransportDispatchEnvelope(
                "delivery-1",
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
        assertNull(envelope.getRouteKey());
        assertNull(envelope.getAttemptId());
        assertNull(envelope.getTraceId());
    }

    @Test
    void packetProjectionRebuildsDispatchItemWhenExplicitlyRequested() {
        TransportDispatchEnvelope envelope = new TransportDispatchEnvelope(
                "delivery-1",
                new TransportPacket(
                        TransportPacket.CURRENT_VERSION,
                        "packet-1",
                        "trace-1",
                        PacketType.TASK_DISPATCH,
                        "websocket",
                        "worker-1",
                        "task-1",
                        "msg-1",
                        "attempt-1",
                        "crawler.fetch-page",
                        TransportPacket.JSON_CONTENT_TYPE,
                        Map.of(
                                "taskName", "task-name",
                                "project", "demoApp",
                                "userId", "agent",
                                "retryCount", 1,
                                "workerId", "worker-1",
                                "workerContextId", "ctx-1",
                                "batchId", "batch-1",
                                "input", Map.of("target", "target-1"),
                                "sharedConfig", Map.of("debug", true)
                        )
                ),
                10L
        );

        TaskDispatchItem projected = TransportPacketViews.toTaskDispatchItem(envelope.getPacket());

        assertNotNull(projected);
        assertEquals("msg-1", projected.getMessageId());
        assertEquals("attempt-1", projected.attemptId());
        assertEquals("target-1", projected.getInput().get("target"));
    }
}
