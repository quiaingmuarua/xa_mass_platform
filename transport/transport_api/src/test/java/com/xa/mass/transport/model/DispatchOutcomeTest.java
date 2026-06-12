package com.xa.mass.transport.model;

import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchOutcomeTest {

    @Test
    void deliveredCopiesDeliveryIdentityAndNormalizesAdapterId() {
        TransportDispatchEnvelope envelope = envelope();

        DispatchOutcome outcome = DispatchOutcome.delivered(" WebSocket ", envelope);

        assertEquals("websocket", outcome.getAdapterId());
        assertEquals("delivery-1", outcome.getDeliveryId());
        assertEquals("worker-1", outcome.getSelectedWorkerId());
        assertNull(outcome.getDeliveryQueueKey());
        assertEquals("group-route-1", outcome.getRouteKey());
        assertEquals("attempt-1", outcome.getAttemptId());
        assertEquals(DispatchOutcomeStatus.DELIVERED, outcome.getStatus());
        assertFalse(outcome.isRetryable());
        assertNull(outcome.getReason());
        assertTrue(outcome.getOccurredAtEpochMillis() > 0L);
    }

    @Test
    void factoryMethodsSetRetryabilityDefaults() {
        TransportDispatchEnvelope envelope = envelope();

        assertFalse(DispatchOutcome.queued("polling", envelope).isRetryable());
        assertTrue(DispatchOutcome.noEndpoint("websocket", envelope, "offline").isRetryable());
        assertTrue(DispatchOutcome.backpressure("polling", envelope, "full").isRetryable());
        assertFalse(DispatchOutcome.invalid("polling", envelope, "bad").isRetryable());
        assertTrue(DispatchOutcome.unavailable("socket", envelope, "missing").isRetryable());
        assertFalse(DispatchOutcome.failed("socket", envelope, "bad frame", false).isRetryable());
        assertTrue(DispatchOutcome.failed("socket", envelope, "io", true).isRetryable());
    }

    @Test
    void queuedOutcomeCanCarryExplicitStoreQueueContext() {
        TransportDispatchEnvelope envelope = envelope();

        DispatchOutcome outcome = DispatchOutcome.queued("polling", "lane-1", envelope);

        assertEquals("lane-1", outcome.getDeliveryQueueKey());
        assertEquals(DispatchOutcomeStatus.QUEUED, outcome.getStatus());
        assertFalse(outcome.isRetryable());
    }

    @Test
    void invalidOutcomeToleratesNullEnvelope() {
        DispatchOutcome outcome = DispatchOutcome.invalid(null, null, "missing item");

        assertNull(outcome.getAdapterId());
        assertNull(outcome.getDeliveryId());
        assertNull(outcome.getSelectedWorkerId());
        assertNull(outcome.getDeliveryQueueKey());
        assertNull(outcome.getRouteKey());
        assertNull(outcome.getAttemptId());
        assertEquals(DispatchOutcomeStatus.INVALID, outcome.getStatus());
        assertEquals("missing item", outcome.getReason());
    }

    @Test
    void explicitConstructorCarriesExecutorEvidence() {
        DispatchOutcome outcome = new DispatchOutcome(
                " delivery-2 ",
                " SOCKET ",
                " worker-2 ",
                " lane-1 ",
                " route-2 ",
                " attempt-2 ",
                DispatchOutcomeStatus.NO_ENDPOINT,
                true,
                "missing endpoint",
                " node-1 ",
                " conn-1 ",
                42L
        );

        assertEquals("delivery-2", outcome.getDeliveryId());
        assertEquals("socket", outcome.getAdapterId());
        assertEquals("worker-2", outcome.getSelectedWorkerId());
        assertEquals("lane-1", outcome.getDeliveryQueueKey());
        assertEquals("route-2", outcome.getRouteKey());
        assertEquals("attempt-2", outcome.getAttemptId());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, outcome.getStatus());
        assertTrue(outcome.isRetryable());
        assertEquals("missing endpoint", outcome.getReason());
        assertEquals("node-1", outcome.getTransportNodeId());
        assertEquals("conn-1", outcome.getConnectionId());
        assertEquals(42L, outcome.getOccurredAtEpochMillis());
    }

    private TransportDispatchEnvelope envelope() {
        return new TransportDispatchEnvelope(
                "delivery-1",
                "worker-1",
                new TransportPacket(
                        TransportPacket.CURRENT_VERSION,
                        "delivery-1",
                        "attempt-1",
                        PacketType.TASK_DISPATCH,
                        "polling",
                        "group-route-1",
                        "task-1",
                        "msg-1",
                        "attempt-1",
                        "crawler.fetch-page",
                        TransportPacket.JSON_CONTENT_TYPE,
                        Map.of(
                                TransportPacket.PAYLOAD_RETRY_COUNT, 0,
                                TransportPacket.PAYLOAD_INPUT, Map.of("target", "target-1"),
                                TransportPacket.PAYLOAD_SHARED_CONFIG, Map.of()
                        )
                ),
                10L
        );
    }
}

