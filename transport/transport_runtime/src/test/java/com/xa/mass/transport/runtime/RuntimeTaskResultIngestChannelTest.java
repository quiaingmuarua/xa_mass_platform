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
import com.xa.mass.engine.TaskManagerAssignmentRuntimePort;
import com.xa.mass.engine.TaskQueryService;
import com.xa.mass.engine.TaskManagerResultIngestFacade;
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
        assignmentRuntimePort = new TaskManagerAssignmentRuntimePort(taskManager);
        channel = new RuntimeTaskResultIngestChannel(new TaskManagerResultIngestFacade(taskManager));
    }

    @Test
    void successResponseUpdatesStoredTaskMessage() {
        Task task = createRunningTask("task-success");
        TaskMsg taskMsg = taskQueries.getTaskMessages(task.getTid(), 1).get(0);

        boolean handled = channel.ingest(report(task, taskMsg, "SUCCESS", "ok", null));

        assertTrue(handled);
        TaskMsg updated = taskQueries.getTaskMessage(task.getTid(), taskMsg.getMessageId());
        assertEquals(TaskMsgStatus.SUCCESS, updated.getStatus());
        assertEquals("SUCCESS", updated.getOutput().get("status"));
        assertEquals("ok", updated.getOutput().get("mockData"));
        TaskMsgAttempt attempt = taskQueries.getLatestTaskMessageAttempt(task.getTid(), taskMsg.getMessageId());
        assertNotNull(attempt);
        assertEquals("SUCCESS", attempt.getOutput().get("status"));
        assertEquals(TaskStatus.TERMINAL, taskQueries.getTask(task.getTid()).getStatus());
    }

    @Test
    void failureResponseFollowsRuntimeRetryBudgetInsteadOfStaleTaskMessageProjection() {
        Task task = createRunningTask("task-failure");
        TaskMsg taskMsg = taskQueries.getTaskMessages(task.getTid(), 1).get(0);
        taskMsg.setMaxRetryCount(0);
        taskStorage.updateTaskMessage(task.getTid(), taskMsg);

        boolean handled = channel.ingest(report(task, taskMsg, "FAILED", "boom", "RATE_LIMITED"));

        assertTrue(handled);
        TaskMsg updated = taskQueries.getTaskMessage(task.getTid(), taskMsg.getMessageId());
        assertEquals(TaskMsgStatus.INIT, updated.getStatus());
        assertEquals(1, updated.getRetryCount());
        assertNull(updated.getErrorMessage());
        assertNull(updated.getErrorCode());
        TaskMsgAttempt attempt = taskQueries.getLatestTaskMessageAttempt(task.getTid(), taskMsg.getMessageId());
        assertNotNull(attempt);
        assertEquals(TaskMsgAttemptStatus.REVOKED, attempt.getStatus());
        assertEquals(TaskMsgAttemptFinalReason.REVOKED_FOR_RETRY, attempt.getFinalReason());
        assertEquals("boom", attempt.getErrorMessage());
        assertEquals("RATE_LIMITED", attempt.getErrorCode());
        assertNull(attempt.getOutput());
        assertEquals(TaskStatus.RUNNING, taskQueries.getTask(task.getTid()).getStatus());
    }

    @Test
    void duplicateResponseKeepsFirstFinalResultAndStillReturnsHandled() {
        Task task = createRunningTask("task-duplicate");
        TaskMsg taskMsg = taskQueries.getTaskMessages(task.getTid(), 1).get(0);

        boolean firstHandled = channel.ingest(report(task, taskMsg, "SUCCESS", "ok", null));
        boolean secondHandled = channel.ingest(report(task, taskMsg, "FAILED", "boom", null));

        assertTrue(firstHandled);
        assertTrue(secondHandled);
        TaskMsg updated = taskQueries.getTaskMessage(task.getTid(), taskMsg.getMessageId());
        assertEquals(TaskMsgStatus.SUCCESS, updated.getStatus());
        assertNull(updated.getErrorMessage());
    }

    @Test
    void transportNeutralResultReportCanBeIngestedWithoutWebSocketMessageObject() {
        Task task = createRunningTask("task-transport-neutral");
        TaskMsg taskMsg = taskQueries.getTaskMessages(task.getTid(), 1).get(0);

        boolean handled = channel.ingest(new TaskResultReport(
                task.getTid(),
                taskMsg.getMessageId(),
                true,
                "ok-from-report",
                null,
                Map.of("status", "SUCCESS", "mockData", "ok-from-report")
        ));

        assertTrue(handled);
        TaskMsg updated = taskQueries.getTaskMessage(task.getTid(), taskMsg.getMessageId());
        assertEquals(TaskMsgStatus.SUCCESS, updated.getStatus());
        assertEquals("SUCCESS", updated.getOutput().get("status"));
        assertEquals("ok-from-report", updated.getOutput().get("mockData"));
    }

    @Test
    void resultEnvelopeDelegatesToTaskResultLifecycleWithoutChangingSemantics() {
        Task task = createRunningTask("task-envelope");
        TaskMsg taskMsg = taskQueries.getTaskMessages(task.getTid(), 1).get(0);

        boolean handled = channel.ingest(TransportResultEnvelope.fromReport(
                "polling",
                "worker-1",
                "worker-1",
                report(task, taskMsg, "SUCCESS", "ok-envelope", null)
        ));

        assertTrue(handled);
        TaskMsg updated = taskQueries.getTaskMessage(task.getTid(), taskMsg.getMessageId());
        assertEquals(TaskMsgStatus.SUCCESS, updated.getStatus());
        assertEquals("ok-envelope", updated.getOutput().get("mockData"));
        assertEquals(TaskStatus.TERMINAL, taskQueries.getTask(task.getTid()).getStatus());
    }

    @Test
    void mismatchedEnvelopeAttemptIdentityStillDelegatesDuringLogOnlyStage() {
        Task task = createRunningTask("task-envelope-attempt-mismatch");
        TaskMsg taskMsg = taskQueries.getTaskMessages(task.getTid(), 1).get(0);

        boolean handled = channel.ingest(new TransportResultEnvelope(
                "polling",
                "worker-1",
                "worker-1",
                "wrong-attempt",
                null,
                report(task, taskMsg, "SUCCESS", "ok-mismatch", null)
        ));

        assertTrue(handled);
        TaskMsg updated = taskQueries.getTaskMessage(task.getTid(), taskMsg.getMessageId());
        assertEquals(TaskMsgStatus.SUCCESS, updated.getStatus());
        assertEquals("ok-mismatch", updated.getOutput().get("mockData"));
    }

    @Test
    void envelopeTraceIdFlowsIntoEngineCanonicalTraceEvents() {
        Task task = createRunningTask("task-envelope-trace");
        TaskMsg taskMsg = taskQueries.getTaskMessages(task.getTid(), 1).get(0);

        boolean handled = channel.ingest(TransportResultEnvelope.fromReport(
                "polling",
                "worker-1",
                "worker-1",
                "trace-envelope-1",
                report(task, taskMsg, "SUCCESS", "ok-trace", null)
        ));

        assertTrue(handled);
        assertTrue(traceSink.events.stream().anyMatch(event ->
                event.getEventType() == ExecutionEventType.CALLBACK_ACCEPTED
                        && "trace-envelope-1".equals(event.getTraceId())
                        && task.getTid().equals(event.getIdentity().taskId())
                        && taskMsg.getMessageId().equals(event.getIdentity().messageId())));
    }

    @Test
    void envelopeTraceIdTemporarilyOverridesExistingMdcTraceId() {
        Task task = createRunningTask("task-envelope-mdc-restore");
        TaskMsg taskMsg = taskQueries.getTaskMessages(task.getTid(), 1).get(0);
        MDC.put("traceId", "outer-trace");
        try {
            boolean handled = channel.ingest(TransportResultEnvelope.fromReport(
                    "polling",
                    "worker-1",
                    "worker-1",
                    "trace-envelope-2",
                    report(task, taskMsg, "SUCCESS", "ok-restore", null)
            ));

            assertTrue(handled);
            assertTrue(traceSink.events.stream().anyMatch(event ->
                    event.getEventType() == ExecutionEventType.CALLBACK_ACCEPTED
                            && "trace-envelope-2".equals(event.getTraceId())
                            && task.getTid().equals(event.getIdentity().taskId())
                            && taskMsg.getMessageId().equals(event.getIdentity().messageId())));
        } finally {
            assertEquals("outer-trace", MDC.get("traceId"));
            MDC.remove("traceId");
        }
    }

    private Task createRunningTask(String taskName) {
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

        TaskMsg taskMsg = taskQueries.getTaskMessages(task.getTid(), 1).get(0);
        taskWorkRuntime.claimReady(
                task.getTid(),
                List.of(new WorkerClaimTarget("worker-1", "worker-context-1", "batch-0", 1)),
                1,
                assignmentRuntimePort.getTaskMessageLeaseSeconds()
        );
        taskMsg.applyLatestAttemptProjection("worker-1", "worker-context-1", "batch-0");
        taskMsg.markAsAssigned();
        taskStorage.updateTaskMessage(task.getTid(), taskMsg);

        TaskMsgAttempt attempt = new TaskMsgAttempt("attempt-" + taskMsg.getMessageId() + "-1",
                task.getTid(), taskMsg.getMessageId(), 1);
        attempt.setWorkerId("worker-1");
        attempt.setWorkerContextId("worker-context-1");
        attempt.setBatchId("batch-0");
        assertTrue(attempt.markLeased(LocalDateTime.now().plusMinutes(5)));
        assertTrue(attempt.markDispatched());
        taskStorage.addTaskMessageAttempt(task.getTid(), taskMsg.getMessageId(), attempt);
        return task;
    }

    private TaskResultReport report(Task task, TaskMsg taskMsg, String status, String detail, String errorCode) {
        java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("status", status);
        payload.put("mockData", detail);
        if (errorCode != null) {
            payload.put("errorCode", errorCode);
        }
        return new TaskResultReport(
                task.getTid(),
                taskMsg.getMessageId(),
                "SUCCESS".equalsIgnoreCase(status),
                detail,
                errorCode,
                payload
        );
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
}


