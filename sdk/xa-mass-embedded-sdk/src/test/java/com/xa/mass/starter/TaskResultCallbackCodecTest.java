package com.xa.mass.starter;

import com.xa.mass.sdk.worker.WorkerResultSubmission;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.routing.RoutingEnvelope;
import com.xa.mass.transport.routing.RoutingOwnerKinds;
import com.xa.mass.transport.routing.RoutingTarget;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

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

        RoutingEnvelope envelope = codec.toEnvelope(request, Map.of("adapterId", "polling"));
        TaskResultCallbackCommand decoded = codec.decode(envelope);

        assertEquals(RoutingOwnerKinds.RESULT_INGRESS, envelope.target().ownerKind());
        assertEquals(request.resultCorrelationRef(), envelope.target().ownerRef());
        assertEquals("polling", envelope.diagnostics().get("adapterId"));
        assertFalse(envelope.payload().contains("adapterId"));
        assertFalse(envelope.payload().contains("taskId"));
        assertFalse(envelope.payload().contains("messageId"));
        assertEquals("task-1", decoded.taskId());
        assertEquals("msg-1", decoded.messageId());
        assertEquals("attempt-1", decoded.attemptId());
        assertEquals(null, decoded.leaseToken());
        assertEquals(null, decoded.traceId());
        assertEquals("ok", decoded.output().get("result"));
    }

    @Test
    void decodeUsesDiagnosticTraceFromRoutingEnvelopeDiagnostics() {
        String resultCorrelationRef = correlation("task-1", "msg-1", "attempt-1");
        RoutingEnvelope envelope = envelope(
                """
                {
                  "resultCorrelationRef": "%s",
                  "success": false,
                  "message": "failed"
                }
                """.formatted(resultCorrelationRef),
                resultCorrelationRef,
                Map.of("traceId", "diagnostic-trace")
        );

        TaskResultCallbackCommand decoded = codec.decode(envelope);

        assertEquals("failed", decoded.detail());
        assertEquals("attempt-1", decoded.attemptId());
        assertEquals(null, decoded.leaseToken());
        assertEquals("diagnostic-trace", decoded.traceId());
    }

    @Test
    void decodeAcceptsCanonicalResultPayload() {
        String resultCorrelationRef = correlation("task-1", "msg-1", "attempt-1");
        RoutingEnvelope envelope = envelope(
                """
                {
                  "resultCorrelationRef": "%s",
                  "success": true,
                  "result": "done"
                }
                """.formatted(resultCorrelationRef),
                resultCorrelationRef,
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
        RoutingEnvelope envelope = envelope(
                "{\"taskId\":\"task-1\",\"success\":true}",
                "msg-1",
                null
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> codec.decode(envelope));

        assertEquals("result callback payload requires resultCorrelationRef", error.getMessage());
    }

    @Test
    void decodeRejectsTargetPayloadCorrelationMismatch() {
        String resultCorrelationRef = correlation("task-1", "msg-1", "attempt-1");
        RoutingEnvelope envelope = envelope(
                """
                {
                  "resultCorrelationRef": "%s",
                  "success": true,
                  "result": "done"
                }
                """.formatted(resultCorrelationRef),
                "different-correlation",
                null
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> codec.decode(envelope));

        assertEquals("result envelope target ownerRef must match payload resultCorrelationRef", error.getMessage());
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

    private static RoutingEnvelope envelope(String payload, String resultCorrelationRef, Map<String, String> diagnostics) {
        return new RoutingEnvelope(
                UUID.randomUUID().toString(),
                RoutingTarget.resultIngress(resultCorrelationRef),
                payload,
                diagnostics,
                System.currentTimeMillis()
        );
    }
}
