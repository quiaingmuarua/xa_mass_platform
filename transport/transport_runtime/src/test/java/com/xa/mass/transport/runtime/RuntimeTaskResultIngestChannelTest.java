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
        TaskMsg updated = taskQueries.getTaskMessageProjection(fixture.taskId(), fixture.messageId());
        assertEquals(TaskMsgStatus.SUCCESS, updated.getStatus());
        assertEquals("SUCCESS", updated.getOutput().get("status"));
        assertEquals("ok", updated.getOutput().get("mockData"));
        TaskMsgAttempt attempt = taskQueries.getLatestTaskMessageAttemptAuditView(fixture.taskId(), fixture.messageId());
        assertNotNull(attempt);
        assertEquals("SUCCESS", attempt.getOutput().get("status"));
        assertEquals(TaskStatus.TERMINAL, taskQueries.getTask(fixture.taskId()).getStatus());
    }

    @Test
    void failureResponseFollowsRuntimeRetryBudgetInsteadOfStaleTaskMessageProjection() {
        RunningTaskFixture fixture = createRunningTask("task-failure");
        TaskMsg taskMsg = taskQueries.getTaskMessageProjection(fixture.taskId(), fixture.messageId());
        taskMsg.setMaxRetryCount(0);
        taskStorage.updateTaskMessage(fixture.taskId(), taskMsg);

        boolean handled = channel.ingest(report(fixture, "FAILED", "boom", "RATE_LIMITED"));

        assertTrue(handled);
        TaskMsg updated = taskQueries.getTaskMessageProjection(fixture.taskId(), fixture.messageId());
        assertEquals(TaskMsgStatus.INIT, updated.getStatus());
        assertEquals(1, updated.getRetryCount());
        assertNull(updated.getErrorMessage());
        assertNull(updated.getErrorCode());
        TaskMsgAttempt attempt = taskQueries.getLatestTaskMessageAttemptAuditView(fixture.taskId(), fixture.messageId());
        assertNotNull(attempt);
        assertEquals(TaskMsgAttemptStatus.REVOKED, attempt.getStatus());
        assertEquals(TaskMsgAttemptFinalReason.REVOKED_FOR_RETRY, attempt.getFinalReason());
        assertEquals("boom", attempt.getErrorMessage());
        assertEquals("RATE_LIMITED", attempt.getErrorCode());
        assertNull(attempt.getOutput());
        assertEquals(TaskStatus.RUNNING, taskQueries.getTask(fixture.taskId()).getStatus());
    }

    @Test
    void duplicateResponseKeepsFirstFinalResultAndStillReturnsHandled() {
        RunningTaskFixture fixture = createRunningTask("task-duplicate");

        boolean firstHandled = channel.ingest(report(fixture, "SUCCESS", "ok", null));
        boolean secondHandled = channel.ingest(report(fixture, "FAILED", "boom", null));

        assertTrue(firstHandled);
        assertTrue(secondHandled);
        TaskMsg updated = taskQueries.getTaskMessageProjection(fixture.taskId(), fixture.messageId());
        assertEquals(TaskMsgStatus.SUCCESS, updated.getStatus());
        assertNull(updated.getErrorMessage());
    }

    @Test
    void duplicateResponseDoesNotReadAttemptProjectionOnlyForTrace() {
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
                "duplicate result trace should use bounded message projection instead of re-reading attempt rows");
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
        TaskMsg updated = taskQueries.getTaskMessageProjection(fixture.taskId(), fixture.messageId());
        assertEquals(TaskMsgStatus.SUCCESS, updated.getStatus());
        assertEquals("SUCCESS", updated.getOutput().get("status"));
        assertEquals("ok-from-report", updated.getOutput().get("mockData"));
    }

    @Test
    void resultEnvelopeDelegatesToTaskResultLifecycleWithoutChangingSemantics() {
        RunningTaskFixture fixture = createRunningTask("task-envelope");

        boolean handled = channel.ingest(TransportResultEnvelope.fromReport(
                "polling",
                "worker-1",
                "worker-1",
                report(fixture, "SUCCESS", "ok-envelope", null)
        ));

        assertTrue(handled);
        TaskMsg updated = taskQueries.getTaskMessageProjection(fixture.taskId(), fixture.messageId());
        assertEquals(TaskMsgStatus.SUCCESS, updated.getStatus());
        assertEquals("ok-envelope", updated.getOutput().get("mockData"));
        assertEquals(TaskStatus.TERMINAL, taskQueries.getTask(fixture.taskId()).getStatus());
    }

    @Test
    void mismatchedEnvelopeAttemptIdentityStillDelegatesDuringLogOnlyStage() {
        RunningTaskFixture fixture = createRunningTask("task-envelope-attempt-mismatch");

        boolean handled = channel.ingest(new TransportResultEnvelope(
                "polling",
                "worker-1",
                "worker-1",
                "wrong-attempt",
                null,
                report(fixture, "SUCCESS", "ok-mismatch", null)
        ));

        assertTrue(handled);
        TaskMsg updated = taskQueries.getTaskMessageProjection(fixture.taskId(), fixture.messageId());
        assertEquals(TaskMsgStatus.SUCCESS, updated.getStatus());
        assertEquals("ok-mismatch", updated.getOutput().get("mockData"));
    }

    @Test
    void envelopeAttemptValidationDoesNotRequirePersistedAttemptProjectionRow() {
        RunningTaskFixture fixture = createRunningTask("task-envelope-no-attempt-row", false);

        boolean handled = channel.ingest(new TransportResultEnvelope(
                "polling",
                "worker-1",
                "worker-1",
                fixture.attemptId(),
                null,
                report(fixture, "SUCCESS", "ok-no-attempt-row", null)
        ));

        assertTrue(handled);
        TaskMsg updated = taskQueries.getTaskMessageProjection(fixture.taskId(), fixture.messageId());
        assertEquals(TaskMsgStatus.SUCCESS, updated.getStatus());
        assertEquals("ok-no-attempt-row", updated.getOutput().get("mockData"));
        TaskMsgAttempt recoveredAttempt = taskQueries.getLatestTaskMessageAttemptAuditView(fixture.taskId(), fixture.messageId());
        assertNotNull(recoveredAttempt);
        assertEquals(TaskMsgAttemptStatus.SUCCEEDED, recoveredAttempt.getStatus());
    }

    @Test
    void resultCorrelationDoesNotReadActiveAttemptProjection() {
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

        new TaskManagerResultIngestFacade(taskManager).getResultCorrelation(fixture.taskId(), fixture.messageId());

        assertEquals(0, trackingStorage.latestActiveAttemptReadCount,
                "result correlation should validate against runtime lease without reading active attempt projection");
    }

    @Test
    void missingTaskMessageReadProjectionKeepsCompatibilityReinsertBounded() {
        scheduler = new RecordingTaskScheduler();
        ProjectionHiddenReadStorage projectionHiddenStorage = new ProjectionHiddenReadStorage();
        taskStorage = projectionHiddenStorage;
        taskWorkRuntime = new InMemoryTaskWorkRuntime();
        traceSink = new RecordingExecutionEventSink();
        taskManager = new TaskManager(scheduler, taskStorage, taskStorage, taskWorkRuntime, traceSink);
        taskCommands = new TaskCommandService(taskManager);
        taskQueries = new TaskQueryService(taskManager);
        assignmentRuntimePort = taskManager;
        channel = new RuntimeTaskResultIngestChannel(new TaskManagerResultIngestFacade(taskManager));

        RunningTaskFixture fixture = createRunningTask("task-hidden-message-read");
        projectionHiddenStorage.resetCompatibilityAddCount();

        boolean handled = channel.ingest(report(fixture, "SUCCESS", "ok-hidden-read", null));

        assertTrue(handled);
        assertTrue(projectionHiddenStorage.compatibilityAddCount <= 1,
                "result convergence should keep TaskMsg compatibility reinsert bounded when read projection is hidden");
        TaskMsg updated = projectionHiddenStorage.getTaskMessages(fixture.taskId()).get(0);
        assertEquals(TaskMsgStatus.SUCCESS, updated.getStatus());
        assertEquals("ok-hidden-read", updated.getOutput().get("mockData"));
    }

    @Test
    void resultConvergesWhenAttemptProjectionUpdateFailsAfterRuntimeAcceptance() {
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
        TaskMsg updated = taskQueries.getTaskMessageProjection(fixture.taskId(), fixture.messageId());
        assertEquals(TaskMsgStatus.SUCCESS, updated.getStatus());
        assertEquals("ok-update-fails", updated.getOutput().get("mockData"));
        assertEquals(TaskStatus.TERMINAL, taskQueries.getTask(fixture.taskId()).getStatus());
    }

    @Test
    void envelopeTraceIdFlowsIntoEngineCanonicalTraceEvents() {
        RunningTaskFixture fixture = createRunningTask("task-envelope-trace");

        boolean handled = channel.ingest(TransportResultEnvelope.fromReport(
                "polling",
                "worker-1",
                "worker-1",
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
            boolean handled = channel.ingest(TransportResultEnvelope.fromReport(
                    "polling",
                    "worker-1",
                    "worker-1",
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

    private TaskMsg firstMessage(String taskId) {
        return taskQueries.getTaskMessageSnapshot(taskId, 1).messages().get(0);
    }

    private RunningTaskFixture createRunningTask(String taskName, boolean persistAttemptProjection) {
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
        String attemptId = "attempt-" + taskMsg.getMessageId() + "-1";
        taskMsg.applyLatestAttemptProjection(attemptId, "worker-1", "worker-context-1", "batch-0");
        taskMsg.markAsAssigned();
        taskStorage.updateTaskMessage(task.getTid(), taskMsg);

        if (!persistAttemptProjection) {
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
            return SchedulingResult.success(List.of());
        }

        @Override
        public List<SchedulingResult> scheduleTasks(List<Task> tasks) {
            return List.of();
        }

        @Override
        public boolean retryTaskMsg(TaskMsg taskMsg) {
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

    private static final class ProjectionHiddenReadStorage extends InMemoryTaskStorage {
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



