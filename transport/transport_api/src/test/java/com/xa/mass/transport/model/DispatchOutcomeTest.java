package com.xa.mass.transport.model;

import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.packet.TransportPacketViews;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchOutcomeTest {

    @Test
    void sentCopiesDispatchIdentityAndNormalizesAdapterId() {
        TransportDispatchEnvelope envelope = envelope();

        DispatchOutcome outcome = DispatchOutcome.sent(" WebSocket ", envelope);

        assertEquals("websocket", outcome.getAdapterId());
        assertEquals("delivery-1", outcome.getDeliveryId());
        assertEquals("worker-1", outcome.getRouteKey());
        assertEquals("attempt-1", outcome.getCorrelationKey());
        assertEquals(DispatchOutcomeStatus.SENT, outcome.getStatus());
        assertFalse(outcome.isRetryable());
        assertNull(outcome.getReason());
    }

    @Test
    void factoryMethodsSetRetryabilityDefaults() {
        TransportDispatchEnvelope envelope = envelope();

        assertFalse(DispatchOutcome.queued("polling", envelope).isRetryable());
        assertTrue(DispatchOutcome.endpointOffline("websocket", envelope, "offline").isRetryable());
        assertTrue(DispatchOutcome.backpressureRejected("polling", envelope, "full").isRetryable());
        assertFalse(DispatchOutcome.invalid("polling", envelope, "bad").isRetryable());
        assertTrue(DispatchOutcome.adapterUnavailable("socket", envelope, "missing").isRetryable());
        assertFalse(DispatchOutcome.failed("socket", envelope, "bad frame", false).isRetryable());
        assertTrue(DispatchOutcome.failed("socket", envelope, "io", true).isRetryable());
    }

    @Test
    void invalidOutcomeToleratesNullEnvelope() {
        DispatchOutcome outcome = DispatchOutcome.invalid(null, null, "missing item");

        assertNull(outcome.getAdapterId());
        assertNull(outcome.getDeliveryId());
        assertNull(outcome.getRouteKey());
        assertNull(outcome.getCorrelationKey());
        assertEquals(DispatchOutcomeStatus.INVALID_ITEM, outcome.getStatus());
        assertEquals("missing item", outcome.getReason());
    }

    private TransportDispatchEnvelope envelope() {
        TaskDispatchItem item = new TaskDispatchItem(
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
        return new TransportDispatchEnvelope(
                "delivery-1",
                new TransportPacket(
                        TransportPacket.CURRENT_VERSION,
                        "delivery-1",
                        "attempt-1",
                        PacketType.TASK_DISPATCH,
                        "polling",
                        "worker-1",
                        item.getTaskId(),
                        item.getMessageId(),
                        item.attemptId(),
                        item.getEventCode(),
                        TransportPacket.JSON_CONTENT_TYPE,
                        TransportPacketViews.dispatchPayload(item.wireView())
                ),
                10L
        );
    }
}
