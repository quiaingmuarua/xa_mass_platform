package com.xa.mass.starter;

import com.xa.mass.base.runtime.result.TaskResultCorrelation;
import com.xa.mass.base.runtime.result.TaskResultIngestFacade;
import com.xa.mass.sdk.worker.WorkerActionReply;
import com.xa.mass.transport.channel.ResultIngressDiagnostics;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.channel.ResultIngressMessage;
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
    void nullIngressIsTransientStarterFailure() {
        RuntimeTaskResultIngestChannel channel = new RuntimeTaskResultIngestChannel(new RecordingResultIngestFacade(null));

        assertEquals(ResultIngressHandleOutcome.TRANSIENT_FAILURE, channel.handleResult(null));
        channel.handle(null);
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
        assertNull(facade.lastDetail);
        assertNull(facade.lastErrorCode);
        assertEquals("ok", facade.lastOutput.get("result"));
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
                "attempt-3",
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
                "attempt-3",
                null,
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

        ResultIngressHandleOutcome handled = channel.handleResult(rawEnvelope(
                "task-4",
                "msg-4",
                true,
                "ok",
                "attempt-4",
                "lease-4"
        ));

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
    }

    @Test
    void leaseIdentityMismatchIsAcceptedNoopWithoutEngineApply() {
        RecordingResultIngestFacade facade = new RecordingResultIngestFacade(TaskResultCorrelation.workerLevel(
                "task-5-lease",
                "msg-5-lease",
                "attempt-5-lease",
                "active-lease-token",
                "worker-5",
                "batch-5"
        ));
        RuntimeTaskResultIngestChannel channel = new RuntimeTaskResultIngestChannel(facade);

        ResultIngressHandleOutcome handled = channel.handleResult(rawEnvelope(
                "task-5-lease",
                "msg-5-lease",
                true,
                "late stale result",
                "attempt-5-lease",
                "stale-lease-token"
        ));

        assertEquals(ResultIngressHandleOutcome.HANDLED_NOOP, handled);
        assertEquals(1, facade.correlationCalls.get());
        assertEquals(0, facade.ingestCalls.get());
    }


    @Test
    void missingActiveLeaseDelegatesToEngineForDuplicateLateOrNoLeaseClassification() {
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

        assertEquals(ResultIngressHandleOutcome.HANDLED_APPLIED, handled);
        assertEquals(1, facade.correlationCalls.get());
        assertEquals(1, facade.ingestCalls.get());
    }

    @Test
    void invalidPayloadIsPermanentReject() {
        RuntimeTaskResultIngestChannel channel = new RuntimeTaskResultIngestChannel(new RecordingResultIngestFacade(null));

        ResultIngressHandleOutcome handled = channel.handleResult(
                new ResultIngressEntry(
                        "msg-1",
                        new ResultIngressMessage(
                                "result-msg-invalid",
                                "msg-1",
                                "{",
                                0L,
                                System.currentTimeMillis()
                ),
                        ResultIngressDiagnostics.empty()
                )
        );

        assertEquals(ResultIngressHandleOutcome.PERMANENT_REJECT, handled);
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
    }

    private static ResultIngressEntry envelope(WorkerActionReply request) {
        return CODEC.toEntry(request, Map.of("adapterId", "polling"));
    }

    private static WorkerActionReply request(String taskId,
                                             String messageId,
                                             boolean success,
                                             String result,
                                             String resultCode,
                                             String attemptId,
                                             String leaseToken,
                                             String traceId) {
        return new WorkerActionReply(
                new TaskDispatchDeliveryCorrelationCodec().encode(
                        new TaskDispatchDeliveryCorrelation(taskId, messageId, attemptId, 0)
                ),
                success,
                resultCode,
                result
        );
    }

    private static ResultIngressEntry rawEnvelope(String taskId,
                                                  String messageId,
                                                  boolean success,
                                                  String result,
                                                  String attemptId,
                                                  String leaseToken) {
        String replyRef = new TaskDispatchDeliveryCorrelationCodec().encode(
                new TaskDispatchDeliveryCorrelation(taskId, messageId, attemptId, 0)
        );
        String payload = """
                {
                  "replyRef": "%s",
                  "success": %s,
                  "body": "%s",
                  "leaseToken": "%s"
                }
                """.formatted(replyRef, success, result, leaseToken);
        return new ResultIngressEntry(
                replyRef,
                new ResultIngressMessage("result-" + messageId, replyRef, payload, 0L, System.currentTimeMillis()),
                new ResultIngressDiagnostics(Map.of("adapterId", "polling"))
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
