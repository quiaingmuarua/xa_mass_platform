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

        TransportResultEnvelope envelope = TransportResultEnvelope.fromReport(
                " WebSocket ",
                " worker-1 ",
                " endpoint-1 ",
                report
        );

        assertEquals("websocket", envelope.getAdapterId());
        assertEquals("worker-1", envelope.getWorkerId());
        assertEquals("endpoint-1", envelope.getEndpointId());
        assertNull(envelope.getAttemptId());
        assertNull(envelope.getLeaseToken());
        assertSame(report, envelope.getReport());
        assertEquals("task-1", envelope.getTaskId());
        assertEquals("msg-1", envelope.getMessageId());
    }

    @Test
    void metadataFieldsTolerateNullAndBlankValues() {
        TransportResultEnvelope envelope = TransportResultEnvelope.fromReport(
                " ",
                null,
                "\t",
                report()
        );

        assertNull(envelope.getAdapterId());
        assertNull(envelope.getWorkerId());
        assertNull(envelope.getEndpointId());
        assertNull(envelope.getAttemptId());
        assertNull(envelope.getLeaseToken());
    }

    @Test
    void fromDispatchContextCarriesAttemptIdentityWithoutChangingReportPayload() {
        TaskResultReport report = report();

        TransportResultEnvelope envelope = TransportResultEnvelope.fromDispatchContext(
                " Polling ",
                " worker-1 ",
                " endpoint-1 ",
                " attempt-1 ",
                report
        );

        assertEquals("polling", envelope.getAdapterId());
        assertEquals("worker-1", envelope.getWorkerId());
        assertEquals("endpoint-1", envelope.getEndpointId());
        assertEquals("attempt-1", envelope.getAttemptId());
        assertNull(envelope.getLeaseToken());
        assertSame(report, envelope.getReport());
    }

    @Test
    void reportIsRequired() {
        assertThrows(NullPointerException.class,
                () -> TransportResultEnvelope.fromReport("polling", "worker-1", "endpoint-1", null));
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
