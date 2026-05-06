package com.xa.mass.transport.model;

import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TransportDispatchEnvelopeTest {

    @Test
    void constructorNormalizesAdapterRouteAndCorrelationKeys() {
        TransportDispatchEnvelope envelope = new TransportDispatchEnvelope(
                "delivery-1",
                new TransportPacket(
                        TransportPacket.CURRENT_VERSION,
                        "delivery-1",
                        " attempt-1 ",
                        PacketType.TASK_DISPATCH,
                        " WebSocket ",
                        " worker-1 ",
                        "task-1",
                        "msg-1",
                        null,
                        "crawler.fetch-page",
                        TransportPacket.JSON_CONTENT_TYPE,
                        Map.of()
                ),
                item(),
                10L
        );

        assertEquals("websocket", envelope.getAdapterId());
        assertEquals("worker-1", envelope.getRouteKey());
        assertEquals("attempt-1", envelope.getCorrelationKey());
        assertEquals("delivery-1", envelope.getPacket().packetId());
        assertEquals("task-1", envelope.getPacket().taskId());
    }

    @Test
    void constructorCollapsesBlankAdapterRouteAndCorrelationKeysToNull() {
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
                item(),
                10L
        );

        assertNull(envelope.getAdapterId());
        assertNull(envelope.getRouteKey());
        assertNull(envelope.getCorrelationKey());
    }

    @Test
    void packetBackedConstructorProjectsPayloadLazilyWhenCompatibilityViewIsRequested() {
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

        TaskDispatchItem projected = envelope.getPayload();

        assertNotNull(projected);
        assertEquals("msg-1", projected.getMessageId());
        assertEquals("attempt-1", projected.attemptId());
        assertEquals("target-1", projected.getInput().get("target"));
    }

    private TaskDispatchItem item() {
        return new TaskDispatchItem(
                "task-1",
                "msg-1",
                "crawler.fetch-page",
                "task-name",
                "demoApp",
                "agent",
                0,
                "worker-1",
                "ctx-1",
                "batch-1",
                Map.of("target", "target-1"),
                Map.of()
        );
    }
}
