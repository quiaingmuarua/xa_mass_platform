package com.xa.mass.transport.runtime;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.TaskAssignmentRuntimePort;
import com.xa.mass.engine.TaskManagerResultIngestFacade;
import com.xa.mass.engine.TaskMessageAttemptSupport;
import com.xa.mass.engine.TaskMessageAttemptView;
import com.xa.mass.engine.TaskMessageView;
import com.xa.mass.engine.TaskQueryService;
import com.xa.mass.base.model.TaskCreateRequestDto;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.model.TransportResultEnvelope;
import com.xa.mass.trace.sink.ExecutionEvent;
import com.xa.mass.trace.sink.ExecutionEventSink;
import com.xa.mass.trace.sink.ExecutionEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeTaskResultIngestChannelTest {

    private RecordingTaskScheduler scheduler;
    private TaskManager taskManager;
    private TaskCommandService taskCommands;
    private TaskQueryService taskQueries;
    private TaskAssignmentRuntimePort assignmentRuntimePort;
    private InMemoryTaskStorage taskStorage;
    private InMemoryTaskWorkRuntime taskWorkRuntime;
    private RuntimeTaskResultIngestChannel channel;
    private RecordingExecutionEventSink traceSink;

    @BeforeEach
    void setUp() {
        scheduler = new RecordingTaskScheduler();
        taskStorage = new InMemoryTaskStorage();
        taskWorkRuntime = new InMemoryTaskWorkRuntime();
        traceSink = new RecordingExecutionEventSink();
        taskManager = new TaskManager(scheduler, taskStorage, taskStorage, taskWorkRuntime, traceSink);
        taskCommands = new TaskCommandService(taskManager);
        taskQueries = new TaskQueryService(taskManager);
        assignmentRuntimePort = taskManager;
        channel = new RuntimeTaskResultIngestChannel(new TaskManagerResultIngestFacade(taskManager));
    }

    @Test
    void successResponseUpdatesStoredTaskMessage() {
        RunningTaskFixture fixture = createRunningTask("task-success");

        boolean handled = channel.ingest(report(fixture, "SUCCESS", "ok", null));

        assertTrue(handled);
        TaskMessageView updated = compatibilityMessageSnapshotView(fixture);
        assertEquals("SUCCESS", updated.status());
        assertEquals("SUCCESS", updated.output().get("status"));
        assertEquals("ok", updated.output().get("mockData"));
        TaskMessageAttemptView attempt = latestAttemptAuditView(fixture);
        assertNotNull(attempt);
        assertEquals("SUCCESS", attempt.output().get("status"));
        assertEquals(TaskStatus.TERMINAL, taskQueries.getTask(fixture.taskId()).getStatus());
    }

    @Test
    void failureResponseFollowsRuntimeRetryBudgetInsteadOfStaleCompatibilitySnapshotView() {
        RunningTaskFixture fixture = createRunningTask("task-failure");
        updateMaxRetryCount(fixture, 0);

        boolean handled = channel.ingest(report(fixture, "FAILED", "boom", "RATE_LIMITED"));

        assertTrue(handled);
        TaskMessageView updated = compatibilityMessageSnapshotView(fixture);
        assertEquals("INIT", updated.status());
        assertEquals(1, updated.retryCount());
        assertNull(updated.errorMessage());
        assertNull(updated.errorCode());
        TaskMessageAttemptView attempt = latestAttemptAuditView(fixture);
        assertNotNull(attempt);
        assertEquals("REVOKED", attempt.status());
        assertEquals(TaskMsgAttemptFinalReason.REVOKED_FOR_RETRY.name(), attempt.finalReason());
        assertEquals("boom", attempt.errorMessage());
        assertEquals("RATE_LIMITED", attempt.errorCode());
        assertNull(attempt.output());
        assertEquals(TaskStatus.RUNNING, taskQueries.getTask(fixture.taskId()).getStatus());
    }

    @Test
    void duplicateResponseKeepsFirstFinalResultAndStillReturnsHandled() {
        RunningTaskFixture fixture = createRunningTask("task-duplicate");

        boolean firstHandled = channel.ingest(report(fixture, "SUCCESS", "ok", null));
        boolean secondHandled = channel.ingest(report(fixture, "FAILED", "boom", null));

        assertTrue(firstHandled);
        assertTrue(secondHandled);
        TaskMessageView updated = compatibilityMessageSnapshotView(fixture);
        assertEquals("SUCCESS", updated.status());
        assertNull(updated.errorMessage());
    }

    @Test
    void duplicateResponseDoesNotReadAttemptResidueOnlyForTrace() {
        scheduler = new RecordingTaskScheduler();
        TrackingLatestAttemptStorage trackingStorage = new TrackingLatestAttemptStorage();
        taskStorage = trackingStorage;
        taskWorkRuntime = new InMemoryTaskWorkRuntime();
        traceSink = new RecordingExecutionEventSink();
        taskManager = new TaskManager(scheduler, taskStorage, taskStorage, taskWorkRuntime, traceSink);
        taskCommands = new TaskCommandService(taskManager);
        taskQueries = new TaskQueryService(taskManager);
        assignmentRuntimePort = taskManager;
        channel = new RuntimeTaskResultIngestChannel(new TaskManagerResultIngestFacade(taskManager));

        RunningTaskFixture fixture = createRunningTask("task-duplicate-no-attempt-read");

        assertTrue(channel.ingest(report(fixture, "SUCCESS", "ok-first", null)));
        trackingStorage.resetLatestAttemptReadCount();

        assertTrue(channel.ingest(report(fixture, "FAILED", "late-duplicate", null)));
        assertEquals(0, trackingStorage.latestAttemptReadCount,
                "duplicate result trace should use bounded compatibility message detail instead of re-reading attempt rows");
    }

    @Test
    void transportNeutralResultReportCanBeIngestedWithoutWebSocketMessageObject() {
        RunningTaskFixture fixture = createRunningTask("task-transport-neutral");

        boolean handled = channel.ingest(new TaskResultReport(
                fixture.taskId(),
                fixture.messageId(),
                true,
                "ok-from-report",
                null,
                Map.of("status", "SUCCESS", "mockData", "ok-from-report")
        ));

        assertTrue(handled);
        TaskMessageView updated = compatibilityMessageSnapshotView(fixture);
        assertEquals("SUCCESS", updated.status());
        assertEquals("SUCCESS", updated.output().get("status"));
        assertEquals("ok-from-report", updated.output().get("mockData"));
    }

    @Test
    void resultEnvelopeDelegatesToTaskResultLifecycleWithoutChangingSemantics() {
        RunningTaskFixture fixture = createRunningTask("task-envelope");

        boolean handled = channel.ingest(new TransportResultEnvelope(
                "polling",
                "worker-1",
                "worker-1",
                "worker-1",
                report(fixture, "SUCCESS", "ok-envelope", null)
        ));

        assertTrue(handled);
        TaskMessageView updated = compatibilityMessageSnapshotView(fixture);
        assertEquals("SUCCESS", updated.status());
        assertEquals("ok-envelope", updated.output().get("mockData"));
        assertEquals(TaskStatus.TERMINAL, taskQueries.getTask(fixture.taskId()).getStatus());
    }

    @Test
    void mismatchedEnvelopeAttemptIdentityStillDelegatesDuringLogOnlyStage() {
        RunningTaskFixture fixture = createRunningTask("task-envelope-attempt-mismatch");

        boolean handled = channel.ingest(new TransportResultEnvelope(
                "polling",
                "worker-1",
                "worker-1",
                "worker-1",
                "wrong-attempt",
                null,
                report(fixture, "SUCCESS", "ok-mismatch", null)
        ));

        assertTrue(handled);
        TaskMessageView updated = compatibilityMessageSnapshotView(fixture);
        assertEquals("SUCCESS", updated.status());
        assertEquals("ok-mismatch", updated.output().get("mockData"));
    }

    @Test
    void envelopeAttemptValidationDoesNotRequirePersistedAttemptResidueRow() {
        RunningTaskFixture fixture = createRunningTask("task-envelope-no-attempt-row", false);

        boolean handled = channel.ingest(new TransportResultEnvelope(
                "polling",
                "worker-1",
                "worker-1",
                "worker-1",
                fixture.attemptId(),
                null,
                report(fixture, "SUCCESS", "ok-no-attempt-row", null)
        ));

        assertTrue(handled);
        TaskMessageView updated = compatibilityMessageSnapshotView(fixture);
        assertEquals("SUCCESS", updated.status());
        assertEquals("ok-no-attempt-row", updated.output().get("mockData"));
        TaskMessageAttemptView recoveredAttempt = latestAttemptAuditView(fixture);
        assertNotNull(recoveredAttempt);
        assertEquals("SUCCEEDED", recoveredAttempt.status());
    }

    @Test
    void missingAttemptResidueIsRecreatedOnlyAsFinalBoundedAuditView() {
        scheduler = new RecordingTaskScheduler();
        AttemptWriteCountingStorage countingStorage = new AttemptWriteCountingStorage();
        taskStorage = countingStorage;
        taskWorkRuntime = new InMemoryTaskWorkRuntime();
        traceSink = new RecordingExecutionEventSink();
        taskManager = new TaskManager(scheduler, taskStorage, taskStorage, taskWorkRuntime, traceSink);
        taskCommands = new TaskCommandService(taskManager);
        taskQueries = new TaskQueryService(taskManager);
        assignmentRuntimePort = taskManager;
        channel = new RuntimeTaskResultIngestChannel(new TaskManagerResultIngestFacade(taskManager));

        RunningTaskFixture fixture = createRunningTask("task-missing-attempt-bounded-recreate", false);
        countingStorage.resetAttemptWriteCounts();

        boolean handled = channel.ingest(report(fixture, "SUCCESS", "ok-bounded-attempt", null));

        assertTrue(handled);
        assertEquals(1, countingStorage.addAttemptCount,
                "missing attempt residue should be recreated once as the final bounded audit view");
        assertTrue(countingStorage.updateAttemptCount <= 1,
                "result convergence should not keep restamping intermediate attempt states when only runtime lease exists");
        TaskMessageAttemptView recoveredAttempt = latestAttemptAuditView(fixture);
        assertNotNull(recoveredAttempt);
        assertEquals("SUCCEEDED", recoveredAttempt.status());
    }

    @Test
    void resultCorrelationDoesNotReadActiveAttemptResidue() {
        scheduler = new RecordingTaskScheduler();
        ActiveAttemptTrackingStorage trackingStorage = new ActiveAttemptTrackingStorage();
        taskStorage = trackingStorage;
        taskWorkRuntime = new InMemoryTaskWorkRuntime();
        traceSink = new RecordingExecutionEventSink();
        taskManager = new TaskManager(scheduler, taskStorage, taskStorage, taskWorkRuntime, traceSink);
        taskCommands = new TaskCommandService(taskManager);
        taskQueries = new TaskQueryService(taskManager);
        assignmentRuntimePort = taskManager;
        channel = new RuntimeTaskResultIngestChannel(new TaskManagerResultIngestFacade(taskManager));

        RunningTaskFixture fixture = createRunningTask("task-envelope-no-active-attempt-read", false);
        trackingStorage.resetLatestActiveAttemptReadCount();

        var correlation = new TaskManagerResultIngestFacade(taskManager).getResultCorrelation(
                fixture.taskId(),
                fixture.messageId()
        );

        assertEquals(0, trackingStorage.latestActiveAttemptReadCount,
                "result correlation should validate against runtime lease without reading active attempt residue");
        assertEquals(fixture.attemptId(), correlation.projectedAttemptId());
    }

    @Test
    void hiddenTaskMessageReadKeepsCompatibilityReinsertBounded() {
        scheduler = new RecordingTaskScheduler();
        HiddenCompatibilityMessageReadStorage hiddenReadStorage = new HiddenCompatibilityMessageReadStorage();
        taskStorage = hiddenReadStorage;
        taskWorkRuntime = new InMemoryTaskWorkRuntime();
        traceSink = new RecordingExecutionEventSink();
        taskManager = new TaskManager(scheduler, taskStorage, taskStorage, taskWorkRuntime, traceSink);
        taskCommands = new TaskCommandService(taskManager);
        taskQueries = new TaskQueryService(taskManager);
        assignmentRuntimePort = taskManager;
        channel = new RuntimeTaskResultIngestChannel(new TaskManagerResultIngestFacade(taskManager));

        RunningTaskFixture fixture = createRunningTask("task-hidden-message-read");
        hiddenReadStorage.resetCompatibilityAddCount();

        boolean handled = channel.ingest(report(fixture, "SUCCESS", "ok-hidden-read", null));

        assertTrue(handled);
        assertTrue(hiddenReadStorage.compatibilityAddCount <= 1,
                "result convergence should keep TaskMsg compatibility reinsert bounded when compatibility read is hidden");
        TaskMsg updated = storedCompatibilityMessage(fixture);
        assertEquals(TaskMsgStatus.SUCCESS, updated.getStatus());
        assertEquals("ok-hidden-read", updated.getOutput().get("mockData"));
    }

    @Test
    void terminalLateResultDoesNotDependOnReadableCompatibilityMessage() {
        scheduler = new RecordingTaskScheduler();
        HiddenCompatibilityMessageReadStorage hiddenReadStorage = new HiddenCompatibilityMessageReadStorage();
        taskStorage = hiddenReadStorage;
        taskWorkRuntime = new InMemoryTaskWorkRuntime();
        traceSink = new RecordingExecutionEventSink();
        taskManager = new TaskManager(scheduler, taskStorage, taskStorage, taskWorkRuntime, traceSink);
        taskCommands = new TaskCommandService(taskManager);
        taskQueries = new TaskQueryService(taskManager);
        assignmentRuntimePort = taskManager;
        channel = new RuntimeTaskResultIngestChannel(new TaskManagerResultIngestFacade(taskManager));

        RunningTaskFixture fixture = createRunningTask("task-terminal-hidden-message-read");

        assertTrue(channel.ingest(report(fixture, "SUCCESS", "ok-terminal-first", null)));
        assertTrue(channel.ingest(report(fixture, "FAILED", "late-terminal-second", null)));

        TaskMsg stored = storedCompatibilityMessage(fixture);
        assertEquals(TaskMsgStatus.SUCCESS, stored.getStatus());
        assertEquals("ok-terminal-first", stored.getOutput().get("mockData"));
        assertEquals(TaskStatus.TERMINAL, taskQueries.getTask(fixture.taskId()).getStatus());
    }

    @Test
    void resultConvergesWhenAttemptResidueUpdateFailsAfterRuntimeAcceptance() {
        scheduler = new RecordingTaskScheduler();
        taskStorage = new FailingUpdateAttemptStorage();
        taskWorkRuntime = new InMemoryTaskWorkRuntime();
        traceSink = new RecordingExecutionEventSink();
        taskManager = new TaskManager(scheduler, taskStorage, taskStorage, taskWorkRuntime, traceSink);
        taskCommands = new TaskCommandService(taskManager);
        taskQueries = new TaskQueryService(taskManager);
        assignmentRuntimePort = taskManager;
        channel = new RuntimeTaskResultIngestChannel(new TaskManagerResultIngestFacade(taskManager));

        RunningTaskFixture fixture = createRunningTask("task-attempt-update-fails");

        boolean handled = channel.ingest(report(fixture, "SUCCESS", "ok-update-fails", null));

        assertTrue(handled);
        TaskMessageView updated = compatibilityMessageSnapshotView(fixture);
        assertEquals("SUCCESS", updated.status());
        assertEquals("ok-update-fails", updated.output().get("mockData"));
        assertEquals(TaskStatus.TERMINAL, taskQueries.getTask(fixture.taskId()).getStatus());
    }

    @Test
    void envelopeTraceIdFlowsIntoEngineCanonicalTraceEvents() {
        RunningTaskFixture fixture = createRunningTask("task-envelope-trace");

        boolean handled = channel.ingest(new TransportResultEnvelope(
                "polling",
                "worker-1",
                "worker-1",
                "worker-1",
                null,
                null,
                "trace-envelope-1",
                report(fixture, "SUCCESS", "ok-trace", null)
        ));

        assertTrue(handled);
        assertTrue(traceSink.events.stream().anyMatch(event ->
                event.getEventType() == ExecutionEventType.CALLBACK_ACCEPTED
                        && "trace-envelope-1".equals(event.getTraceId())
                        && fixture.taskId().equals(event.getIdentity().taskId())
                        && fixture.messageId().equals(event.getIdentity().messageId())));
    }

    @Test
    void envelopeTraceIdTemporarilyOverridesExistingMdcTraceId() {
        RunningTaskFixture fixture = createRunningTask("task-envelope-mdc-restore");
        MDC.put("traceId", "outer-trace");
        try {
            boolean handled = channel.ingest(new TransportResultEnvelope(
                    "polling",
                    "worker-1",
                    "worker-1",
                    "worker-1",
                    null,
                    null,
                    "trace-envelope-2",
                    report(fixture, "SUCCESS", "ok-restore", null)
            ));

            assertTrue(handled);
            assertTrue(traceSink.events.stream().anyMatch(event ->
                    event.getEventType() == ExecutionEventType.CALLBACK_ACCEPTED
                            && "trace-envelope-2".equals(event.getTraceId())
                            && fixture.taskId().equals(event.getIdentity().taskId())
                            && fixture.messageId().equals(event.getIdentity().messageId())));
        } finally {
            assertEquals("outer-trace", MDC.get("traceId"));
            MDC.remove("traceId");
        }
    }

    private RunningTaskFixture createRunningTask(String taskName) {
        return createRunningTask(taskName, true);
    }

    private RunningTaskFixture createRunningTask(String taskName, boolean persistAttemptResidue) {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName(taskName);
        dto.setProject("demoApp");
        dto.setSharedConfig(java.util.Map.of(
                "textContent", "hello",
                "routingCode", "us",
                "_sdk", java.util.Map.of("eventCode", "crawler.fetch-page")
        ));
        dto.setUserId("agent");
        dto.setBatchSize(1);
        dto.setInputs(List.of(Map.of("target", "alpha")));
        Task task = taskCommands.createTask(dto);
        taskCommands.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg taskMsg = firstMessage(task.getTid());
        String messageId = taskMsg.getMessageId();
        taskWorkRuntime.claimReady(
                task.getTid(),
                List.of(new WorkerClaimTarget("worker-1", "worker-context-1", "batch-0", 1)),
                1,
                assignmentRuntimePort.getTaskMessageLeaseSeconds()
        );
        String attemptId = TaskMessageAttemptSupport.runtimeAttemptId(
                taskMsg.getMessageId(),
                1,
                "worker-1",
                "worker-context-1",
                "batch-0"
        );
        taskMsg.applyLatestAttemptProjection(attemptId, "worker-1", "worker-context-1", "batch-0");
        taskMsg.markAsAssigned();
        taskStorage.updateTaskMessage(task.getTid(), taskMsg);

        if (!persistAttemptResidue) {
            return new RunningTaskFixture(task.getTid(), messageId, attemptId);
        }

        TaskMsgAttempt attempt = new TaskMsgAttempt(attemptId,
                task.getTid(), taskMsg.getMessageId(), 1);
        attempt.setWorkerId("worker-1");
        attempt.setWorkerContextId("worker-context-1");
        attempt.setBatchId("batch-0");
        assertTrue(attempt.markLeased(LocalDateTime.now().plusMinutes(5)));
        assertTrue(attempt.markDispatched());
        taskStorage.addTaskMessageAttempt(task.getTid(), taskMsg.getMessageId(), attempt);
        return new RunningTaskFixture(task.getTid(), messageId, attemptId);
    }

    private TaskMsg compatibilityMessageSnapshotView(RunningTaskFixture fixture) {
        return compatibilityMessageSnapshotViewById(fixture.taskId(), fixture.messageId());
    }

    private TaskMsgAttempt latestAttemptAuditView(RunningTaskFixture fixture) {
        return taskStorage.getLatestTaskMessageAttempt(fixture.taskId(), fixture.messageId()).orElse(null);
    }

    private void updateMaxRetryCount(RunningTaskFixture fixture, int maxRetryCount) {
        TaskMsg taskMsg = compatibilityMessageSnapshotView(fixture);
        taskMsg.setMaxRetryCount(maxRetryCount);
        taskStorage.updateTaskMessage(fixture.taskId(), taskMsg);
    }

    private TaskMsg storedCompatibilityMessage(RunningTaskFixture fixture) {
        return taskStorage.getTaskMessages(fixture.taskId()).get(0);
    }

    private TaskMsg firstMessage(String taskId) {
        return taskStorage.getTaskMessages(taskId).get(0);
    }

    private TaskMsg compatibilityMessageSnapshotViewById(String taskId, String messageId) {
        return taskStorage.getTaskMessages(taskId).stream()
                .filter(message -> messageId.equals(message.getMessageId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Task message not found in bounded compatibility snapshot: taskId="
                                + taskId + ", messageId=" + messageId));
    }

    private TaskResultReport report(RunningTaskFixture fixture, String status, String detail, String errorCode) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("status", status);
        payload.put("mockData", detail);
        if (errorCode != null) {
            payload.put("errorCode", errorCode);
        }
        return new TaskResultReport(
                fixture.taskId(),
                fixture.messageId(),
                "SUCCESS".equalsIgnoreCase(status),
                detail,
                errorCode,
                payload
        );
    }

    private record RunningTaskFixture(String taskId, String messageId, String attemptId) {
    }

    private static class RecordingTaskScheduler implements TaskScheduler {
        @Override
        public SchedulingResult scheduleTask(Task task) {
            return SchedulingResult.success();
        }

        @Override
        public List<SchedulingResult> scheduleTasks(List<Task> tasks) {
            return List.of();
        }

        @Override
        public boolean retryTaskMessage(String taskId, String messageId) {
            return true;
        }

        @Override
        public boolean cancelTask(String taskId) {
            return true;
        }

        @Override
        public boolean pauseTask(String taskId) {
            return true;
        }

        @Override
        public boolean resumeTask(String taskId) {
            return true;
        }
    }

    private static final class RecordingExecutionEventSink implements ExecutionEventSink {
        private final List<ExecutionEvent> events = new ArrayList<>();

        @Override
        public void emit(ExecutionEvent event) {
            events.add(event);
        }
    }

    private static final class FailingUpdateAttemptStorage extends InMemoryTaskStorage {
        @Override
        public boolean updateTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
            return false;
        }
    }

    private static final class AttemptWriteCountingStorage extends InMemoryTaskStorage {
        private int addAttemptCount;
        private int updateAttemptCount;

        @Override
        public void addTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
            addAttemptCount++;
            super.addTaskMessageAttempt(taskId, messageId, attempt);
        }

        @Override
        public boolean updateTaskMessageAttempt(String taskId, String messageId, TaskMsgAttempt attempt) {
            updateAttemptCount++;
            return super.updateTaskMessageAttempt(taskId, messageId, attempt);
        }

        private void resetAttemptWriteCounts() {
            addAttemptCount = 0;
            updateAttemptCount = 0;
        }
    }

    private static final class TrackingLatestAttemptStorage extends InMemoryTaskStorage {
        private int latestAttemptReadCount;

        @Override
        public java.util.Optional<TaskMsgAttempt> getLatestTaskMessageAttempt(String taskId, String messageId) {
            latestAttemptReadCount++;
            return super.getLatestTaskMessageAttempt(taskId, messageId);
        }

        private void resetLatestAttemptReadCount() {
            latestAttemptReadCount = 0;
        }
    }

    private static final class ActiveAttemptTrackingStorage extends InMemoryTaskStorage {
        private int latestActiveAttemptReadCount;

        @Override
        public java.util.Optional<TaskMsgAttempt> getLatestActiveTaskMessageAttempt(String taskId, String messageId) {
            latestActiveAttemptReadCount++;
            return super.getLatestActiveTaskMessageAttempt(taskId, messageId);
        }

        private void resetLatestActiveAttemptReadCount() {
            latestActiveAttemptReadCount = 0;
        }
    }

    private static final class HiddenCompatibilityMessageReadStorage extends InMemoryTaskStorage {
        private int compatibilityAddCount;

        @Override
        public java.util.Optional<TaskMsg> getTaskMessage(String taskId, String messageId) {
            return java.util.Optional.empty();
        }

        @Override
        public void addTaskMessage(String taskId, TaskMsg taskMsg) {
            compatibilityAddCount++;
            super.addTaskMessage(taskId, taskMsg);
        }

        private void resetCompatibilityAddCount() {
            compatibilityAddCount = 0;
        }
    }
}




