package com.xa.mass.transport.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchOutcomeTest {

    @Test
    void deliveredCopiesOnlyStableDeliveryIdentityAndCorrelation() {
        AdapterDispatchRequest request = request();

        DispatchOutcome outcome = DispatchOutcome.delivered(request);

        assertEquals("delivery-1", outcome.getDeliveryId());
        assertEquals("worker-1", outcome.getSelectedWorkerId());
        assertEquals("corr-1", outcome.getCorrelationRef());
        assertEquals(DispatchOutcomeStatus.DELIVERED, outcome.getStatus());
        assertFalse(outcome.isRetryable());
        assertNull(outcome.getReason());
        assertTrue(outcome.getOccurredAtEpochMillis() > 0L);
    }

    @Test
    void factoryMethodsSetRetryabilityDefaults() {
        AdapterDispatchRequest request = request();

        assertFalse(DispatchOutcome.queued("delivery-1", "worker-1", "corr-1").isRetryable());
        assertTrue(DispatchOutcome.noEndpoint(request, "offline").isRetryable());
        assertTrue(DispatchOutcome.backpressure("delivery-1", "worker-1", "corr-1", "full").isRetryable());
        assertFalse(DispatchOutcome.invalid(request, "bad").isRetryable());
        assertTrue(DispatchOutcome.unavailable(request, "missing").isRetryable());
        assertFalse(DispatchOutcome.failed(request, "bad frame", false).isRetryable());
        assertTrue(DispatchOutcome.failed(request, "io", true).isRetryable());
    }

    @Test
    void queuedOutcomeCarriesNoLaneEndpointOrTaskFacts() {
        DispatchOutcome outcome = DispatchOutcome.queued("delivery-1", "worker-1", "corr-1");

        assertEquals("delivery-1", outcome.getDeliveryId());
        assertEquals("worker-1", outcome.getSelectedWorkerId());
        assertEquals("corr-1", outcome.getCorrelationRef());
        assertEquals(DispatchOutcomeStatus.QUEUED, outcome.getStatus());
        assertFalse(outcome.isRetryable());
    }

    @Test
    void invalidOutcomeToleratesNullEnvelope() {
        DispatchOutcome outcome = DispatchOutcome.invalid(null, null, null, "missing item");

        assertNull(outcome.getDeliveryId());
        assertNull(outcome.getSelectedWorkerId());
        assertNull(outcome.getCorrelationRef());
        assertEquals(DispatchOutcomeStatus.INVALID, outcome.getStatus());
        assertEquals("missing item", outcome.getReason());
    }

    @Test
    void explicitConstructorNormalizesStableDeliveryIdentity() {
        DispatchOutcome outcome = new DispatchOutcome(
                " delivery-2 ",
                " worker-2 ",
                " corr-2 ",
                DispatchOutcomeStatus.NO_ENDPOINT,
                true,
                "missing endpoint",
                42L
        );

        assertEquals("delivery-2", outcome.getDeliveryId());
        assertEquals("worker-2", outcome.getSelectedWorkerId());
        assertEquals("corr-2", outcome.getCorrelationRef());
        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, outcome.getStatus());
        assertTrue(outcome.isRetryable());
        assertEquals("missing endpoint", outcome.getReason());
        assertEquals(42L, outcome.getOccurredAtEpochMillis());
    }

    @Test
    void contractDoesNotExposeTransportOwnerOrTaskIds() {
        Set<String> methods = Arrays.stream(DispatchOutcome.class.getMethods())
                .map(method -> method.getName())
                .collect(Collectors.toSet());

        assertFalse(methods.contains("getAdapterId"));
        assertFalse(methods.contains("getDeliveryQueueKey"));
        assertFalse(methods.contains("getRouteKey"));
        assertFalse(methods.contains("getTransportNodeId"));
        assertFalse(methods.contains("getConnectionId"));
        assertFalse(methods.contains("getTaskId"));
        assertFalse(methods.contains("getMessageId"));
        assertFalse(methods.contains("getAttemptId"));
        assertFalse(methods.contains("getAttemptNo"));
    }

    private AdapterDispatchRequest request() {
        return new AdapterDispatchRequest(
                "delivery-1",
                "bucket-1",
                "worker-1",
                "{\"messageId\":\"msg-1\"}",
                "corr-1",
                10L
        );
    }
}
