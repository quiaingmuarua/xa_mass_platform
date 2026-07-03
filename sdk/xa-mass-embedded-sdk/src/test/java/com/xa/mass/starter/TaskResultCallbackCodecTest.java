package com.xa.mass.starter;

import com.xa.mass.sdk.worker.WorkerActionReply;
import com.xa.mass.transport.channel.ResultIngressDiagnostics;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.channel.ResultIngressMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskResultCallbackCodecTest {

    private static final String OUTPUT_FIELD = "output";

    private final TaskResultCallbackCodec codec = new TaskResultCallbackCodec();

    @Test
    void workerResultRequestRoundTripsThroughOpaqueTransportEnvelope() {
        WorkerActionReply request = new WorkerActionReply(
                correlation("task-1", "msg-1", "attempt-1"),
                true,
                null,
                "ok"
        );

        ResultIngressEntry entry = codec.toEntry(request, Map.of("adapterId", "polling"));
        TaskResultCallbackCommand decoded = codec.decode(entry);

        assertEquals(request.replyRef(), entry.partitionKey());
        assertEquals(request.replyRef(), entry.message().resultCorrelationRef());
        assertEquals("polling", entry.diagnostics().get("adapterId"));
        assertFalse(entry.message().payload().contains("adapterId"));
        assertFalse(entry.message().payload().contains("taskId"));
        assertFalse(entry.message().payload().contains("messageId"));
        assertEquals("task-1", decoded.taskId());
        assertEquals("msg-1", decoded.messageId());
        assertEquals("attempt-1", decoded.attemptId());
        assertEquals(null, decoded.leaseToken());
        assertEquals(null, decoded.traceId());
        assertEquals("ok", decoded.output().get("result"));
    }

    @Test
    void decodeUsesDiagnosticTraceFromResultIngressDiagnostics() {
        String resultCorrelationRef = correlation("task-1", "msg-1", "attempt-1");
        ResultIngressEntry entry = envelope(
                """
                {
                  "replyRef": "%s",
                  "success": false,
                  "body": "failed"
                }
                """.formatted(resultCorrelationRef),
                resultCorrelationRef,
                Map.of("traceId", "diagnostic-trace")
        );

        TaskResultCallbackCommand decoded = codec.decode(entry);

        assertEquals("failed", decoded.detail());
        assertEquals("attempt-1", decoded.attemptId());
        assertEquals(null, decoded.leaseToken());
        assertEquals("diagnostic-trace", decoded.traceId());
    }

    @Test
    void decodeAcceptsCanonicalResultPayload() {
        String resultCorrelationRef = correlation("task-1", "msg-1", "attempt-1");
        ResultIngressEntry entry = envelope(
                """
                {
                  "replyRef": "%s",
                  "success": true,
                  "body": "done"
                }
                """.formatted(resultCorrelationRef),
                resultCorrelationRef,
                null
        );

        TaskResultCallbackCommand decoded = codec.decode(entry);

        assertEquals("task-1", decoded.taskId());
        assertEquals("msg-1", decoded.messageId());
        assertEquals(null, decoded.detail());
        assertEquals("done", decoded.output().get("result"));
    }

    @Test
    void decodeRejectsMissingRequiredPayloadIdentity() {
        ResultIngressEntry entry = envelope(
                "{\"taskId\":\"task-1\",\"success\":true}",
                "msg-1",
                null
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> codec.decode(entry));

        assertEquals("result callback payload requires replyRef", error.getMessage());
    }

    @Test
    void decodeRejectsTargetPayloadCorrelationMismatch() {
        String resultCorrelationRef = correlation("task-1", "msg-1", "attempt-1");
        ResultIngressEntry entry = envelope(
                """
                {
                  "replyRef": "%s",
                  "success": true,
                  "body": "done"
                }
                """.formatted(resultCorrelationRef),
                "different-correlation",
                null
        );

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> codec.decode(entry));

        assertEquals("result ingress message correlation must match payload replyRef", error.getMessage());
    }

    @Test
    void decodeDoesNotUsePartitionKeyAsResultTruth() {
        String resultCorrelationRef = correlation("task-1", "msg-1", "attempt-1");
        ResultIngressEntry entry = new ResultIngressEntry(
                "different-partition",
                new ResultIngressMessage(
                        UUID.randomUUID().toString(),
                        resultCorrelationRef,
                        """
                        {
                          "replyRef": "%s",
                          "success": true,
                          "body": "done"
                        }
                        """.formatted(resultCorrelationRef),
                        0L,
                        System.currentTimeMillis()
                ),
                ResultIngressDiagnostics.empty()
        );

        TaskResultCallbackCommand decoded = codec.decode(entry);

        assertEquals("task-1", decoded.taskId());
        assertEquals("msg-1", decoded.messageId());
        assertEquals("done", decoded.output().get("result"));
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
                        Map.of(OUTPUT_FIELD, new Object()),
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

    private static ResultIngressEntry envelope(String payload, String resultCorrelationRef, Map<String, String> diagnostics) {
        return new ResultIngressEntry(
                resultCorrelationRef,
                new ResultIngressMessage(
                        UUID.randomUUID().toString(),
                        resultCorrelationRef,
                        payload,
                        0L,
                        System.currentTimeMillis()
                ),
                new ResultIngressDiagnostics(diagnostics)
        );
    }
}
