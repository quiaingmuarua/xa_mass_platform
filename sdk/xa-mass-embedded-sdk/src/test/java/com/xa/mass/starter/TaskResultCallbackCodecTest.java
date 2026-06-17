package com.xa.mass.starter;

import com.xa.mass.sdk.worker.WorkerResultSubmission;
import com.xa.mass.transport.model.TransportResultIngressEnvelope;
import com.xa.mass.transport.packet.TransportPacket;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskResultCallbackCodecTest {

    private final TaskResultCallbackCodec codec = new TaskResultCallbackCodec();

    @Test
    void workerResultRequestRoundTripsThroughOpaqueTransportEnvelope() {
        WorkerResultSubmission request = new WorkerResultSubmission(
                correlation("task-1", "msg-1", "attempt-1"),
                true,
                null,
                "ok"
        );

        TransportResultIngressEnvelope envelope =
                codec.toEnvelope(request, "partition-1", Map.of("adapterId", "polling"));
        TaskResultCallbackCommand decoded = codec.decode(envelope);

        assertEquals("partition-1", envelope.getPartitionKey());
        assertEquals("polling", envelope.diagnostic("adapterId"));
        assertFalse(envelope.getPayload().contains("adapterId"));
        assertFalse(envelope.getPayload().contains("taskId"));
        assertFalse(envelope.getPayload().contains("messageId"));
        assertEquals("task-1", decoded.taskId());
        assertEquals("msg-1", decoded.messageId());
        assertEquals("attempt-1", decoded.attemptId());
        assertEquals(null, decoded.leaseToken());
        assertEquals(null, decoded.traceId());
        assertEquals("ok", decoded.output().get("result"));
    }

    @Test
    void decodeUsesDiagnosticTraceWhenCorrelationOmitsTrace() {
        TransportResultIngressEnvelope envelope = TransportResultIngressEnvelope.received(
                """
                {
                  "resultCorrelationRef": "%s",
                  "success": false,
                  "message": "failed"
                }
                """.formatted(correlation("task-1", "msg-1", "attempt-1")),
                """
                {"leaseToken":"lease-1"}
                """,
                "partition-1",
                Map.of("traceId", "diagnostic-trace")
        );

        TaskResultCallbackCommand decoded = codec.decode(envelope);

        assertEquals("failed", decoded.detail());
        assertEquals("attempt-1", decoded.attemptId());
        assertEquals("lease-1", decoded.leaseToken());
        assertEquals("diagnostic-trace", decoded.traceId());
    }

    @Test
    void decodeAcceptsCanonicalResultPayload() {
        TransportResultIngressEnvelope envelope = TransportResultIngressEnvelope.received(
                """
                {
                  "resultCorrelationRef": "%s",
                  "success": true,
                  "result": "done"
                }
                """.formatted(correlation("task-1", "msg-1", "attempt-1")),
                null,
                "partition-1",
                null
        );

        TaskResultCallbackCommand decoded = codec.decode(envelope);

        assertEquals("task-1", decoded.taskId());
        assertEquals("msg-1", decoded.messageId());
        assertEquals(null, decoded.detail());
        assertEquals("done", decoded.output().get("result"));
    }

    @Test
    void decodeRejectsMissingRequiredPayloadIdentity() {
        TransportResultIngressEnvelope envelope = TransportResultIngressEnvelope.received(
                "{\"taskId\":\"task-1\",\"success\":true}",
                null,
                "msg-1",
                null
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> codec.decode(envelope));

        assertEquals("result callback payload requires resultCorrelationRef", error.getMessage());
    }

    @Test
    void taskCallbackCommandRejectsUnsupportedOutputValues() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new TaskResultCallbackCommand(
                        "task-1",
                        "msg-1",
                        true,
                        "ok",
                        null,
                        Map.of(TransportPacket.PAYLOAD_OUTPUT, new Object()),
                        null,
                        null,
                        null
                )
        );

        assertEquals("output.output contains unsupported non-JSON value type: java.lang.Object", error.getMessage());
    }

    private static String correlation(String taskId, String messageId, String attemptId) {
        return new TaskDispatchDeliveryCorrelationCodec().encode(
                new TaskDispatchDeliveryCorrelation(taskId, messageId, attemptId, 0)
        );
    }
}
