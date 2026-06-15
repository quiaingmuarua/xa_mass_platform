package com.xa.mass.transport.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchOutcomeTest {

    @Test
    void deliveredCopiesOnlyStableDeliveryIdentity() {
        AdapterDispatchRequest request = request();

        DispatchOutcome outcome = DispatchOutcome.delivered(request);

        assertEquals("delivery-1", outcome.getDeliveryId());
        assertEquals("worker-1", outcome.getSelectedWorkerId());
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

        assertFalse(DispatchOutcome.queued("delivery-1", "worker-1", "attempt-1", "task-1", "msg-1", 1).isRetryable());
        assertTrue(DispatchOutcome.noEndpoint(request, "offline").isRetryable());
        assertTrue(DispatchOutcome.backpressure("delivery-1", "worker-1", "attempt-1", "task-1", "msg-1", 1, "full").isRetryable());
        assertFalse(DispatchOutcome.invalid(request, "bad").isRetryable());
        assertTrue(DispatchOutcome.unavailable(request, "missing").isRetryable());
        assertFalse(DispatchOutcome.failed(request, "bad frame", false).isRetryable());
        assertTrue(DispatchOutcome.failed(request, "io", true).isRetryable());
    }

    @Test
    void queuedOutcomeCarriesNoLaneOrEndpointFacts() {
        DispatchOutcome outcome = DispatchOutcome.queued("delivery-1", "worker-1", "attempt-1", "task-1", "msg-1", 1);

        assertEquals("delivery-1", outcome.getDeliveryId());
        assertEquals("worker-1", outcome.getSelectedWorkerId());
        assertEquals("task-1", outcome.getTaskId());
        assertEquals("msg-1", outcome.getMessageId());
        assertEquals(1, outcome.getAttemptNo());
        assertEquals(DispatchOutcomeStatus.QUEUED, outcome.getStatus());
        assertFalse(outcome.isRetryable());
    }

    @Test
    void invalidOutcomeToleratesNullEnvelope() {
        DispatchOutcome outcome = DispatchOutcome.invalid(null, null, null, null, null, 0, "missing item");

        assertNull(outcome.getDeliveryId());
        assertNull(outcome.getSelectedWorkerId());
        assertNull(outcome.getAttemptId());
        assertEquals(DispatchOutcomeStatus.INVALID, outcome.getStatus());
        assertEquals("missing item", outcome.getReason());
    }

    @Test
    void explicitConstructorNormalizesStableDeliveryIdentity() {
        DispatchOutcome outcome = new DispatchOutcome(
                " delivery-2 ",
                " worker-2 ",
                " attempt-2 ",
                " task-2 ",
                " msg-2 ",
                2,
                DispatchOutcomeStatus.NO_ENDPOINT,
                true,
                "missing endpoint",
                42L
        );

        assertEquals("delivery-2", outcome.getDeliveryId());
        assertEquals("worker-2", outcome.getSelectedWorkerId());
        assertEquals("attempt-2", outcome.getAttemptId());
        assertEquals("task-2", outcome.getTaskId());
        assertEquals("msg-2", outcome.getMessageId());
        assertEquals(2, outcome.getAttemptNo());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, outcome.getStatus());
        assertTrue(outcome.isRetryable());
        assertEquals("missing endpoint", outcome.getReason());
        assertEquals(42L, outcome.getOccurredAtEpochMillis());
    }

    @Test
    void contractDoesNotExposeTransportOwnerIds() {
        Set<String> methods = Arrays.stream(DispatchOutcome.class.getMethods())
                .map(method -> method.getName())
                .collect(Collectors.toSet());

        assertFalse(methods.contains("getAdapterId"));
        assertFalse(methods.contains("getDeliveryQueueKey"));
        assertFalse(methods.contains("getRouteKey"));
        assertFalse(methods.contains("getTransportNodeId"));
        assertFalse(methods.contains("getConnectionId"));
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
                new TaskDispatchExecutionContext("attempt-1", 1, 0, "batch-1"),
                new AdapterEndpoint("group-route-1", "node-1", "conn-1", 10_000L),
                10L
        );
    }
}
