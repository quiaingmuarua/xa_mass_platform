package com.xa.mass.starter;

import com.xa.mass.base.runtime.result.TaskResultCorrelation;
import com.xa.mass.base.runtime.result.TaskResultIngestFacade;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.model.TransportResultEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeTaskResultIngestChannelTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void nullIngressIsNotHandled() {
        RuntimeTaskResultIngestChannel channel = new RuntimeTaskResultIngestChannel(new RecordingResultIngestFacade(null));

        assertFalse(channel.ingest((TaskResultReport) null));
        assertFalse(channel.ingest((TransportResultEnvelope) null));
    }

    @Test
    void taskResultReportDelegatesToFacade() {
        RecordingResultIngestFacade facade = new RecordingResultIngestFacade(null);
        RuntimeTaskResultIngestChannel channel = new RuntimeTaskResultIngestChannel(facade);

        boolean handled = channel.ingest(report("task-1", "msg-1", true, "ok", null));

        assertTrue(handled);
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

        boolean handled = channel.ingest(envelope(
                null,
                null,
                "transport-trace",
                report("task-2", "msg-2", true, "ok", null)
        ));

        assertTrue(handled);
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

        boolean handled = channel.ingest(envelope(
                null,
                "lease-3",
                null,
                report("task-3", "msg-3", true, "ok", null)
        ));

        assertTrue(handled);
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

        boolean handled = channel.ingest(envelope(
                "attempt-4",
                "lease-4",
                null,
                report("task-4", "msg-4", true, "ok", null)
        ));

        assertTrue(handled);
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

        boolean handled = channel.ingest(envelope(
                "wrong-attempt",
                "lease-5",
                null,
                report("task-5", "msg-5", true, "ok", null)
        ));

        assertTrue(handled);
        assertEquals(1, facade.correlationCalls.get());
        assertEquals(0, facade.ingestCalls.get());
    }

    @Test
    void missingActiveLeaseIsAcceptedNoopWithoutEngineApply() {
        RecordingResultIngestFacade facade = new RecordingResultIngestFacade(
                TaskResultCorrelation.noActiveLease("task-6", "msg-6")
        );
        RuntimeTaskResultIngestChannel channel = new RuntimeTaskResultIngestChannel(facade);

        boolean handled = channel.ingest(envelope(
                "attempt-6",
                "lease-6",
                null,
                report("task-6", "msg-6", true, "ok", null)
        ));

        assertTrue(handled);
        assertEquals(1, facade.correlationCalls.get());
        assertEquals(0, facade.ingestCalls.get());
    }

    private static TransportResultEnvelope envelope(String attemptId,
                                                    String leaseToken,
                                                    String traceId,
                                                    TaskResultReport report) {
        return new TransportResultEnvelope(
                "polling",
                "worker-1",
                attemptId,
                leaseToken,
                traceId,
                report
        );
    }

    private static TaskResultReport report(String taskId,
                                           String messageId,
                                           boolean success,
                                           String detail,
                                           String errorCode) {
        return new TaskResultReport(
                taskId,
                messageId,
                success,
                detail,
                errorCode,
                Map.of("status", success ? "SUCCESS" : "FAILED", "mockData", detail)
        );
    }

    private static final class RecordingResultIngestFacade implements TaskResultIngestFacade {
        private final TaskResultCorrelation correlation;
        private final AtomicInteger ingestCalls = new AtomicInteger();
        private final AtomicInteger correlationCalls = new AtomicInteger();
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
            return true;
        }

        @Override
        public TaskResultCorrelation getResultCorrelation(String taskId, String messageId) {
            correlationCalls.incrementAndGet();
            return correlation;
        }
    }
}
