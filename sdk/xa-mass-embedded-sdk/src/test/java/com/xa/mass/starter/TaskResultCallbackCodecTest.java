package com.xa.mass.starter;

import com.xa.mass.sdk.worker.WorkerResultSubmitRequest;
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
        WorkerResultSubmitRequest request = new WorkerResultSubmitRequest(
                "task-1",
                "msg-1",
                true,
                "ok",
                null,
                Map.of("status", "SUCCESS"),
                "attempt-1",
                "lease-1",
                "trace-1"
        );

        TransportResultIngressEnvelope envelope =
                codec.toEnvelope(request, "partition-1", Map.of("adapterId", "polling"));
        TaskResultCallbackCommand decoded = codec.decode(envelope);

        assertEquals("partition-1", envelope.getPartitionKey());
        assertEquals("polling", envelope.diagnostic("adapterId"));
        assertFalse(envelope.getPayload().contains("adapterId"));
        assertEquals("task-1", decoded.taskId());
        assertEquals("msg-1", decoded.messageId());
        assertEquals("attempt-1", decoded.attemptId());
        assertEquals("lease-1", decoded.leaseToken());
        assertEquals("trace-1", decoded.traceId());
        assertEquals("SUCCESS", decoded.output().get("status"));
    }

    @Test
    void decodeUsesDiagnosticTraceWhenCorrelationOmitsTrace() {
        TransportResultIngressEnvelope envelope = TransportResultIngressEnvelope.received(
                """
                {"taskId":"task-1","messageId":"msg-1","success":false,"message":"failed"}
                """,
                """
                {"attemptId":"attempt-1","leaseToken":"lease-1"}
                """,
                "msg-1",
                Map.of("traceId", "diagnostic-trace")
        );

        TaskResultCallbackCommand decoded = codec.decode(envelope);

        assertEquals("failed", decoded.detail());
        assertEquals("diagnostic-trace", decoded.traceId());
    }

    @Test
    void decodeAcceptsAdapterCanonicalResultPayload() {
        TransportResultIngressEnvelope envelope = TransportResultIngressEnvelope.received(
                """
                {
                  "taskId": "task-1",
                  "messageId": "msg-1",
                  "success": true,
                  "detail": "done",
                  "output": {"value": "ok"}
                }
                """,
                null,
                "msg-1",
                null
        );

        TaskResultCallbackCommand decoded = codec.decode(envelope);

        assertEquals("task-1", decoded.taskId());
        assertEquals("msg-1", decoded.messageId());
        assertEquals("done", decoded.detail());
        assertEquals("ok", decoded.output().get("value"));
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

        assertEquals("result callback payload requires taskId, messageId, and success", error.getMessage());
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
}
