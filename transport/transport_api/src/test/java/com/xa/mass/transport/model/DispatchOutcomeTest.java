package com.xa.mass.transport.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchOutcomeTest {

    @Test
    void deliveredCopiesDeliveryIdentityAndNormalizesAdapterId() {
        AdapterDispatchRequest request = request();

        DispatchOutcome outcome = DispatchOutcome.delivered(" WebSocket ", request);

        assertEquals("websocket", outcome.getAdapterId());
        assertEquals("delivery-1", outcome.getDeliveryId());
        assertEquals("worker-1", outcome.getSelectedWorkerId());
        assertNull(outcome.getDeliveryQueueKey());
        assertEquals("group-route-1", outcome.getRouteKey());
        assertEquals("attempt-1", outcome.getAttemptId());
        assertEquals("task-1", outcome.getTaskId());
        assertEquals("msg-1", outcome.getMessageId());
        assertEquals(1, outcome.getAttemptNo());
        assertEquals(DispatchOutcomeStatus.DELIVERED, outcome.getStatus());
        assertFalse(outcome.isRetryable());
        assertNull(outcome.getReason());
        assertTrue(outcome.getOccurredAtEpochMillis() > 0L);
    }

    @Test
    void factoryMethodsSetRetryabilityDefaults() {
        AdapterDispatchRequest request = request();

        assertFalse(DispatchOutcome.queued("polling", "lane-1", "delivery-1", "worker-1", "attempt-1", "task-1", "msg-1", 1).isRetryable());
        assertTrue(DispatchOutcome.noEndpoint("websocket", request, "offline").isRetryable());
        assertTrue(DispatchOutcome.backpressure("polling", "lane-1", "delivery-1", "worker-1", "attempt-1", "task-1", "msg-1", 1, "full").isRetryable());
        assertFalse(DispatchOutcome.invalid("polling", request, "bad").isRetryable());
        assertTrue(DispatchOutcome.unavailable("socket", request, "missing").isRetryable());
        assertFalse(DispatchOutcome.failed("socket", request, "bad frame", false).isRetryable());
        assertTrue(DispatchOutcome.failed("socket", request, "io", true).isRetryable());
    }

    @Test
    void queuedOutcomeCanCarryExplicitStoreQueueContext() {
        DispatchOutcome outcome = DispatchOutcome.queued("polling", "lane-1", "delivery-1", "worker-1", "attempt-1", "task-1", "msg-1", 1);

        assertEquals("lane-1", outcome.getDeliveryQueueKey());
        assertEquals("task-1", outcome.getTaskId());
        assertEquals("msg-1", outcome.getMessageId());
        assertEquals(1, outcome.getAttemptNo());
        assertEquals(DispatchOutcomeStatus.QUEUED, outcome.getStatus());
        assertFalse(outcome.isRetryable());
    }

    @Test
    void invalidOutcomeToleratesNullEnvelope() {
        DispatchOutcome outcome = DispatchOutcome.invalid(null, null, null, null, null, null, null, 0, "missing item");

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
                " task-2 ",
                " msg-2 ",
                2,
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
        assertEquals("task-2", outcome.getTaskId());
        assertEquals("msg-2", outcome.getMessageId());
        assertEquals(2, outcome.getAttemptNo());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, outcome.getStatus());
        assertTrue(outcome.isRetryable());
        assertEquals("missing endpoint", outcome.getReason());
        assertEquals("node-1", outcome.getTransportNodeId());
        assertEquals("conn-1", outcome.getConnectionId());
        assertEquals(42L, outcome.getOccurredAtEpochMillis());
    }

    private AdapterDispatchRequest request() {
        return new AdapterDispatchRequest(
                "delivery-1",
                "polling",
                "worker-1",
                new TaskDispatchContent(
                        "task-1",
                        "msg-1",
                        "crawler.fetch-page",
                        Map.of("target", "target-1"),
                        Map.of()
                ),
                new TaskDispatchExecutionContext("attempt-1", 1, 0, "batch-1", null, null, null),
                new AdapterEndpoint("group-route-1", "node-1", "conn-1", 10_000L),
                10L
        );
    }
}

