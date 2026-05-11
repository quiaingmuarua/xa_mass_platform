package com.xa.mass.transport.runtime;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.TaskAssignmentRuntimePort;
import com.xa.mass.engine.TaskManagerResultIngestFacade;
import com.xa.mass.engine.TaskQueryService;
import com.xa.mass.engine.TaskWorkAttemptIdSupport;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionStatus;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.model.TransportResultEnvelope;
import com.xa.mass.transport.packet.TransportPacket;
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
        TaskDetailStore.TaskMessageProjection updated = compatibilityMessageSnapshotView(fixture);
        assertEquals(TaskMessageProjectionStatus.SUCCESS, updated.status());
        assertEquals("SUCCESS", updated.output().get("status"));
        assertEquals("ok", updated.output().get("mockData"));
        TaskDetailStore.TaskMessageAttemptProjection attempt = latestAttemptAuditView(fixture);
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
        TaskDetailStore.TaskMessageProjection updated = compatibilityMessageSnapshotView(fixture);
        assertEquals(TaskMessageProjectionStatus.INIT, updated.status());
        assertEquals(1, updated.retryCount());
        assertNull(updated.errorMessage());
        assertNull(updated.errorCode());
        TaskDetailStore.TaskMessageAttemptProjection attempt = latestAttemptAuditView(fixture);
        assertNotNull(attempt);
        assertEquals(TaskMessageAttemptProjectionStatus.REVOKED, attempt.status());
        assertEquals(TaskMessageAttemptProjectionFinalReason.REVOKED_FOR_RETRY, attempt.finalReason());
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
        TaskDetailStore.TaskMessageProjection updated = compatibilityMessageSnapshotView(fixture);
        assertEquals(TaskMessageProjectionStatus.SUCCESS, updated.status());
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
        TaskDetailStore.TaskMessageProjection updated = compatibilityMessageSnapshotView(fixture);
        assertEquals(TaskMessageProjectionStatus.SUCCESS, updated.status());
        assertEquals("SUCCESS", updated.output().get("status"));
        assertEquals("ok-from-report", updated.output().get("mockData"));
    }

    @Test
    void resultEnvelopeDelegatesToTaskResultLifecycleWithoutChangingSemantics() {
        RunningTaskFixture fixture = createRunningTask("task-envelope");

        boolean handled = channel.ingest(new TransportResultEnvelope(
                "polling",
                "worker-1",
                null,
                null,
                null,
                report(fixture, "SUCCESS", "ok-envelope", null)
        ));

        assertTrue(handled);
        TaskDetailStore.TaskMessageProjection updated = compatibilityMessageSnapshotView(fixture);
        assertEquals(TaskMessageProjectionStatus.SUCCESS, updated.status());
        assertEquals("ok-envelope", updated.output().get("mockData"));
        assertEquals(TaskStatus.TERMINAL, taskQueries.getTask(fixture.taskId()).getStatus());
    }

    @Test
    void mismatchedEnvelopeAttemptIdentityIsAcceptedAsNoop() {
        RunningTaskFixture fixture = createRunningTask("task-envelope-attempt-mismatch");

        boolean handled = channel.ingest(new TransportResultEnvelope(
                "polling",
                "worker-1",
                "wrong-attempt",
                null,
                null,
                report(fixture, "SUCCESS", "ok-mismatch", null)
        ));

        assertTrue(handled);
        TaskDetailStore.TaskMessageProjection updated = compatibilityMessageSnapshotView(fixture);
        assertEquals(TaskMessageProjectionStatus.ASSIGNED, updated.status());
        assertNull(updated.output());
    }

    @Test
    void mismatchedEnvelopeLeaseIdentityIsAcceptedAsNoop() {
        RunningTaskFixture fixture = createRunningTask("task-envelope-lease-mismatch");

        boolean handled = channel.ingest(new TransportResultEnvelope(
                "polling",
                "worker-1",
                fixture.attemptId(),
                "wrong-lease-token",
                null,
                report(fixture, "SUCCESS", "ok-lease-mismatch", null)
        ));

        assertTrue(handled);
        TaskDetailStore.TaskMessageProjection updated = compatibilityMessageSnapshotView(fixture);
        assertEquals(TaskMessageProjectionStatus.ASSIGNED, updated.status());
        assertNull(updated.output());
    }

    @Test
    void envelopeWithoutActiveLeaseIsAcceptedAsNoop() {
        RunningTaskFixture fixture = createRunningTask("task-envelope-no-active-lease");
        taskWorkRuntime.discardTask(fixture.taskId());

        boolean handled = channel.ingest(new TransportResultEnvelope(
                "polling",
                "worker-1",
                fixture.attemptId(),
                null,
                null,
                report(fixture, "SUCCESS", "ok-no-active-lease", null)
        ));

        assertTrue(handled);
        TaskDetailStore.TaskMessageProjection updated = compatibilityMessageSnapshotView(fixture);
        assertEquals(TaskMessageProjectionStatus.ASSIGNED, updated.status());
        assertNull(updated.output());
    }

    @Test
    void envelopeAttemptValidationDoesNotRequirePersistedAttemptResidueRow() {
        RunningTaskFixture fixture = createRunningTask("task-envelope-no-attempt-row", false);

        boolean handled = channel.ingest(new TransportResultEnvelope(
                "polling",
                "worker-1",
                fixture.attemptId(),
                null,
                null,
                report(fixture, "SUCCESS", "ok-no-attempt-row", null)
        ));

        assertTrue(handled);
        TaskDetailStore.TaskMessageProjection updated = compatibilityMessageSnapshotView(fixture);
        assertEquals(TaskMessageProjectionStatus.SUCCESS, updated.status());
        assertEquals("ok-no-attempt-row", updated.output().get("mockData"));
        TaskDetailStore.TaskMessageAttemptProjection recoveredAttempt = latestAttemptAuditView(fixture);
        assertNotNull(recoveredAttempt);
        assertEquals(TaskMessageAttemptProjectionStatus.SUCCEEDED, recoveredAttempt.status());
    }

    @Test
    void missingAttemptResidueKeepsCompatibilityReinsertBoundedToSingleLatestAttemptProjection() {
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
        assertEquals(1, countingStorage.attemptUpsertCount,
                "result convergence should keep latest-attempt compatibility residue bounded to one upsert when no attempt row exists");
        TaskDetailStore.TaskMessageAttemptProjection recoveredAttempt = latestAttemptAuditView(fixture);
        assertNotNull(recoveredAttempt);
        assertEquals(TaskMessageAttemptProjectionStatus.SUCCEEDED, recoveredAttempt.status());
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
        TaskDetailStore.TaskMessageProjection updated = storedCompatibilityMessage(fixture);
        assertEquals(TaskMessageProjectionStatus.SUCCESS, updated.status());
        assertEquals("ok-hidden-read", updated.output().get("mockData"));
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

        TaskDetailStore.TaskMessageProjection stored = storedCompatibilityMessage(fixture);
        assertEquals(TaskMessageProjectionStatus.SUCCESS, stored.status());
        assertEquals("ok-terminal-first", stored.output().get("mockData"));
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
        TaskDetailStore.TaskMessageProjection updated = compatibilityMessageSnapshotView(fixture);
        assertEquals(TaskMessageProjectionStatus.SUCCESS, updated.status());
        assertEquals("ok-update-fails", updated.output().get("mockData"));
        assertEquals(TaskStatus.TERMINAL, taskQueries.getTask(fixture.taskId()).getStatus());
    }

    @Test
    void envelopeTraceIdFlowsIntoEngineCanonicalTraceEvents() {
        RunningTaskFixture fixture = createRunningTask("task-envelope-trace");

        boolean handled = channel.ingest(new TransportResultEnvelope(
                "polling",
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
        TaskShellCreateRequestDto shell = new TaskShellCreateRequestDto();
        shell.setSourceRef(taskName);
        shell.setProject("demoApp");
        shell.setSharedConfig(java.util.Map.of(
                "textContent", "hello",
                "routingCode", "us",
                "_sdk", java.util.Map.of("eventCode", "crawler.fetch-page")
        ));
        shell.setUserId("agent");
        TaskExecutionSpec spec = new TaskExecutionSpec();
        spec.setBatchSize(1);
        spec.setDefaultMaxRetryCount(3);
        shell.setExecutionSpec(spec);
        List<Map<String, Object>> inputs = List.of(Map.of("target", "alpha"));
        Task task = taskCommands.createTaskShell(shell);
        taskCommands.appendTaskItems(task.getTid(), inputs);
        assertTrue(taskCommands.sealTask(task.getTid()));
        taskCommands.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskDetailStore.TaskMessageProjection taskMsg = firstMessage(task.getTid());
        String messageId = taskMsg.messageId();
        taskWorkRuntime.claimReady(
                task.getTid(),
                List.of(new WorkerClaimTarget("worker-1", "worker-context-1", "batch-0", 1)),
                1,
                assignmentRuntimePort.getWorkLeaseSeconds()
        );
        String attemptId = TaskWorkAttemptIdSupport.runtimeAttemptId(
                taskMsg.messageId(),
                1,
                "worker-1",
                "worker-context-1",
                "batch-0"
        );
        TaskDetailStore.TaskMessageProjection assignedProjection = new TaskDetailStore.TaskMessageProjection(
                taskMsg.messageId(),
                taskMsg.taskId(),
                taskMsg.input(),
                taskMsg.payloadRef(),
                TaskMessageProjectionStatus.ASSIGNED,
                LocalDateTime.now(),
                taskMsg.createTime(),
                LocalDateTime.now(),
                taskMsg.startTime(),
                taskMsg.completeTime(),
                taskMsg.retryCount(),
                taskMsg.maxRetryCount(),
                taskMsg.errorMessage(),
                taskMsg.errorCode(),
                taskMsg.finalReason(),
                taskMsg.output(),
                attemptId,
                "worker-1",
                "worker-context-1",
                "batch-0"
        );
        taskStorage.upsertTaskMessageProjection(task.getTid(), assignedProjection);

        if (!persistAttemptResidue) {
            return new RunningTaskFixture(task.getTid(), messageId, attemptId);
        }

        TaskDetailStore.TaskMessageAttemptProjection attempt = new TaskDetailStore.TaskMessageAttemptProjection(
                attemptId,
                task.getTid(),
                taskMsg.messageId(),
                1,
                "worker-1",
                "worker-context-1",
                "batch-0",
                TaskMessageAttemptProjectionStatus.DISPATCHED,
                null,
                null,
                null,
                null
        );
        taskStorage.upsertTaskMessageAttemptProjection(task.getTid(), taskMsg.messageId(), attempt);
        return new RunningTaskFixture(task.getTid(), messageId, attemptId);
    }

    private TaskDetailStore.TaskMessageProjection compatibilityMessageSnapshotView(RunningTaskFixture fixture) {
        return compatibilityMessageSnapshotViewById(fixture.taskId(), fixture.messageId());
    }

    private TaskDetailStore.TaskMessageAttemptProjection latestAttemptAuditView(RunningTaskFixture fixture) {
        return taskStorage.getLatestTaskMessageAttemptProjection(fixture.taskId(), fixture.messageId()).orElse(null);
    }

    private void updateMaxRetryCount(RunningTaskFixture fixture, int maxRetryCount) {
        TaskDetailStore.TaskMessageProjection taskMsg = storedCompatibilityMessage(fixture);
        taskStorage.upsertTaskMessageProjection(
                fixture.taskId(),
                new TaskDetailStore.TaskMessageProjection(
                        taskMsg.messageId(),
                        taskMsg.taskId(),
                        taskMsg.input(),
                        taskMsg.payloadRef(),
                        taskMsg.status(),
                        taskMsg.assignedTime(),
                        taskMsg.createTime(),
                        taskMsg.updateTime(),
                        taskMsg.startTime(),
                        taskMsg.completeTime(),
                        taskMsg.retryCount(),
                        maxRetryCount,
                        taskMsg.errorMessage(),
                        taskMsg.errorCode(),
                        taskMsg.finalReason(),
                        taskMsg.output(),
                        taskMsg.latestAttemptId(),
                        taskMsg.latestAttemptWorkerId(),
                        taskMsg.latestAttemptWorkerContextId(),
                        taskMsg.latestAttemptBatchId()
                )
        );
    }

    private TaskDetailStore.TaskMessageProjection storedCompatibilityMessage(RunningTaskFixture fixture) {
        int limit = (int) Math.max(1L, taskStorage.getTaskMessageStats(fixture.taskId()).getTotal());
        return taskStorage.getTaskMessageProjections(fixture.taskId(), limit).stream()
                .filter(projection -> fixture.messageId().equals(projection.messageId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Task message projection not found: taskId="
                                + fixture.taskId() + ", messageId=" + fixture.messageId()));
    }

    private TaskDetailStore.TaskMessageProjection firstMessage(String taskId) {
        return taskStorage.getTaskMessageProjections(taskId, 1).get(0);
    }

    private TaskDetailStore.TaskMessageProjection compatibilityMessageSnapshotViewById(String taskId, String messageId) {
        return taskStorage.getTaskMessageProjection(taskId, messageId)
                .orElseThrow(() -> new IllegalStateException(
                        "Task message projection not found: taskId=" + taskId + ", messageId=" + messageId));
    }

    private TaskResultReport report(RunningTaskFixture fixture, String status, String detail, String errorCode) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("status", status);
        payload.put("mockData", detail);
        if (errorCode != null) {
            payload.put(TransportPacket.PAYLOAD_ERROR_CODE, errorCode);
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
        public boolean upsertTaskMessageAttemptProjection(String taskId,
                                                          String messageId,
                                                          TaskDetailStore.TaskMessageAttemptProjection projection) {
            if (getLatestTaskMessageAttemptProjection(taskId, messageId).isPresent()) {
                return false;
            }
            return super.upsertTaskMessageAttemptProjection(taskId, messageId, projection);
        }
    }

    private static final class AttemptWriteCountingStorage extends InMemoryTaskStorage {
        private int attemptUpsertCount;

        @Override
        public boolean upsertTaskMessageAttemptProjection(String taskId,
                                                          String messageId,
                                                          TaskDetailStore.TaskMessageAttemptProjection projection) {
            attemptUpsertCount++;
            return super.upsertTaskMessageAttemptProjection(taskId, messageId, projection);
        }

        private void resetAttemptWriteCounts() {
            attemptUpsertCount = 0;
        }
    }

    private static final class TrackingLatestAttemptStorage extends InMemoryTaskStorage {
        private int latestAttemptReadCount;

        @Override
        public java.util.Optional<TaskDetailStore.TaskMessageAttemptProjection> getLatestTaskMessageAttemptProjection(String taskId,
                                                                                                                      String messageId) {
            latestAttemptReadCount++;
            return super.getLatestTaskMessageAttemptProjection(taskId, messageId);
        }

        private void resetLatestAttemptReadCount() {
            latestAttemptReadCount = 0;
        }
    }

    private static final class ActiveAttemptTrackingStorage extends InMemoryTaskStorage {
        private int latestActiveAttemptReadCount;

        @Override
        public java.util.Optional<TaskDetailStore.TaskMessageAttemptProjection> getLatestActiveTaskMessageAttemptProjection(String taskId,
                                                                                                                            String messageId) {
            latestActiveAttemptReadCount++;
            return super.getLatestActiveTaskMessageAttemptProjection(taskId, messageId);
        }

        private void resetLatestActiveAttemptReadCount() {
            latestActiveAttemptReadCount = 0;
        }
    }

    private static final class HiddenCompatibilityMessageReadStorage extends InMemoryTaskStorage {
        private int compatibilityAddCount;

        @Override
        public java.util.Optional<TaskDetailStore.TaskMessageProjection> getTaskMessageProjection(String taskId,
                                                                                                  String messageId) {
            return java.util.Optional.empty();
        }

        @Override
        public boolean upsertTaskMessageProjection(String taskId, TaskDetailStore.TaskMessageProjection taskMsg) {
            compatibilityAddCount++;
            return super.upsertTaskMessageProjection(taskId, taskMsg);
        }

        private void resetCompatibilityAddCount() {
            compatibilityAddCount = 0;
        }
    }
}



