package com.xa.mass.transport.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransportResultEnvelopeTest {

    @Test
    void fromReportCapturesTransportMetadataAndReportIdentity() {
        TaskResultReport report = report();

        TransportResultEnvelope envelope = TransportResultEnvelope.addressed(
                " WebSocket ",
                " route-1 ",
                " worker-1 ",
                " endpoint-1 ",
                report
        );

        assertEquals("websocket", envelope.getAdapterId());
        assertEquals("route-1", envelope.getRouteKey());
        assertEquals("worker-1", envelope.getWorkerId());
        assertEquals("endpoint-1", envelope.getEndpointId());
        assertNull(envelope.getAttemptId());
        assertNull(envelope.getLeaseToken());
        assertNull(envelope.getTraceId());
        assertSame(report, envelope.getReport());
        assertEquals("task-1", envelope.getTaskId());
        assertEquals("msg-1", envelope.getMessageId());
    }

    @Test
    void diagnosticFieldsTolerateNullAndBlankValues() {
        TransportResultEnvelope envelope = TransportResultEnvelope.addressed(
                "polling",
                "route-1",
                " ",
                "\t",
                report()
        );

        assertEquals("polling", envelope.getAdapterId());
        assertEquals("route-1", envelope.getRouteKey());
        assertNull(envelope.getWorkerId());
        assertNull(envelope.getEndpointId());
        assertNull(envelope.getAttemptId());
        assertNull(envelope.getLeaseToken());
        assertNull(envelope.getTraceId());
    }

    @Test
    void fromDispatchContextCarriesAttemptIdentityWithoutChangingReportPayload() {
        TaskResultReport report = report();

        TransportResultEnvelope envelope = new TransportResultEnvelope(
                " Polling ",
                " route-1 ",
                " worker-1 ",
                " endpoint-1 ",
                " attempt-1 ",
                null,
                null,
                report
        );

        assertEquals("polling", envelope.getAdapterId());
        assertEquals("route-1", envelope.getRouteKey());
        assertEquals("worker-1", envelope.getWorkerId());
        assertEquals("endpoint-1", envelope.getEndpointId());
        assertEquals("attempt-1", envelope.getAttemptId());
        assertNull(envelope.getLeaseToken());
        assertNull(envelope.getTraceId());
        assertSame(report, envelope.getReport());
    }

    @Test
    void fromReportCarriesTraceIdWhenProvided() {
        TransportResultEnvelope envelope = new TransportResultEnvelope(
                "polling",
                "route-1",
                "worker-1",
                "endpoint-1",
                null,
                null,
                " trace-123 ",
                report()
        );

        assertNull(envelope.getAttemptId());
        assertEquals("trace-123", envelope.getTraceId());
    }

    @Test
    void fromDispatchContextCarriesTraceIdWhenProvided() {
        TransportResultEnvelope envelope = new TransportResultEnvelope(
                "polling",
                "route-1",
                "worker-1",
                "endpoint-1",
                "attempt-1",
                null,
                " trace-attempt-1 ",
                report()
        );

        assertEquals("attempt-1", envelope.getAttemptId());
        assertNull(envelope.getLeaseToken());
        assertEquals("trace-attempt-1", envelope.getTraceId());
    }

    @Test
    void reportIsRequired() {
        assertThrows(NullPointerException.class,
                () -> TransportResultEnvelope.addressed("polling", "route-1", "worker-1", "endpoint-1", null));
    }

    @Test
    void canonicalTransportAddressIsRequired() {
        IllegalArgumentException adapterIdError = assertThrows(
                IllegalArgumentException.class,
                () -> TransportResultEnvelope.addressed(" ", "route-1", "worker-1", "endpoint-1", report())
        );
        assertEquals("adapterId must not be blank", adapterIdError.getMessage());

        IllegalArgumentException routeKeyError = assertThrows(
                IllegalArgumentException.class,
                () -> TransportResultEnvelope.addressed("polling", " ", "worker-1", "endpoint-1", report())
        );
        assertEquals("routeKey must not be blank", routeKeyError.getMessage());
    }

    private TaskResultReport report() {
        return new TaskResultReport(
                "task-1",
                "msg-1",
                true,
                "ok",
                null,
                Map.of("status", "SUCCESS")
        );
    }
}

