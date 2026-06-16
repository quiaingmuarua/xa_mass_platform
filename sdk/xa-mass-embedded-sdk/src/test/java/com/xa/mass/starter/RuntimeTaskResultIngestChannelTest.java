package com.xa.mass.starter;

import com.xa.mass.base.runtime.result.TaskResultCorrelation;
import com.xa.mass.base.runtime.result.TaskResultIngestFacade;
import com.xa.mass.sdk.worker.WorkerResultSubmitRequest;
import com.xa.mass.transport.channel.TransportResultIngressOutcome;
import com.xa.mass.transport.model.TransportResultIngressEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeTaskResultIngestChannelTest {

    private static final TaskResultCallbackCodec CODEC = new TaskResultCallbackCodec();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void nullIngressIsRetryableFailure() {
        RuntimeTaskResultIngestChannel channel = new RuntimeTaskResultIngestChannel(new RecordingResultIngestFacade(null));

        assertEquals(ResultIngressHandleOutcome.RETRYABLE_FAILURE, channel.handleResult(null));
        assertEquals(TransportResultIngressOutcome.RETRYABLE_FAILURE, channel.handle(null));
    }

    @Test
    void opaqueEnvelopeDelegatesToFacade() {
        RecordingResultIngestFacade facade = new RecordingResultIngestFacade(null);
        RuntimeTaskResultIngestChannel channel = new RuntimeTaskResultIngestChannel(facade);

        ResultIngressHandleOutcome handled = channel.handleResult(envelope(request(
                "task-1",
                "msg-1",
                true,
                "ok",
                null,
                null,
                null,
                null
        )));

        assertEquals(ResultIngressHandleOutcome.HANDLED_APPLIED, handled);
        assertEquals(1, facade.ingestCalls.get());
        assertEquals("task-1", facade.lastTaskId);
        assertEquals("msg-1", facade.lastMessageId);
        assertTrue(facade.lastSuccess);
        assertEquals("ok", facade.lastDetail);
        assertNull(facade.lastErrorCode);
        assertEquals("SUCCESS", facade.lastOutput.get("status"));
        assertEquals(0, facade.correlationCalls.get());
    }

    @Test
    void envelopeWithoutIdentityDelegatesAndRestoresTraceMdc() {
        MDC.put("traceId", "outer-trace");
        RecordingResultIngestFacade facade = new RecordingResultIngestFacade(null);
        RuntimeTaskResultIngestChannel channel = new RuntimeTaskResultIngestChannel(facade);

        ResultIngressHandleOutcome handled = channel.handleResult(envelope(request(
                "task-2",
                "msg-2",
                true,
                "ok",
                null,
                null,
                null,
                "transport-trace"
        )));

        assertEquals(ResultIngressHandleOutcome.HANDLED_APPLIED, handled);
        assertEquals(1, facade.ingestCalls.get());
        assertEquals(0, facade.correlationCalls.get());
        assertEquals("outer-trace", MDC.get("traceId"));
    }

    @Test
    void matchingLeaseIdentityDelegatesToFacade() {
        RecordingResultIngestFacade facade = new RecordingResultIngestFacade(TaskResultCorrelation.workerLevel(
                "task-3",
                "msg-3",
                null,
                "lease-3",
                "worker-3",
                "batch-3"
        ));
        RuntimeTaskResultIngestChannel channel = new RuntimeTaskResultIngestChannel(facade);

        ResultIngressHandleOutcome handled = channel.handleResult(envelope(request(
                "task-3",
                "msg-3",
                true,
                "ok",
                null,
                null,
                "lease-3",
                null
        )));

        assertEquals(ResultIngressHandleOutcome.HANDLED_APPLIED, handled);
        assertEquals(1, facade.correlationCalls.get());
        assertEquals(1, facade.ingestCalls.get());
    }

    @Test
    void matchingAttemptIdentityDelegatesToFacade() {
        RecordingResultIngestFacade facade = new RecordingResultIngestFacade(TaskResultCorrelation.workerLevel(
                "task-4",
                "msg-4",
                "attempt-4",
                "lease-4",
                "worker-4",
                "batch-4"
        ));
        RuntimeTaskResultIngestChannel channel = new RuntimeTaskResultIngestChannel(facade);

        ResultIngressHandleOutcome handled = channel.handleResult(envelope(request(
                "task-4",
                "msg-4",
                true,
                "ok",
                null,
                "attempt-4",
                "lease-4",
                null
        )));

        assertEquals(ResultIngressHandleOutcome.HANDLED_APPLIED, handled);
        assertEquals(1, facade.correlationCalls.get());
        assertEquals(1, facade.ingestCalls.get());
    }

    @Test
    void identityMismatchIsAcceptedNoopWithoutEngineApply() {
        RecordingResultIngestFacade facade = new RecordingResultIngestFacade(TaskResultCorrelation.workerLevel(
                "task-5",
                "msg-5",
                "attempt-5",
                "lease-5",
                "worker-5",
                "batch-5"
        ));
        RuntimeTaskResultIngestChannel channel = new RuntimeTaskResultIngestChannel(facade);

        ResultIngressHandleOutcome handled = channel.handleResult(envelope(request(
                "task-5",
                "msg-5",
                true,
                "ok",
                null,
                "wrong-attempt",
                "lease-5",
                null
        )));

        assertEquals(ResultIngressHandleOutcome.HANDLED_NOOP, handled);
        assertEquals(1, facade.correlationCalls.get());
        assertEquals(0, facade.ingestCalls.get());
        assertEquals(TransportResultIngressOutcome.ACKNOWLEDGED, handled.toTransportOutcome());
    }

    @Test
    void missingActiveLeaseIsAcceptedNoopWithoutEngineApply() {
        RecordingResultIngestFacade facade = new RecordingResultIngestFacade(
                TaskResultCorrelation.noActiveLease("task-6", "msg-6")
        );
        RuntimeTaskResultIngestChannel channel = new RuntimeTaskResultIngestChannel(facade);

        ResultIngressHandleOutcome handled = channel.handleResult(envelope(request(
                "task-6",
                "msg-6",
                true,
                "ok",
                null,
                "attempt-6",
                "lease-6",
                null
        )));

        assertEquals(ResultIngressHandleOutcome.HANDLED_NOOP, handled);
        assertEquals(1, facade.correlationCalls.get());
        assertEquals(0, facade.ingestCalls.get());
    }

    @Test
    void invalidPayloadIsPermanentRejectAndAckable() {
        RuntimeTaskResultIngestChannel channel = new RuntimeTaskResultIngestChannel(new RecordingResultIngestFacade(null));

        ResultIngressHandleOutcome handled = channel.handleResult(
                TransportResultIngressEnvelope.received("{", null, "msg-1", null)
        );

        assertEquals(ResultIngressHandleOutcome.PERMANENT_REJECT, handled);
        assertEquals(TransportResultIngressOutcome.ACKNOWLEDGED, handled.toTransportOutcome());
    }

    @Test
    void facadeFalseIsPermanentRejectInsteadOfRetryLoop() {
        RecordingResultIngestFacade facade = new RecordingResultIngestFacade(null);
        facade.applyResult = false;
        RuntimeTaskResultIngestChannel channel = new RuntimeTaskResultIngestChannel(facade);

        ResultIngressHandleOutcome handled = channel.handleResult(envelope(request(
                "task-7",
                "msg-7",
                false,
                "failed",
                "ERR",
                null,
                null,
                null
        )));

        assertEquals(ResultIngressHandleOutcome.PERMANENT_REJECT, handled);
        assertEquals(TransportResultIngressOutcome.ACKNOWLEDGED, handled.toTransportOutcome());
    }

    private static TransportResultIngressEnvelope envelope(WorkerResultSubmitRequest request) {
        return CODEC.toEnvelope(request, request.messageId(), Map.of("adapterId", "polling"));
    }

    private static WorkerResultSubmitRequest request(String taskId,
                                                     String messageId,
                                                     boolean success,
                                                     String detail,
                                                     String errorCode,
                                                     String attemptId,
                                                     String leaseToken,
                                                     String traceId) {
        return new WorkerResultSubmitRequest(
                taskId,
                messageId,
                success,
                detail,
                errorCode,
                Map.of("status", success ? "SUCCESS" : "FAILED", "mockData", detail),
                attemptId,
                leaseToken,
                traceId
        );
    }

    private static final class RecordingResultIngestFacade implements TaskResultIngestFacade {
        private final TaskResultCorrelation correlation;
        private final AtomicInteger ingestCalls = new AtomicInteger();
        private final AtomicInteger correlationCalls = new AtomicInteger();
        private boolean applyResult = true;
        private String lastTaskId;
        private String lastMessageId;
        private boolean lastSuccess;
        private String lastDetail;
        private String lastErrorCode;
        private Map<String, Object> lastOutput;

        private RecordingResultIngestFacade(TaskResultCorrelation correlation) {
            this.correlation = correlation;
        }

        @Override
        public boolean ingestTaskResult(String taskId,
                                        String messageId,
                                        boolean success,
                                        String detail,
                                        String errorCode,
                                        Map<String, Object> output) {
            ingestCalls.incrementAndGet();
            lastTaskId = taskId;
            lastMessageId = messageId;
            lastSuccess = success;
            lastDetail = detail;
            lastErrorCode = errorCode;
            lastOutput = output;
            return applyResult;
        }

        @Override
        public TaskResultCorrelation getResultCorrelation(String taskId, String messageId) {
            correlationCalls.incrementAndGet();
            return correlation;
        }
    }
}
