package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskHoldReason;
import com.xa.mass.base.enums.task.TaskIngestStatus;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskSourceType;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgFinalReason;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskCreateRequestDto;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.model.TaskMessageSnapshot;
import com.xa.mass.engine.model.*;
import com.xa.mass.engine.policy.TaskTerminalPolicy;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import com.xa.mass.engine.strategy.TaskScheduler;
import com.xa.mass.engine.util.TraceEventLogCapture;
import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TaskManagerLifecycleTest {

    private RecordingTaskScheduler scheduler;
    private InMemoryTaskStorage taskStorage;
    private ProjectionAwareTaskManager taskManager;

    @BeforeEach
    void setUp() {
        scheduler = new RecordingTaskScheduler();
        taskStorage = new InMemoryTaskStorage();
        taskManager = new ProjectionAwareTaskManager(scheduler, taskStorage, taskStorage, new InMemoryTaskWorkRuntime());
    }

    @Test
    void createTaskStartsAsNewAndPreservesInputs() {
        Task task = taskManager.createTask(buildRequest("task-create"));

        assertEquals(TaskStatus.NEW, task.getStatus());
        assertEquals(TaskSourceType.BATCH, task.getSourceType());
        assertEquals(TaskWorkloadClass.BULK, task.getWorkloadClass());
        assertEquals(TaskIngestStatus.SEALED, task.getIngestStatus());
        assertNotNull(task.getProjectRef());
        assertEquals("demoApp", task.getProjectRef().getCode());
        assertNotNull(task.getUser());
        assertEquals("agent", task.getUser().getUserId());

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        assertEquals(2, messages.size());
        assertEquals("alpha", messages.get(0).getInput().get("target"));
        assertEquals("beta", messages.get(1).getInput().get("target"));
        assertEquals(task.getTid(), messages.get(0).getTaskId());
        assertEquals(task.getTid(), messages.get(1).getTaskId());
        assertNotEquals(messages.get(0).getMessageId(), messages.get(1).getMessageId());
        assertEquals(2, taskManager.getTaskWorkRuntime().stats(task.getTid()).readyCount());
    }

    @Test
    void createTaskPreservesExplicitInteractiveWorkloadIndependentlyFromSourceType() {
        TaskCreateRequestDto dto = buildRequest("task-interactive");
        dto.setSourceType(TaskSourceType.STREAM);
        dto.setOpenEnded(true);
        dto.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);

        Task task = taskManager.createTask(dto);

        assertEquals(TaskSourceType.STREAM, task.getSourceType());
        assertEquals(TaskWorkloadClass.INTERACTIVE, task.getWorkloadClass());
        assertEquals(TaskIntakeStatus.OPEN, task.getIntakeStatus());
    }

    @Test
    void taskCanMoveFromNewToReadyToPausedAndBackToReady() {
        Task task = taskManager.createTask(buildRequest("task-lifecycle"));

        assertTrue(taskManager.approveTask(task.getTid()));
        assertEquals(TaskStatus.READY, taskManager.getTask(task.getTid()).getStatus());

        assertTrue(taskManager.pauseTask(task.getTid()));
        assertEquals(TaskStatus.PAUSED, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(List.of(task.getTid()), scheduler.pausedTaskIds);

        assertTrue(taskManager.resumeTask(task.getTid()));
        assertEquals(TaskStatus.READY, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(List.of(task.getTid()), scheduler.resumedTaskIds);
    }

    @Test
    void resumeTaskDetailedReportsReadyOutcome() {
        Task task = taskManager.createTask(buildRequest("task-resume-detailed"));
        assertTrue(taskManager.approveTask(task.getTid()));
        assertTrue(taskManager.pauseTask(task.getTid()));

        TaskResumeResult result = taskManager.resumeTaskDetailed(task.getTid());

        assertTrue(result.isSuccess());
        assertEquals(TaskResumeResult.Outcome.RESUMED_TO_READY, result.getOutcome());
        assertEquals(TaskStatus.READY, result.getStatus());
        assertNull(result.getTerminalReason());
    }

    @Test
    void blockedTaskCanBeApprovedBackToReady() {
        Task task = taskManager.createTask(buildRequest("task-blocked"));

        assertTrue(taskManager.rejectTask(task.getTid()));
        assertEquals(TaskStatus.BLOCKED, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(TaskHoldReason.REVIEW_REJECTED, taskManager.getTask(task.getTid()).getHoldReason());

        assertTrue(taskManager.approveTask(task.getTid()));
        assertEquals(TaskStatus.READY, taskManager.getTask(task.getTid()).getStatus());
        assertNull(taskManager.getTask(task.getTid()).getHoldReason());
    }

    @Test
    void taskReadyListenersRunOnApproveAndResume() {
        Task task = taskManager.createTask(buildRequest("task-ready-hook"));
        AtomicInteger notifications = new AtomicInteger();
        taskManager.events().addTaskReadyListener(t -> {
            if (task.getTid().equals(t.getTid())) {
                notifications.incrementAndGet();
            }
        });

        assertTrue(taskManager.approveTask(task.getTid()));
        assertTrue(taskManager.pauseTask(task.getTid()));
        assertTrue(taskManager.resumeTask(task.getTid()));

        assertEquals(2, notifications.get());
    }

    @Test
    void invalidActionsAreRejectedOutsideExpectedStates() {
        Task task = taskManager.createTask(buildRequest("task-invalid"));

        assertFalse(taskManager.pauseTask(task.getTid()));
        assertFalse(taskManager.resumeTask(task.getTid()));

        assertTrue(taskManager.approveTask(task.getTid()));
        assertFalse(taskManager.rejectTask(task.getTid()));
        assertFalse(taskManager.approveTask(task.getTid()));

        assertTrue(taskManager.cancelTask(task.getTid()));
        assertEquals(TaskStatus.TERMINAL, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(TaskTerminalReason.MANUAL_CANCELLED, taskManager.getTask(task.getTid()).getTerminalReason());
        assertFalse(taskManager.getTaskWorkRuntime().hasReadyWork(task.getTid()));
        assertFalse(taskManager.resumeTask(task.getTid()));
    }

    @Test
    void createTaskRejectsWhenNoInputsProvided() {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName("no-targets");
        dto.setProject("demoApp");
        dto.setSharedConfig(java.util.Map.of("textContent", "smoke", "routingCode", "us"));
        dto.setUserId("agent");
        dto.setInputs(null);
        dto.setBatchSize(0);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> taskManager.createTask(dto));
        assertTrue(error.getMessage().contains("inputs"));
    }

    @Test
    void createStreamTaskAllowsEmptyInitialInputsAndCreatesShell() {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName("stream-shell");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setOpenEnded(true);
        dto.setSourceType(TaskSourceType.STREAM);
        dto.setInputs(List.of());

        Task task = taskManager.createTask(dto);

        assertEquals(TaskStatus.NEW, task.getStatus());
        assertEquals(TaskSourceType.STREAM, task.getSourceType());
        assertEquals(TaskIngestStatus.READY, task.getIngestStatus());
        assertEquals(TaskIntakeStatus.OPEN, task.getIntakeStatus());
        assertEquals(0, task.getTaskTargetNumber());
        assertTrue(taskManager.getTaskMessages(task.getTid()).isEmpty());
    }

    @Test
    void createFileTaskAllowsEmptyInitialInputsAndCreatesPendingShell() {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName("file-shell");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setSourceType(TaskSourceType.FILE);
        dto.setSourceRef("mock/input/demo.csv");
        dto.setInputs(List.of());

        Task task = taskManager.createTask(dto);

        assertEquals(TaskStatus.NEW, task.getStatus());
        assertEquals(TaskSourceType.FILE, task.getSourceType());
        assertEquals(TaskIngestStatus.PENDING, task.getIngestStatus());
        assertEquals(TaskIntakeStatus.SEALED, task.getIntakeStatus());
        assertEquals("mock/input/demo.csv", task.getSourceRef());
        assertEquals(0, task.getTaskTargetNumber());
        assertTrue(taskManager.getTaskMessages(task.getTid()).isEmpty());
    }

    @Test
    void createFileTaskRequiresSourceRef() {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName("file-shell-no-ref");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setSourceType(TaskSourceType.FILE);
        dto.setInputs(List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> taskManager.createTask(dto));

        assertEquals("sourceRef is required for FILE task sources", error.getMessage());
    }

    @Test
    void createFileTaskRejectsInitialInputsToKeepFileIngestChunked() {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName("file-shell-with-inline-input");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setSourceType(TaskSourceType.FILE);
        dto.setSourceRef("mock/input/demo.csv");
        dto.setInputs(List.of(java.util.Map.of("target", "alpha")));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> taskManager.createTask(dto));

        assertEquals("FILE task sources must be created as a sourceRef shell; ingest work items in batches",
                error.getMessage());
    }

    @Test
    void payloadRefIngressEnqueuesRuntimeWorkWithoutRequiringTaskMsgInputPayload() {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName("payload-ref-ingress");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setOpenEnded(true);
        dto.setSourceType(TaskSourceType.STREAM);
        dto.setInputs(List.of());

        Task task = taskManager.createTask(dto);
        String messageId = java.util.UUID.randomUUID().toString();
        String payloadRef = "s3://bucket/payloads/demo-1.json";

        taskManager.addTaskPayloadRef(task.getTid(), messageId, payloadRef, 5);

        TaskMsg projection = taskManager.getTaskMessageProjection(task.getTid(), messageId);
        assertNotNull(projection);
        assertEquals(payloadRef, projection.getPayloadRef());
        assertTrue(projection.getInput().isEmpty());
        assertEquals(1, taskManager.getTaskWorkRuntime().stats(task.getTid()).readyCount());
        ClaimedTaskWork claimed = taskManager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(new WorkerClaimTarget("worker-payload-ref", "worker-context-payload-ref", "batch-0", 1)),
                1,
                taskManager.getTaskMessageLeaseSeconds()
        ).get(0);
        assertEquals(payloadRef, claimed.payloadRef());
        assertTrue(claimed.payload().isEmpty());
    }

    @Test
    void runtimeIngressStillConvergesWhenInitialTaskMsgProjectionWriteFails() {
        ProjectionWriteFailingTaskStorage failingStorage = new ProjectionWriteFailingTaskStorage();
        ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                new RecordingTaskScheduler(),
                failingStorage,
                failingStorage,
                new InMemoryTaskWorkRuntime()
        );

        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName("payload-ref-best-effort-ingress");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setOpenEnded(true);
        dto.setSourceType(TaskSourceType.STREAM);
        dto.setInputs(List.of());

        Task task = manager.createTask(dto);
        manager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        manager.updateTask(task);

        String messageId = java.util.UUID.randomUUID().toString();
        String payloadRef = "s3://bucket/payloads/demo-best-effort.json";
        failingStorage.failNextTaskMessageAdd();

        manager.addTaskPayloadRef(task.getTid(), messageId, payloadRef, 2);

        assertNull(manager.getStoredTaskMessageProjection(task.getTid(), messageId));
        assertEquals(1, manager.getTaskWorkRuntime().stats(task.getTid()).readyCount());

        ClaimedTaskWork claimed = manager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(new WorkerClaimTarget("worker-best-effort", "worker-context-best-effort", "batch-best-effort", 1)),
                1,
                manager.getTaskMessageLeaseSeconds()
        ).get(0);
        assertEquals(messageId, claimed.messageId());
        assertEquals(payloadRef, claimed.payloadRef());
        assertTrue(claimed.payload().isEmpty());

        assertTrue(manager.handleTaskMessageResult(
                task.getTid(),
                messageId,
                true,
                "done",
                null,
                java.util.Map.of("outcome", "success")
        ));

        TaskMsg projection = manager.getTaskMessageProjection(task.getTid(), messageId);
        assertNotNull(projection);
        assertEquals(TaskMsgStatus.SUCCESS, projection.getStatus());
        assertEquals(TaskMsgFinalReason.BUSINESS_SUCCESS, projection.getFinalReason());
        assertTrue(projection.getInput().isEmpty());
        assertEquals(java.util.Map.of("outcome", "success"), projection.getOutput());
    }

    @Test
    void createBatchTaskRejectsOversizedInlineInputs() {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName("oversized-inline");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setInputs(java.util.stream.IntStream.rangeClosed(0, TaskManager.MAX_INITIAL_INLINE_INPUTS)
                .mapToObj(i -> java.util.Map.<String, Object>of("target", "t-" + i))
                .toList());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> taskManager.createTask(dto));

        assertTrue(error.getMessage().contains("BATCH task initial inputs exceed inline create limit"));
    }

    @Test
    void fileTaskCanIngestItemsBeforeApprovalWithoutDispatch() {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName("file-ingest-before-approval");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setSourceType(TaskSourceType.FILE);
        dto.setSourceRef("mock/input/demo.csv");
        dto.setInputs(List.of());

        Task task = taskManager.createTask(dto);
        AtomicInteger dispatchRequests = new AtomicInteger();
        taskManager.events().addTaskDispatchListener(ignored -> dispatchRequests.incrementAndGet());

        int added = taskManager.appendTaskItems(task.getTid(), List.of(
                java.util.Map.<String, Object>of("target", "alpha"),
                java.util.Map.<String, Object>of("target", "beta")
        ));

        Task updatedTask = taskManager.getTask(task.getTid());
        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());

        assertEquals(2, added);
        assertEquals(TaskStatus.NEW, updatedTask.getStatus());
        assertEquals(TaskIngestStatus.READY, updatedTask.getIngestStatus());
        assertEquals(2, updatedTask.getTaskTargetNumber());
        assertEquals(2, messages.size());
        assertEquals(0, dispatchRequests.get());
    }

    @Test
    void appendTaskItemsRejectsOversizedIngestBatch() {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName("stream-shell-ingest-limit");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setOpenEnded(true);
        dto.setSourceType(TaskSourceType.STREAM);
        dto.setInputs(List.of());
        Task task = taskManager.createTask(dto);

        List<java.util.Map<String, Object>> oversizedBatch = java.util.stream.IntStream
                .rangeClosed(0, TaskManager.MAX_INGEST_BATCH_ITEMS)
                .mapToObj(i -> java.util.Map.<String, Object>of("target", "t-" + i))
                .toList();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> taskManager.appendTaskItems(task.getTid(), oversizedBatch));

        assertTrue(error.getMessage().contains("append inputs exceed ingest batch limit"));
    }

    @Test
    void interactiveTaskAppendRespectsWorkloadAwareReadyBackpressureCap() {
        String previousInteractiveCap = System.getProperty("xa.mass.engine.interactiveMaxReadyItemsPerTask");
        String previousBulkCap = System.getProperty("xa.mass.engine.bulkMaxReadyItemsPerTask");
        try {
            System.setProperty("xa.mass.engine.interactiveMaxReadyItemsPerTask", "2");
            System.setProperty("xa.mass.engine.bulkMaxReadyItemsPerTask", "100");

            InMemoryTaskStorage managerStorage = new InMemoryTaskStorage();
            ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                    new RecordingTaskScheduler(),
                    managerStorage,
                    managerStorage,
                    new InMemoryTaskWorkRuntime());
            TaskCreateRequestDto dto = new TaskCreateRequestDto();
            dto.setTaskName("interactive-backpressure");
            dto.setProject("demoApp");
            dto.setUserId("agent");
            dto.setOpenEnded(true);
            dto.setSourceType(TaskSourceType.STREAM);
            dto.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);
            dto.setInputs(List.of(java.util.Map.<String, Object>of("target", "alpha")));

            Task task = manager.createTask(dto);
            assertTrue(manager.approveTask(task.getTid()));

            assertEquals(1, manager.getTaskWorkRuntime().stats(task.getTid()).readyCount());
            assertEquals(1, manager.appendTaskItems(task.getTid(), List.of(
                    java.util.Map.<String, Object>of("target", "beta")
            )));

            IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                    manager.appendTaskItems(task.getTid(), List.of(
                            java.util.Map.<String, Object>of("target", "gamma")
                    )));

            assertTrue(error.getMessage().contains("BACKPRESSURE_REJECTED"));
            assertEquals(2, manager.getTaskMessages(task.getTid()).size());
            assertEquals(2, manager.getTaskWorkRuntime().stats(task.getTid()).readyCount());
        } finally {
            restoreProperty("xa.mass.engine.interactiveMaxReadyItemsPerTask", previousInteractiveCap);
            restoreProperty("xa.mass.engine.bulkMaxReadyItemsPerTask", previousBulkCap);
        }
    }

    @Test
    void createTaskRejectsWhenProjectIsMissing() {
        TaskCreateRequestDto dto = buildRequest("missing-project");
        dto.setProject(null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> taskManager.createTask(dto));

        assertEquals("project is required", error.getMessage());
    }

    @Test
    void createTaskRejectsWhenUserIdIsMissing() {
        TaskCreateRequestDto dto = buildRequest("missing-user");
        dto.setUserId("  ");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> taskManager.createTask(dto));

        assertEquals("userId is required", error.getMessage());
    }

    @Test
    void createTaskPersistsRequestedBatchSize() {
        TaskCreateRequestDto dto = buildRequest("batch-size");
        dto.setBatchSize(3);

        Task task = taskManager.createTask(dto);

        assertEquals(3, task.getBatchSize());
    }

    @Test
    void deleteTaskRejectedForReadyTask() {
        Task task = taskManager.createTask(buildRequest("del-ready"));
        taskManager.approveTask(task.getTid()); // NEW -> READY

        assertFalse(taskManager.deleteTask(task.getTid()),
                "READY task must not be deletable");
        assertNotNull(taskManager.getTask(task.getTid()),
                "Task should still exist after rejected delete");
    }

    @Test
    void deleteTaskAllowedForNewTask() {
        Task task = taskManager.createTask(buildRequest("del-new"));
        assertTrue(taskManager.getTaskWorkRuntime().hasReadyWork(task.getTid()));

        assertTrue(taskManager.deleteTask(task.getTid()),
                "NEW task should be deletable");
        assertNull(taskManager.getTask(task.getTid()));
        assertFalse(taskManager.getTaskWorkRuntime().hasReadyWork(task.getTid()));
    }

    @Test
    void deleteTaskAllowedForTerminalTask() {
        Task task = taskManager.createTask(buildRequest("del-terminal"));
        taskManager.approveTask(task.getTid());
        taskManager.cancelTask(task.getTid()); // -> TERMINAL

        assertTrue(taskManager.deleteTask(task.getTid()),
                "TERMINAL task should be deletable");
        assertNull(taskManager.getTask(task.getTid()));
    }

    @Test
    void handleTaskMessageResultMarksSuccessAndFinishesRunningTask() {
        Task task = taskManager.createTask(buildRequest("task-result-success"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        messages.forEach(msg -> assignMessage(task, msg));

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).getMessageId(), true, "done-1"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).getMessageId(), true, "done-2"));

        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, updatedTask.getTerminalReason());
        assertEquals(2, updatedTask.getTaskSuccessNumber());
        assertEquals(TaskMsgStatus.SUCCESS, taskManager.getTaskMessageProjection(task.getTid(), messages.get(0).getMessageId()).getStatus());
        assertEquals(TaskMsgStatus.SUCCESS, taskManager.getTaskMessageProjection(task.getTid(), messages.get(1).getMessageId()).getStatus());
        assertTrue(taskManager.getTaskMessageProjection(task.getTid(), messages.get(0).getMessageId()).getInput().isEmpty());
        assertTrue(taskManager.getTaskMessageProjection(task.getTid(), messages.get(1).getMessageId()).getInput().isEmpty());
    }

    @Test
    void handleTaskMessageResultEmitsRunningSuccessAndTerminalTrace() {
        Task task = taskManager.createTask(buildRequest("task-result-trace", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), true, "done"));
            capture.assertHasEvent("TASK_MSG_STATUS_TRANSITION", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.getMessageId().equals(mdc.get("messageId"))
                            && "ASSIGNED".equals(mdc.get("fromStatus"))
                            && "RUNNING".equals(mdc.get("toStatus")));
            capture.assertHasEvent("TASK_MSG_STATUS_TRANSITION", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.getMessageId().equals(mdc.get("messageId"))
                            && "RUNNING".equals(mdc.get("fromStatus"))
                            && "SUCCESS".equals(mdc.get("toStatus")));
            capture.assertHasEvent("TASK_TERMINAL_CLOSED", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && "ALL_MESSAGES_SUCCEEDED".equals(mdc.get("terminalReason")));
        }
    }

    @Test
    void handleTaskMessageResultEmitsTaskProgressSnapshot() {
        Task task = taskManager.createTask(buildRequest("task-progress-snapshot", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), true, "done"));
            capture.assertHasEvent("TASK_PROGRESS_SNAPSHOT", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && "FINALIZED_TO_TERMINAL".equals(mdc.get("resolutionOutcome"))
                            && "TERMINAL".equals(mdc.get("taskStatus"))
                            && "ALL_MESSAGES_SUCCEEDED".equals(mdc.get("terminalReason"))
                            && "1".equals(mdc.get("totalMessages"))
                            && "1".equals(mdc.get("successMessages"))
                            && "0".equals(mdc.get("pendingMessages"))
                            && "100.0".equals(mdc.get("progressPercent")));
        }
    }

    @Test
    void handleTaskMessageResultMarksFailureAndKeepsExecutedCountAtSuccessOnly() {
        Task task = taskManager.createTask(buildRequest("task-result-failure", List.of("alpha", "beta"), 0));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), false, "boom"));

        TaskMsg updatedMessage = taskManager.getTaskMessageProjection(task.getTid(), message.getMessageId());
        assertEquals(TaskMsgStatus.FAILED, updatedMessage.getStatus());
        assertEquals("boom", updatedMessage.getErrorMessage());
        assertEquals(0, taskManager.getTask(task.getTid()).getTaskSuccessNumber());
    }

    @Test
    void retryReusesSameTaskMessageAndFinalSuccessCountDoesNotInflate() {
        Task task = taskManager.createTask(buildRequest("task-result-retry", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        String messageId = message.getMessageId();
        assignMessage(task, message, "worker-1", "worker-context-1", "batch-0");

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messageId, false, "boom-once"));

        List<TaskMsg> afterRetryMessages = taskManager.getTaskMessages(task.getTid());
        assertEquals(1, afterRetryMessages.size());
        TaskMsg retriedMessage = afterRetryMessages.get(0);
        assertEquals(messageId, retriedMessage.getMessageId());
        assertEquals(TaskMsgStatus.INIT, retriedMessage.getStatus());
        assertEquals(1, retriedMessage.getRetryCount());
        assertNull(retriedMessage.getFinalReason());
        assertNull(retriedMessage.getLatestAttemptWorkerId());
        assertNull(retriedMessage.getLatestAttemptWorkerContextId());
        assertNull(retriedMessage.getLatestAttemptBatchId());
        assertNull(retriedMessage.getErrorMessage());
        assertEquals(TaskStatus.RUNNING, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(0, taskManager.getTask(task.getTid()).getTaskSuccessNumber());

        assignMessage(task, retriedMessage, "worker-2", "worker-context-2", "batch-1");
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messageId, true, "done-after-retry"));

        TaskMsg finalMessage = taskManager.getTaskMessageProjection(task.getTid(), messageId);
        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskMsgStatus.SUCCESS, finalMessage.getStatus());
        assertEquals(TaskMsgFinalReason.BUSINESS_SUCCESS, finalMessage.getFinalReason());
        assertEquals(1, finalMessage.getRetryCount());
        assertNull(finalMessage.getOutput());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, updatedTask.getTerminalReason());
        assertEquals(1, updatedTask.getTaskSuccessNumber());
    }

    @Test
    void retryEmitsRetryResetTrace() {
        Task task = taskManager.createTask(buildRequest("task-result-retry-trace", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message, "worker-1", "worker-context-1", "batch-0");

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), false, "boom-once"));
            capture.assertHasEvent("TASK_MSG_RETRY_RESET", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.getMessageId().equals(mdc.get("messageId"))
                            && "1".equals(mdc.get("retryCount"))
                            && "0".equals(mdc.get("workRetryDelayMillis"))
                            && "INIT".equals(mdc.get("toStatus")));
        }
    }

    @Test
    void interactiveRetryableFailureDelaysRuntimeVisibilityButStillRequestsRedispatch() throws InterruptedException {
        String previousInteractiveRetryDelay = System.getProperty("xa.mass.engine.interactiveWorkRetryDelayMillis");
        try {
            System.setProperty("xa.mass.engine.interactiveWorkRetryDelayMillis", "200");

        InMemoryTaskStorage managerStorage = new InMemoryTaskStorage();
        ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                new RecordingTaskScheduler(),
                managerStorage,
                managerStorage,
                new InMemoryTaskWorkRuntime());
            TaskCreateRequestDto dto = buildRequest("task-result-interactive-delayed-retry", List.of("alpha"));
            dto.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);
            Task task = manager.createTask(dto);
            manager.approveTask(task.getTid());
            task.setStatus(TaskStatus.RUNNING);
            manager.updateTask(task);

            TaskMsg message = manager.getTaskMessages(task.getTid()).get(0);
            assignMessage(manager, task, message, "worker-1", "worker-context-1", "batch-0");

            AtomicInteger dispatchEvents = new AtomicInteger();
            CountDownLatch dispatchLatch = new CountDownLatch(1);
            manager.events().addTaskDispatchListener(ignored -> {
                dispatchEvents.incrementAndGet();
                dispatchLatch.countDown();
            });

            assertTrue(manager.handleTaskMessageResult(task.getTid(), message.getMessageId(), false, "boom-once"));

        TaskMsg retriedMessage = manager.getTaskMessageProjection(task.getTid(), message.getMessageId());
            assertEquals(TaskMsgStatus.INIT, retriedMessage.getStatus());
            assertEquals(1, retriedMessage.getRetryCount());
            assertEquals(0, manager.getTaskWorkRuntime().stats(task.getTid()).readyCount());
            assertEquals(1, manager.getTaskWorkRuntime().stats(task.getTid()).delayedCount());
            assertEquals(0, dispatchEvents.get());
            assertFalse(dispatchLatch.await(100, TimeUnit.MILLISECONDS));
            assertTrue(dispatchLatch.await(2, TimeUnit.SECONDS));
            assertEquals(1, dispatchEvents.get());
        } finally {
            restoreProperty("xa.mass.engine.interactiveWorkRetryDelayMillis", previousInteractiveRetryDelay);
        }
    }

    @Test
    void delayedRetryWakeupIsCoalescedPerTaskUnderMultipleRetryableFailures() throws InterruptedException {
        String previousInteractiveRetryDelay = System.getProperty("xa.mass.engine.interactiveWorkRetryDelayMillis");
        try {
            System.setProperty("xa.mass.engine.interactiveWorkRetryDelayMillis", "200");

        InMemoryTaskStorage managerStorage = new InMemoryTaskStorage();
        ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                new RecordingTaskScheduler(),
                managerStorage,
                managerStorage,
                new InMemoryTaskWorkRuntime());
            TaskCreateRequestDto dto = buildRequest("task-result-interactive-coalesced-retry", List.of("alpha", "beta"), 1);
            dto.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);
            Task task = manager.createTask(dto);
            manager.approveTask(task.getTid());
            task.setStatus(TaskStatus.RUNNING);
            manager.updateTask(task);

            List<TaskMsg> messages = manager.getTaskMessages(task.getTid());
            messages.forEach(message -> assignMessage(manager, task, message));

            AtomicInteger dispatchEvents = new AtomicInteger();
            CountDownLatch dispatchLatch = new CountDownLatch(1);
            manager.events().addTaskDispatchListener(ignored -> {
                if (task.getTid().equals(ignored.getTid())) {
                    dispatchEvents.incrementAndGet();
                    dispatchLatch.countDown();
                }
            });

            assertTrue(manager.handleTaskMessageResult(
                    task.getTid(),
                    messages.get(0).getMessageId(),
                    false,
                    "retry-alpha",
                    "SYNTHETIC_RETRY",
                    null
            ));
            assertTrue(manager.handleTaskMessageResult(
                    task.getTid(),
                    messages.get(1).getMessageId(),
                    false,
                    "retry-beta",
                    "SYNTHETIC_RETRY",
                    null
            ));

            assertTrue(dispatchLatch.await(2, TimeUnit.SECONDS));
            Thread.sleep(250);

            assertEquals(1, dispatchEvents.get());
            assertEquals(TaskMsgStatus.INIT,
                    manager.getTaskMessageProjection(task.getTid(), messages.get(0).getMessageId()).getStatus());
            assertEquals(TaskMsgStatus.INIT,
                    manager.getTaskMessageProjection(task.getTid(), messages.get(1).getMessageId()).getStatus());
            assertEquals(1,
                    manager.getTaskMessageProjection(task.getTid(), messages.get(0).getMessageId()).getRetryCount());
            assertEquals(1,
                    manager.getTaskMessageProjection(task.getTid(), messages.get(1).getMessageId()).getRetryCount());
        } finally {
            restoreProperty("xa.mass.engine.interactiveWorkRetryDelayMillis", previousInteractiveRetryDelay);
        }
    }

    @Test
    void callbackWithoutActiveLeaseIsRejectedAndTraced() {
        Task task = taskManager.createTask(buildRequest("task-result-no-active-attempt", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assertTrue(message.markAsAssigned());
        taskManager.updateTaskMessageProjection(task.getTid(), message);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertFalse(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), true, "done"));
            capture.assertHasEvent("CALLBACK_REJECTED_NO_ACTIVE_LEASE", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.getMessageId().equals(mdc.get("messageId"))
                            && "ASSIGNED".equals(mdc.get("taskMsgStatus")));
        }
    }

    @Test
    void callbackForInitProjectionWithoutRuntimeLeaseIsRejectedAndTraced() {
        Task task = taskManager.createTask(buildRequest("task-result-init-callback", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        TaskMsgAttempt attempt = new TaskMsgAttempt("attempt-init", task.getTid(), message.getMessageId(), 1);
        attempt.setWorkerId("worker-init");
        attempt.setWorkerContextId("worker-context-init");
        attempt.setBatchId("batch-init");
        assertTrue(attempt.markLeased(LocalDateTime.now().plusMinutes(5)));
        assertTrue(attempt.markDispatched());
        taskManager.addTaskMessageAttemptAuditProjection(task.getTid(), message.getMessageId(), attempt);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertFalse(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), true, "done"));
            capture.assertHasEvent("CALLBACK_REJECTED_NO_ACTIVE_LEASE", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.getMessageId().equals(mdc.get("messageId"))
                            && "INIT".equals(mdc.get("taskMsgStatus")));
        }

        TaskMsg persistedMessage = taskManager.getTaskMessageProjection(task.getTid(), message.getMessageId());
        assertEquals(TaskMsgStatus.INIT, persistedMessage.getStatus());
        assertEquals(TaskMsgAttemptStatus.DISPATCHED,
                taskManager.getLatestTaskMessageAttemptAuditView(task.getTid(), message.getMessageId()).getStatus());
    }

    @Test
    void callbackWithRuntimeLeaseRepairsInitProjectionAndSucceeds() {
        Task task = taskManager.createTask(buildRequest("task-result-runtime-lease-repair", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message, "worker-repair", "worker-context-repair", "batch-repair");
        message.setStatus(TaskMsgStatus.INIT);
        message.clearLatestAttemptProjection();
        taskManager.updateTaskMessageProjection(task.getTid(), message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), true, "done"));

        TaskMsg updatedMessage = taskManager.getTaskMessageProjection(task.getTid(), message.getMessageId());
        TaskMsgAttempt latestAttempt = taskManager.getLatestTaskMessageAttemptAuditView(task.getTid(), message.getMessageId());
        assertEquals(TaskMsgStatus.SUCCESS, updatedMessage.getStatus());
        assertEquals(TaskMsgFinalReason.BUSINESS_SUCCESS, updatedMessage.getFinalReason());
        assertEquals("worker-repair", updatedMessage.getLatestAttemptWorkerId());
        assertEquals("worker-context-repair", updatedMessage.getLatestAttemptWorkerContextId());
        assertEquals("batch-repair", updatedMessage.getLatestAttemptBatchId());
        assertNotNull(latestAttempt);
        assertEquals(TaskMsgAttemptStatus.SUCCEEDED, latestAttempt.getStatus());
    }

    @Test
    void callbackWithRuntimeLeaseRecoversMissingAttemptProjection() {
        Task task = taskManager.createTask(buildRequest("task-result-runtime-attempt-recovery", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        List<ClaimedTaskWork> claimed = taskManager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(new WorkerClaimTarget("worker-recover", "worker-context-recover", "batch-recover", 1)),
                1,
                taskManager.getTaskMessageLeaseSeconds()
        );
        assertEquals(1, claimed.size());
        message.applyLatestAttemptProjection("worker-recover", "worker-context-recover", "batch-recover");
        assertTrue(message.markAsAssigned());
        taskManager.updateTaskMessageProjection(task.getTid(), message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), true, "done"));

        TaskMsgAttempt recoveredAttempt = taskManager.getLatestTaskMessageAttemptAuditView(task.getTid(), message.getMessageId());
        assertNotNull(recoveredAttempt);
        assertEquals(TaskMsgAttemptStatus.SUCCEEDED, recoveredAttempt.getStatus());
        assertEquals("worker-recover", recoveredAttempt.getWorkerId());
        assertEquals("worker-context-recover", recoveredAttempt.getWorkerContextId());
        assertEquals("batch-recover", recoveredAttempt.getBatchId());
    }

    @Test
    void callbackWithRuntimeLeaseDoesNotReadLatestAttemptAuditOnHotPath() {
        TrackingLatestAttemptStorage trackingStorage = new TrackingLatestAttemptStorage();
        taskStorage = trackingStorage;
        taskManager = new ProjectionAwareTaskManager(scheduler, trackingStorage, trackingStorage, new InMemoryTaskWorkRuntime());

        Task task = taskManager.createTask(buildRequest("task-result-runtime-attempt-no-audit-read", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        List<ClaimedTaskWork> claimed = taskManager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(new WorkerClaimTarget("worker-no-read", "worker-context-no-read", "batch-no-read", 1)),
                1,
                taskManager.getTaskMessageLeaseSeconds()
        );
        assertEquals(1, claimed.size());

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), true, "done"));

        assertEquals(0, trackingStorage.latestAttemptReadCount.get(),
                "result hot path should derive attempt correlation from runtime lease without reading latest attempt audit rows");
        TaskMsgAttempt latestAttempt = taskManager.getLatestTaskMessageAttemptAuditView(task.getTid(), message.getMessageId());
        assertNotNull(latestAttempt);
        assertEquals(TaskMsgAttemptStatus.SUCCEEDED, latestAttempt.getStatus());
    }

    @Test
    void callbackWithRuntimeLeaseOverridesFinalTaskMsgProjectionResidue() {
        Task task = taskManager.createTask(buildRequest("task-result-runtime-final-projection-residue", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message, "worker-final-residue", "worker-context-final-residue", "batch-final-residue");
        message.forceFinalize(TaskMsgStatus.FAILED, TaskMsgFinalReason.BUSINESS_FAILED, "stale-final-projection");
        message.setErrorCode("STALE");
        message.setOutput(java.util.Map.of("stale", true));
        taskManager.updateTaskMessageProjection(task.getTid(), message);

        assertTrue(taskManager.handleTaskMessageResult(
                task.getTid(),
                message.getMessageId(),
                true,
                "done",
                null,
                java.util.Map.of("fresh", true)
        ));

        TaskMsg updatedMessage = taskManager.getTaskMessageProjection(task.getTid(), message.getMessageId());
        TaskMsgAttempt latestAttempt = taskManager.getLatestTaskMessageAttemptAuditView(task.getTid(), message.getMessageId());

        assertEquals(TaskMsgStatus.SUCCESS, updatedMessage.getStatus());
        assertEquals(TaskMsgFinalReason.BUSINESS_SUCCESS, updatedMessage.getFinalReason());
        assertNull(updatedMessage.getErrorCode());
        assertEquals(java.util.Map.of("fresh", true), updatedMessage.getOutput());
        assertEquals("worker-final-residue", updatedMessage.getLatestAttemptWorkerId());
        assertNotNull(latestAttempt);
        assertEquals(TaskMsgAttemptStatus.SUCCEEDED, latestAttempt.getStatus());
    }

    @Test
    void retryableFailurePublishesAttemptClosedBeforeDispatchRequested() {
        Task task = taskManager.createTask(buildRequest("task-result-retry-order", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message, "worker-1", "worker-context-1", "batch-0");

        List<String> events = new java.util.ArrayList<>();
        taskManager.events().addTaskMessageAttemptClosedListener((currentTask, attempt) ->
                events.add("attempt-closed:" + attempt.status()));
        taskManager.events().addTaskMessageLogicallyFinalListener((currentTask, event) ->
                events.add("logical-final:" + event.status()));
        taskManager.events().addTaskDispatchListener(currentTask ->
                events.add("dispatch:" + currentTask.getStatus()));

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), false, "boom-once"));

        assertEquals(List.of("attempt-closed:REVOKED", "dispatch:RUNNING"), events);
    }

    @Test
    void terminalCompletionPublishesLogicalFinalBeforeTerminalNotification() {
        Task task = taskManager.createTask(buildRequest("task-terminal-event-order", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message);

        List<String> events = new java.util.ArrayList<>();
        taskManager.events().addTaskMessageLogicallyFinalListener((currentTask, event) -> events.add("logical-final"));
        taskManager.events().addTaskTerminalListener(currentTask -> events.add("terminal"));

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), true, "done"));

        assertEquals(List.of("logical-final", "terminal"), events);
    }

    @Test
    void retryExhaustedFailureMarksAttemptAsBusinessFailure() {
        Task task = taskManager.createTask(buildRequest("task-result-retry-exhausted-attempt", List.of("alpha"), 0));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), false, "boom-final"));

        TaskMsgAttempt attempt = taskManager.getLatestTaskMessageAttemptAuditView(task.getTid(), message.getMessageId());
        assertNotNull(attempt);
        assertEquals(TaskMsgAttemptStatus.FAILED, attempt.getStatus());
        assertEquals(TaskMsgAttemptFinalReason.BUSINESS_FAILURE, attempt.getFinalReason());
    }

    @Test
    void resolveTaskStateReportsNotFinalizedWhileMessagesRemainOpen() {
        Task task = taskManager.createTask(buildRequest("task-resolution-pending"));
        taskManager.approveTask(task.getTid());

        TaskStateResolutionResult result = taskManager.resolveTaskState(task.getTid());

        assertEquals(TaskStateResolutionResult.Outcome.NOT_FINALIZED, result.getOutcome());
        assertEquals(TaskStatus.READY, result.getStatus());
        assertEquals(2, result.getTotalMessages());
        assertEquals(0, result.getSuccessMessages());
        assertEquals(0, result.getFailedMessages());
        assertNull(result.getTerminalReason());
    }

    @Test
    void pausedTaskCompletesToTerminalWhenFinalResultArrives() {
        Task task = taskManager.createTask(buildRequest("task-paused-completion", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.pauseTask(task.getTid()));
        assertEquals(TaskStatus.PAUSED, taskManager.getTask(task.getTid()).getStatus());

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), true, "done-while-paused"));

        Task updatedTask = taskManager.getTask(task.getTid());
        TaskMsg updatedMessage = taskManager.getTaskMessageProjection(task.getTid(), message.getMessageId());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, updatedTask.getTerminalReason());
        assertEquals(1, updatedTask.getTaskSuccessNumber());
        assertEquals(TaskMsgStatus.SUCCESS, updatedMessage.getStatus());
        assertEquals(List.of(task.getTid()), scheduler.pausedTaskIds);
        assertTrue(scheduler.resumedTaskIds.isEmpty());
    }

    @Test
    void resolveTaskStateFinalizesRunningTaskWhenAllMessagesAreFinal() {
        Task task = taskManager.createTask(buildRequest("task-resolution-finalized"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        messages.forEach(msg -> assignMessage(task, msg));
        taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).getMessageId(), true, "done-1");
        taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).getMessageId(), true, "done-2");

        task.setStatus(TaskStatus.RUNNING);
        task.setTerminalReason(null);
        taskManager.updateTask(task);

        TaskStateResolutionResult result = taskManager.resolveTaskState(task.getTid());

        assertEquals(TaskStateResolutionResult.Outcome.FINALIZED_TO_TERMINAL, result.getOutcome());
        assertEquals(TaskStatus.TERMINAL, result.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, result.getTerminalReason());
        assertEquals(2, result.getSuccessMessages());
        assertEquals(0, result.getFailedMessages());
    }

    @Test
    void resolveTaskStateReportsAlreadyFinalForManuallyCancelledTask() {
        Task task = taskManager.createTask(buildRequest("task-resolution-already-final", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.cancelTask(task.getTid()));

        TaskStateResolutionResult result = taskManager.resolveTaskState(task.getTid());

        assertEquals(TaskStateResolutionResult.Outcome.ALREADY_FINAL, result.getOutcome());
        assertEquals(TaskStatus.TERMINAL, result.getStatus());
        assertEquals(TaskTerminalReason.MANUAL_CANCELLED, result.getTerminalReason());
        assertEquals(0, result.getTotalMessages());
    }

    @Test
    void resumeTaskDetailedReportsTerminalOutcomeWhenPausedTaskAlreadyCompleted() {
        Task task = taskManager.createTask(buildRequest("task-paused-resume-terminal", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.pauseTask(task.getTid()));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), true, "done-while-paused"));

        task.setStatus(TaskStatus.PAUSED);
        task.setTerminalReason(null);
        taskManager.updateTask(task);

        TaskResumeResult result = taskManager.resumeTaskDetailed(task.getTid());

        assertTrue(result.isSuccess());
        assertEquals(TaskResumeResult.Outcome.COMPLETED_TO_TERMINAL, result.getOutcome());
        assertEquals(TaskStatus.TERMINAL, result.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, result.getTerminalReason());
        assertTrue(scheduler.resumedTaskIds.isEmpty());
    }

    @Test
    void openEndedTaskStaysNonTerminalUntilSealed() {
        TaskCreateRequestDto request = buildRequest("task-open-ended", List.of("alpha"));
        request.setOpenEnded(true);
        Task task = taskManager.createTask(request);

        assertEquals(TaskIntakeStatus.OPEN, task.getIntakeStatus());

        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), true, "done"));

        Task beforeSeal = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.RUNNING, beforeSeal.getStatus());
        assertNull(beforeSeal.getTerminalReason());
        assertEquals(TaskIntakeStatus.OPEN, beforeSeal.getIntakeStatus());

        assertTrue(taskManager.sealTask(task.getTid()));

        Task sealed = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, sealed.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, sealed.getTerminalReason());
        assertEquals(TaskIntakeStatus.SEALED, sealed.getIntakeStatus());
    }

    @Test
    void fileTaskStaysNonTerminalUntilIngestSealed() {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName("file-task-open-ingest");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setSourceType(TaskSourceType.FILE);
        dto.setSourceRef("mock/input/demo.csv");
        dto.setInputs(List.of());

        Task task = taskManager.createTask(dto);
        assertEquals(TaskIngestStatus.PENDING, task.getIngestStatus());

        assertEquals(1, taskManager.appendTaskItems(task.getTid(), List.of(
                java.util.Map.<String, Object>of("target", "alpha")
        )));

        assertTrue(taskManager.approveTask(task.getTid()));
        Task runningTask = taskManager.getTask(task.getTid());
        runningTask.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(runningTask);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(runningTask, message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), true, "done"));

        Task beforeSeal = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.RUNNING, beforeSeal.getStatus());
        assertNull(beforeSeal.getTerminalReason());
        assertEquals(TaskIngestStatus.READY, beforeSeal.getIngestStatus());

        assertTrue(taskManager.sealTask(task.getTid()));

        Task sealed = taskManager.getTask(task.getTid());
        assertEquals(TaskIngestStatus.SEALED, sealed.getIngestStatus());
        assertEquals(TaskStatus.TERMINAL, sealed.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, sealed.getTerminalReason());
    }

    @Test
    void pausedOpenEndedTaskCanAppendWithoutImmediateDispatch() {
        TaskCreateRequestDto request = buildRequest("task-open-ended-paused-append", List.of("alpha"));
        request.setOpenEnded(true);
        Task task = taskManager.createTask(request);
        taskManager.approveTask(task.getTid());
        assertTrue(taskManager.pauseTask(task.getTid()));

        AtomicInteger dispatchRequests = new AtomicInteger();
        taskManager.events().addTaskDispatchListener(ignored -> dispatchRequests.incrementAndGet());

        int added = taskManager.appendTaskItems(task.getTid(), List.of(
                java.util.Map.<String, Object>of("target", "beta"),
                java.util.Map.<String, Object>of("target", "gamma")
        ));

        Task updatedTask = taskManager.getTask(task.getTid());
        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());

        assertEquals(2, added);
        assertEquals(TaskStatus.PAUSED, updatedTask.getStatus());
        assertEquals(TaskIntakeStatus.OPEN, updatedTask.getIntakeStatus());
        assertEquals(3, messages.size());
        assertEquals(0, dispatchRequests.get());
    }

    @Test
    void lateCallbackAfterCancelDoesNotMutateTerminalTask() {
        Task task = taskManager.createTask(buildRequest("task-cancel-late-callback", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.cancelTask(task.getTid()));
        assertEquals(TaskStatus.TERMINAL, taskManager.getTask(task.getTid()).getStatus());

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), true, "late-success"));

        Task updatedTask = taskManager.getTask(task.getTid());
        TaskMsg updatedMessage = taskManager.getTaskMessageProjection(task.getTid(), message.getMessageId());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.MANUAL_CANCELLED, updatedTask.getTerminalReason());
        assertEquals(0, updatedTask.getTaskSuccessNumber());
        // terminal task reads overlay the compatibility view without rewriting
        // every queued or leased TaskMsg projection row
        assertEquals(TaskMsgStatus.EXPIRED, updatedMessage.getStatus());
    }

    @Test
    void lateCallbackEmitsIgnoredLateTrace() {
        Task task = taskManager.createTask(buildRequest("task-late-trace", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message);

        task.setStatus(TaskStatus.TERMINAL);
        task.setTerminalReason(TaskTerminalReason.MANUAL_CANCELLED);
        taskManager.updateTask(task);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), true, "late-success"));
            capture.assertHasEvent("CALLBACK_IGNORED_LATE", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.getMessageId().equals(mdc.get("messageId")));
        }
    }

    @Test
    void duplicateTaskMessageResultKeepsFirstFinalStateAndDoesNotTriggerSchedulerTwice() {
        Task task = taskManager.createTask(buildRequest("task-result-duplicate", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), true, "done-once"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), false, "boom-twice"));

        TaskMsg updatedMessage = taskManager.getTaskMessageProjection(task.getTid(), message.getMessageId());
        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskMsgStatus.SUCCESS, updatedMessage.getStatus());
        assertNull(updatedMessage.getOutput());
        assertNull(updatedMessage.getErrorMessage());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, updatedTask.getTerminalReason());
        assertEquals(1, updatedTask.getTaskSuccessNumber());
    }

    @Test
    void duplicateCallbackEmitsIgnoredDuplicateTrace() {
        Task task = taskManager.createTask(buildRequest("task-result-duplicate-trace", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), true, "done-once"));

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), true, "done-twice"));
            capture.assertHasEvent("CALLBACK_IGNORED_DUPLICATE", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.getMessageId().equals(mdc.get("messageId")));
        }
    }

    @Test
    void mixedFinalTaskMessagesProduceMixedTerminalReason() {
        Task task = taskManager.createTask(buildRequest("task-result-mixed", List.of("alpha", "beta"), 0));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        messages.forEach(msg -> assignMessage(task, msg));

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).getMessageId(), true, "done"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).getMessageId(), false, "boom"));

        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.MIXED_MESSAGE_RESULTS, updatedTask.getTerminalReason());
        assertEquals(1, updatedTask.getTaskSuccessNumber());
    }

    @Test
    void allFailedTaskMessagesProduceFailedTerminalReason() {
        Task task = taskManager.createTask(buildRequest("task-result-all-failed", List.of("alpha", "beta"), 0));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        messages.forEach(msg -> assignMessage(task, msg));

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).getMessageId(), false, "boom-1"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).getMessageId(), false, "boom-2"));

        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_FAILED, updatedTask.getTerminalReason());
        assertEquals(0, updatedTask.getTaskSuccessNumber());
    }

    @Test
    void validateTaskStateReportsValidTerminalSuccessTask() {
        Task task = taskManager.createTask(buildRequest("task-validate-valid-terminal"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        messages.forEach(msg -> assignMessage(task, msg));

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).getMessageId(), true, "done-1"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).getMessageId(), true, "done-2"));

        TaskStateValidationResult result = taskManager.validateTaskState(task.getTid());

        assertTrue(result.isValid());
        assertFalse(result.isNeedsResolution());
        assertEquals(TaskStatus.TERMINAL, result.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, result.getTerminalReason());
        assertEquals(2, result.getTotalMessages());
        assertEquals(2, result.getSuccessMessages());
        assertEquals(0, result.getFailedMessages());
        assertTrue(result.getViolations().isEmpty());
    }

    @Test
    void validateTaskStateRejectsBlockedTaskWithoutHoldReason() {
        Task task = taskManager.createTask(buildRequest("task-validate-blocked-hold-reason"));
        assertTrue(taskManager.rejectTask(task.getTid()));

        Task blocked = taskManager.getTask(task.getTid());
        blocked.setHoldReason(null);
        taskManager.updateTask(blocked);

        TaskStateValidationResult result = taskManager.validateTaskState(task.getTid());

        assertFalse(result.isValid());
        assertTrue(result.getViolations().contains(
                TaskStateValidationResult.ViolationCode.BLOCKED_HOLD_REASON_MISSING));
    }

    @Test
    void auditTaskProjectionStateRejectsCompletedMessageWithoutFinalReason() {
        Task task = taskManager.createTask(buildRequest("task-validate-message-final-reason", List.of("alpha")));
        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        message.markAsRunning();
        assertTrue(message.markAsSuccess("done"));
        message.setFinalReason(null);
        taskManager.updateTaskMessageProjection(task.getTid(), message);

        TaskStateValidationResult result = taskManager.auditTaskProjectionState(task.getTid());

        assertFalse(result.isValid());
        assertEquals(TaskStateValidationResult.Scope.PROJECTION_AUDIT, result.getScope());
        assertTrue(result.getViolations().contains(
                TaskStateValidationResult.ViolationCode.TASK_MSG_FINAL_REASON_MISSING));
    }

    @Test
    void auditTaskProjectionStateFlagsActiveAttemptWithFinalMessage() {
        Task task = taskManager.createTask(buildRequest("task-validate-active-attempt-final-message", List.of("alpha")));
        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.markAsAssigned();
        message.markAsRunning();
        assertTrue(message.markAsSuccess("done", TaskMsgFinalReason.BUSINESS_SUCCESS));
        taskManager.updateTaskMessageProjection(task.getTid(), message);

        TaskMsgAttempt activeAttempt = new TaskMsgAttempt("attempt-1", task.getTid(), message.getMessageId(), 1);
        activeAttempt.setWorkerId("worker-1");
        assertTrue(activeAttempt.markLeased(java.time.LocalDateTime.now().plusMinutes(1)));
        taskManager.addTaskMessageAttemptAuditProjection(task.getTid(), message.getMessageId(), activeAttempt);

        TaskStateValidationResult result = taskManager.auditTaskProjectionState(task.getTid());

        assertFalse(result.isValid());
        assertEquals(TaskStateValidationResult.Scope.PROJECTION_AUDIT, result.getScope());
        assertTrue(result.getViolations().contains(
                TaskStateValidationResult.ViolationCode.ACTIVE_ATTEMPT_WITH_FINAL_MESSAGE));
    }

    @Test
    void auditTaskProjectionStateFlagsMultipleActiveAttemptsForMessage() {
        Task task = taskManager.createTask(buildRequest("task-validate-multiple-active-attempts", List.of("alpha")));
        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message, "worker-1", "worker-context-1", "batch-0");

        TaskMsgAttempt secondActiveAttempt = new TaskMsgAttempt("attempt-2", task.getTid(), message.getMessageId(), 2);
        secondActiveAttempt.setWorkerId("worker-2");
        secondActiveAttempt.setWorkerContextId("worker-context-2");
        secondActiveAttempt.setBatchId("batch-1");
        assertTrue(secondActiveAttempt.markLeased(LocalDateTime.now().plusMinutes(1)));
        taskManager.addTaskMessageAttemptAuditProjection(task.getTid(), message.getMessageId(), secondActiveAttempt);

        TaskStateValidationResult result = taskManager.auditTaskProjectionState(task.getTid());

        assertFalse(result.isValid());
        assertEquals(TaskStateValidationResult.Scope.PROJECTION_AUDIT, result.getScope());
        assertTrue(result.getViolations().contains(
                TaskStateValidationResult.ViolationCode.MULTIPLE_ACTIVE_ATTEMPTS_FOR_MESSAGE));
    }

    @Test
    void validateTaskStateReportsNeedsResolutionWhenMessagesAreFinalButTaskIsStillRunning() {
        Task task = taskManager.createTask(buildRequest("task-validate-needs-resolution"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        messages.forEach(msg -> assignMessage(task, msg));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).getMessageId(), true, "done-1"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).getMessageId(), true, "done-2"));

        task.setStatus(TaskStatus.RUNNING);
        task.setTerminalReason(null);
        taskManager.updateTask(task);

        TaskStateValidationResult result = taskManager.validateTaskState(task.getTid());

        assertTrue(result.isValid());
        assertTrue(result.isNeedsResolution());
        assertEquals(TaskStatus.RUNNING, result.getStatus());
        assertNull(result.getTerminalReason());
        assertEquals(2, result.getSuccessMessages());
        assertEquals(0, result.getFailedMessages());
        assertTrue(result.getViolations().isEmpty());
    }

    @Test
    void validateTaskStateEmitsValidationSummaryWhenResolutionIsNeeded() {
        Task task = taskManager.createTask(buildRequest("task-validate-needs-resolution-trace"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        messages.forEach(msg -> assignMessage(task, msg));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).getMessageId(), true, "done-1"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).getMessageId(), true, "done-2"));

        task.setStatus(TaskStatus.RUNNING);
        task.setTerminalReason(null);
        taskManager.updateTask(task);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            TaskStateValidationResult result = taskManager.validateTaskState(task.getTid());
            assertTrue(result.isValid());
            assertTrue(result.isNeedsResolution());
            capture.assertHasEvent("TASK_STATE_VALIDATION_SUMMARY", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && "RUNNING".equals(mdc.get("taskStatus"))
                            && "true".equals(mdc.get("valid"))
                            && "true".equals(mdc.get("needsResolution"))
                            && "RUNTIME".equals(mdc.get("validationScope"))
                            && "0".equals(mdc.get("violationCount"))
                            && "ANOMALY".equals(mdc.get("result")));
        }
    }

    @Test
    void validateTaskStateStaysOffFullTaskMessageSnapshots() {
        PagingAwareTaskStorage pagingStorage = new PagingAwareTaskStorage();
        ProjectionAwareTaskManager pagingTaskManager = new ProjectionAwareTaskManager(scheduler, pagingStorage, pagingStorage, new InMemoryTaskWorkRuntime());
        Task task = pagingTaskManager.createTask(buildRequest("validate-paged", List.of("a", "b", "c")));
        pagingTaskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        pagingTaskManager.updateTask(task);

        List<TaskMsg> messages = pagingTaskManager.getTaskMessages(task.getTid());
        assignMessage(pagingTaskManager, task, messages.get(1));

        pagingStorage.resetTraversalCounters();

        TaskStateValidationResult result = pagingTaskManager.validateTaskState(task.getTid());

        assertTrue(result.isValid());
        assertEquals(TaskStateValidationResult.Scope.RUNTIME, result.getScope());
        assertEquals(0, pagingStorage.fullSnapshotReadCount.get(), "runtime validation should not read full task message snapshots");
        assertEquals(0, pagingStorage.attemptStatsReadCount.get(), "runtime validation should not read per-message attempt stats");
        assertEquals(0, pagingStorage.attemptSnapshotReadCount.get(), "validation should not snapshot each message attempt list");
    }

    @Test
    void auditTaskProjectionStateUsesPerMessageAttemptStatsWithoutAttemptSnapshots() {
        PagingAwareTaskStorage pagingStorage = new PagingAwareTaskStorage();
        ProjectionAwareTaskManager pagingTaskManager = new ProjectionAwareTaskManager(scheduler, pagingStorage, pagingStorage, new InMemoryTaskWorkRuntime());
        Task task = pagingTaskManager.createTask(buildRequest("audit-paged", List.of("a", "b", "c")));
        pagingTaskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        pagingTaskManager.updateTask(task);

        List<TaskMsg> messages = pagingTaskManager.getTaskMessages(task.getTid());
        assignMessage(pagingTaskManager, task, messages.get(1));

        pagingStorage.resetTraversalCounters();

        TaskStateValidationResult result = pagingTaskManager.auditTaskProjectionState(task.getTid());

        assertTrue(result.isValid());
        assertEquals(TaskStateValidationResult.Scope.PROJECTION_AUDIT, result.getScope());
        assertTrue(pagingStorage.fullSnapshotReadCount.get() > 0, "projection audit is allowed to read task message compatibility snapshots");
        assertTrue(pagingStorage.attemptStatsReadCount.get() > 0, "projection audit should read attempt stats per message");
        assertEquals(0, pagingStorage.attemptSnapshotReadCount.get(), "projection audit should not snapshot each message attempt list");
    }

    @Test
    void validateTaskStateRejectsTerminalTaskWithoutTerminalReason() {
        Task task = taskManager.createTask(buildRequest("task-validate-missing-terminal-reason", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message);
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), true, "done"));

        task.setStatus(TaskStatus.TERMINAL);
        task.setTerminalReason(null);
        taskManager.updateTask(task);

        TaskStateValidationResult result = taskManager.validateTaskState(task.getTid());

        assertFalse(result.isValid());
        assertFalse(result.isNeedsResolution());
        assertEquals(TaskStatus.TERMINAL, result.getStatus());
        assertNull(result.getTerminalReason());
        assertTrue(result.getViolations().contains(TaskStateValidationResult.ViolationCode.TERMINAL_REASON_MISSING));
    }

    @Test
    void validateTaskStateRejectsMismatchedTerminalReason() {
        Task task = taskManager.createTask(buildRequest("task-validate-reason-mismatch"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        messages.forEach(msg -> assignMessage(task, msg));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).getMessageId(), true, "done"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).getMessageId(), false, "boom"));

        task.setStatus(TaskStatus.TERMINAL);
        task.setTerminalReason(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED);
        taskManager.updateTask(task);

        TaskStateValidationResult result = taskManager.validateTaskState(task.getTid());

        assertFalse(result.isValid());
        assertFalse(result.isNeedsResolution());
        assertEquals(TaskStatus.TERMINAL, result.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, result.getTerminalReason());
        assertTrue(result.getViolations().contains(
                TaskStateValidationResult.ViolationCode.TERMINAL_REASON_MISMATCH_ALL_SUCCEEDED));
    }

    @Test
    void customTerminalPolicyCanKeepTaskRunningEvenWhenMessagesAreFinal() {
        InMemoryTaskStorage policyStorage = new InMemoryTaskStorage();
        ProjectionAwareTaskManager policyAwareManager = new ProjectionAwareTaskManager(
                scheduler,
                policyStorage,
                policyStorage,
                (task, stats) -> TaskTerminalPolicyDecision.keepRunning(),
                new InMemoryTaskWorkRuntime()
        );
        Task task = policyAwareManager.createTask(buildRequest("task-policy-keep-running", List.of("alpha")));
        policyAwareManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        policyAwareManager.updateTask(task);

        TaskMsg message = policyAwareManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(policyAwareManager, task, message);
        assertTrue(policyAwareManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), true, "done"));

        task.setStatus(TaskStatus.RUNNING);
        task.setTerminalReason(null);
        policyAwareManager.updateTask(task);

        TaskStateResolutionResult result = policyAwareManager.resolveTaskState(task.getTid());

        assertEquals(TaskStateResolutionResult.Outcome.NOT_FINALIZED, result.getOutcome());
        assertEquals(TaskStatus.RUNNING, policyAwareManager.getTask(task.getTid()).getStatus());
        assertNull(policyAwareManager.getTask(task.getTid()).getTerminalReason());
    }

    @Test
    void customTerminalPolicyCanForceTerminalBeforeAllMessagesAreFinal() {
        TaskTerminalPolicy runtimeLimitPolicy = (task, stats) ->
                TaskTerminalPolicyDecision.finalizeToTerminal(TaskTerminalReason.MAX_RUNTIME_REACHED);
        InMemoryTaskStorage policyStorage = new InMemoryTaskStorage();
        ProjectionAwareTaskManager policyAwareManager = new ProjectionAwareTaskManager(
                scheduler,
                policyStorage,
                policyStorage,
                runtimeLimitPolicy,
                new InMemoryTaskWorkRuntime()
        );
        Task task = policyAwareManager.createTask(buildRequest("task-policy-force-terminal"));
        policyAwareManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        policyAwareManager.updateTask(task);

        TaskStateResolutionResult result = policyAwareManager.resolveTaskState(task.getTid());

        assertEquals(TaskStateResolutionResult.Outcome.FINALIZED_TO_TERMINAL, result.getOutcome());
        assertEquals(TaskTerminalReason.MAX_RUNTIME_REACHED, result.getTerminalReason());
        assertEquals(TaskStatus.TERMINAL, policyAwareManager.getTask(task.getTid()).getStatus());
        assertEquals(TaskTerminalReason.MAX_RUNTIME_REACHED, policyAwareManager.getTask(task.getTid()).getTerminalReason());
    }

    // ---- Bug1: READY/RUNNING -> BLOCKED (blockTask) ----

    // ---- Open intake terminal validation ----

    @Test
    void validateTaskStateAllowsRuntimeLimitClosureForOpenIntakeTask() {
        TaskTerminalPolicy runtimeLimitPolicy = (task, stats) ->
                TaskTerminalPolicyDecision.finalizeToTerminal(TaskTerminalReason.MAX_RUNTIME_REACHED);
        InMemoryTaskStorage policyStorage = new InMemoryTaskStorage();
        ProjectionAwareTaskManager policyAwareManager = new ProjectionAwareTaskManager(
                scheduler,
                policyStorage,
                policyStorage,
                runtimeLimitPolicy,
                new InMemoryTaskWorkRuntime()
        );

        TaskCreateRequestDto request = buildRequest("task-open-intake-runtime-limit", List.of("alpha"));
        request.setOpenEnded(true);
        Task task = policyAwareManager.createTask(request);
        policyAwareManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        policyAwareManager.updateTask(task);

        TaskStateResolutionResult resolutionResult = policyAwareManager.resolveTaskState(task.getTid());
        TaskStateValidationResult validationResult = policyAwareManager.validateTaskState(task.getTid());

        assertEquals(TaskStateResolutionResult.Outcome.FINALIZED_TO_TERMINAL, resolutionResult.getOutcome());
        assertEquals(TaskTerminalReason.MAX_RUNTIME_REACHED, resolutionResult.getTerminalReason());
        assertTrue(validationResult.isValid());
        assertFalse(validationResult.isNeedsResolution());
        assertEquals(TaskStatus.TERMINAL, validationResult.getStatus());
        assertEquals(TaskTerminalReason.MAX_RUNTIME_REACHED, validationResult.getTerminalReason());
        assertFalse(validationResult.getViolations().contains(
                TaskStateValidationResult.ViolationCode.OPEN_INTAKE_FINALIZED_NON_MANUALLY));
    }

    @Test
    void validateTaskStateDoesNotFlagOpenIntakeViolationForPolicyDrivenTerminalReasons() {
        List<TaskTerminalReason> policyDrivenReasons = List.of(
                TaskTerminalReason.MAX_RUNTIME_REACHED,
                TaskTerminalReason.SUCCESS_RATE_REACHED,
                TaskTerminalReason.RETRY_BUDGET_EXHAUSTED
        );

        for (TaskTerminalReason terminalReason : policyDrivenReasons) {
            TaskCreateRequestDto request = buildRequest("task-open-intake-" + terminalReason.name(), List.of("alpha"));
            request.setOpenEnded(true);
            Task task = taskManager.createTask(request);
            task.setStatus(TaskStatus.TERMINAL);
            task.setTerminalReason(terminalReason);
            taskManager.updateTask(task);

            TaskStateValidationResult result = taskManager.validateTaskState(task.getTid());

            assertTrue(result.isValid(), terminalReason.name());
            assertFalse(result.isNeedsResolution(), terminalReason.name());
            assertEquals(TaskStatus.TERMINAL, result.getStatus(), terminalReason.name());
            assertEquals(terminalReason, result.getTerminalReason(), terminalReason.name());
            assertFalse(result.getViolations().contains(
                    TaskStateValidationResult.ViolationCode.OPEN_INTAKE_FINALIZED_NON_MANUALLY), terminalReason.name());
        }
    }

    @Test
    void blockReadyTaskTransitionsToBlocked() {
        Task task = taskManager.createTask(buildRequest("block-ready"));
        taskManager.approveTask(task.getTid()); // NEW -> READY

        assertTrue(taskManager.blockTask(task.getTid()));
        assertEquals(TaskStatus.BLOCKED, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(TaskHoldReason.MANUAL_BLOCKED, taskManager.getTask(task.getTid()).getHoldReason());
    }

    @Test
    void blockRunningTaskTransitionsToBlocked() {
        Task task = taskManager.createTask(buildRequest("block-running"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        assertTrue(taskManager.blockTask(task.getTid()));
        assertEquals(TaskStatus.BLOCKED, taskManager.getTask(task.getTid()).getStatus());
    }

    @Test
    void blockedTaskViaBlockTaskCanBeApprovedBackToReady() {
        Task task = taskManager.createTask(buildRequest("block-then-approve"));
        taskManager.approveTask(task.getTid()); // READY
        taskManager.blockTask(task.getTid());   // BLOCKED

        assertTrue(taskManager.approveTask(task.getTid()));
        assertEquals(TaskStatus.READY, taskManager.getTask(task.getTid()).getStatus());
    }

    @Test
    void blockTaskRejectedForNewAndTerminalTasks() {
        Task newTask = taskManager.createTask(buildRequest("block-new"));
        assertFalse(taskManager.blockTask(newTask.getTid()), "NEW task cannot be blocked via blockTask");

        taskManager.approveTask(newTask.getTid());
        taskManager.cancelTask(newTask.getTid()); // -> TERMINAL
        assertFalse(taskManager.blockTask(newTask.getTid()), "TERMINAL task cannot be blocked");
    }

    // ---- Bug2: TaskMsg.EXPIRED -> expireTaskMessage ----

    @Test
    void expireAssignedMessageTransitionsToExpiredAndTaskAutoCompletes() {
        Task task = taskManager.createTask(buildRequest("expire-msg", List.of("alpha"), 0));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.expireTaskMessage(task.getTid(), message.getMessageId()));

        TaskMsg updated = taskManager.getTaskMessageProjection(task.getTid(), message.getMessageId());
        assertEquals(TaskMsgStatus.EXPIRED, updated.getStatus());
        assertEquals(TaskMsgFinalReason.LEASE_EXPIRED, updated.getFinalReason());

        // All messages are final, so the task should auto-terminate
        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_FAILED, updatedTask.getTerminalReason());
    }

    @Test
    void expireRunningMessageTransitionsToExpired() {
        Task task = taskManager.createTask(buildRequest("expire-running", List.of("alpha"), 0));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignRunningMessage(task, message);

        assertTrue(taskManager.expireTaskMessage(task.getTid(), message.getMessageId()));
        assertEquals(TaskMsgStatus.EXPIRED,
                taskManager.getTaskMessageProjection(task.getTid(), message.getMessageId()).getStatus());
    }

    @Test
    void expireWithRuntimeLeaseRepairsInitProjectionAndExpires() {
        Task task = taskManager.createTask(buildRequest("expire-runtime-lease-repair", List.of("alpha"), 0));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message, "worker-expire-repair", "worker-context-expire-repair", "batch-expire-repair");
        message.setStatus(TaskMsgStatus.INIT);
        message.clearLatestAttemptProjection();
        taskManager.updateTaskMessageProjection(task.getTid(), message);

        assertTrue(taskManager.expireTaskMessage(task.getTid(), message.getMessageId()));

        TaskMsg updatedMessage = taskManager.getTaskMessageProjection(task.getTid(), message.getMessageId());
        TaskMsgAttempt latestAttempt = taskManager.getLatestTaskMessageAttemptAuditView(task.getTid(), message.getMessageId());
        assertEquals(TaskMsgStatus.EXPIRED, updatedMessage.getStatus());
        assertEquals(TaskMsgFinalReason.LEASE_EXPIRED, updatedMessage.getFinalReason());
        assertNotNull(latestAttempt);
        assertEquals(TaskMsgAttemptStatus.EXPIRED, latestAttempt.getStatus());
    }

    @Test
    void expireAssignedMessageWithRetryBudgetResetsToInitAndRequestsRedispatch() {
        Task task = taskManager.createTask(buildRequest("expire-retry", List.of("alpha"), 1));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assignMessage(task, message, "worker-expire-1", "worker-context-expire-1", "batch-expire-0");

        List<String> events = new java.util.ArrayList<>();
        taskManager.events().addTaskMessageAttemptClosedListener((currentTask, attempt) ->
                events.add("attempt-closed:" + attempt.status()));
        taskManager.events().addTaskMessageLogicallyFinalListener((currentTask, event) ->
                events.add("logical-final:" + event.status()));
        taskManager.events().addTaskDispatchListener(currentTask ->
                events.add("dispatch:" + currentTask.getStatus()));

        assertTrue(taskManager.expireTaskMessage(task.getTid(), message.getMessageId()));

        TaskMsg retriedMessage = taskManager.getTaskMessageProjection(task.getTid(), message.getMessageId());
        TaskMsgAttempt latestAttempt = taskManager.getLatestTaskMessageAttemptAuditView(task.getTid(), message.getMessageId());
        Task updatedTask = taskManager.getTask(task.getTid());

        assertEquals(TaskMsgStatus.INIT, retriedMessage.getStatus());
        assertEquals(1, retriedMessage.getRetryCount());
        assertNull(retriedMessage.getFinalReason());
        assertNull(retriedMessage.getLatestAttemptWorkerId());
        assertNull(retriedMessage.getLatestAttemptWorkerContextId());
        assertNull(retriedMessage.getLatestAttemptBatchId());
        assertNull(taskManager.getLatestActiveAttemptProjection(task.getTid(), message.getMessageId()));

        assertNotNull(latestAttempt);
        assertEquals(TaskMsgAttemptStatus.EXPIRED, latestAttempt.getStatus());
        assertEquals(TaskMsgAttemptFinalReason.LEASE_EXPIRED, latestAttempt.getFinalReason());

        assertEquals(TaskStatus.RUNNING, updatedTask.getStatus());
        assertEquals(List.of("attempt-closed:EXPIRED", "dispatch:RUNNING"), events);
    }

    @Test
    void expireInitOrBindingMessageIsRejected() {
        Task task = taskManager.createTask(buildRequest("expire-init", List.of("alpha")));
        taskManager.approveTask(task.getTid());

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        // message is in INIT state and cannot be expired (never dispatched)
        assertFalse(taskManager.expireTaskMessage(task.getTid(), message.getMessageId()));
        assertEquals(TaskMsgStatus.INIT,
                taskManager.getTaskMessageProjection(task.getTid(), message.getMessageId()).getStatus());
    }

    // ---- Bug3: cancelTask should stay task/runtime-first ----

    @Test
    void cancelTaskLeavesStoredTaskMsgProjectionUntouchedAndOverlaysTerminalView() {
        Task task = taskManager.createTask(buildRequest("cancel-cleanup", List.of("a", "b", "c")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        // msg[0]: advance to ASSIGNED with a real runtime lease
        assignMessage(task, messages.get(0));
        // msg[1], msg[2]: remain INIT
        taskManager.updateTaskMessageProjection(task.getTid(), messages.get(1));
        taskManager.updateTaskMessageProjection(task.getTid(), messages.get(2));

        assertTrue(taskManager.cancelTask(task.getTid()));

        TaskMsg storedMsg0 = taskManager.getStoredTaskMessageProjection(task.getTid(), messages.get(0).getMessageId());
        TaskMsg storedMsg1 = taskManager.getStoredTaskMessageProjection(task.getTid(), messages.get(1).getMessageId());
        TaskMsg storedMsg2 = taskManager.getStoredTaskMessageProjection(task.getTid(), messages.get(2).getMessageId());
        TaskMsg viewMsg0 = taskManager.getTaskMessageProjection(task.getTid(), messages.get(0).getMessageId());
        TaskMsg viewMsg1 = taskManager.getTaskMessageProjection(task.getTid(), messages.get(1).getMessageId());
        TaskMsg viewMsg2 = taskManager.getTaskMessageProjection(task.getTid(), messages.get(2).getMessageId());

        assertEquals(TaskMsgStatus.ASSIGNED, storedMsg0.getStatus());
        assertEquals(TaskMsgStatus.INIT, storedMsg1.getStatus());
        assertEquals(TaskMsgStatus.INIT, storedMsg2.getStatus());

        assertTrue(viewMsg0.isCompleted(), "assigned message should read as final after cancel");
        assertEquals(TaskMsgStatus.EXPIRED, viewMsg0.getStatus());
        assertEquals(TaskMsgFinalReason.MANUAL_CANCELLED, viewMsg0.getFinalReason());
        assertTrue(viewMsg1.isCompleted(), "INIT message should read as final after cancel");
        assertEquals(TaskMsgStatus.FAILED, viewMsg1.getStatus());
        assertEquals(TaskMsgFinalReason.MANUAL_CANCELLED, viewMsg1.getFinalReason());
        assertTrue(viewMsg2.isCompleted(), "INIT message should read as final after cancel");
        assertEquals(TaskMsgStatus.FAILED, viewMsg2.getStatus());
        assertEquals(TaskMsgFinalReason.MANUAL_CANCELLED, viewMsg2.getFinalReason());
    }

    @Test
    void cancelTaskOverlaysAssignedMessageWithoutRestampingStoredProjection() {
        Task task = taskManager.createTask(buildRequest("cancel-no-attempt-residue", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        assertTrue(message.markAsAssigned());
        taskManager.updateTaskMessageProjection(task.getTid(), message);

        assertTrue(taskManager.cancelTask(task.getTid()));

        TaskMsg stored = taskManager.getStoredTaskMessageProjection(task.getTid(), message.getMessageId());
        TaskMsg cancelled = taskManager.getTaskMessageProjection(task.getTid(), message.getMessageId());
        assertEquals(TaskMsgStatus.ASSIGNED, stored.getStatus());
        assertEquals(TaskMsgStatus.EXPIRED, cancelled.getStatus());
        assertEquals(TaskMsgFinalReason.MANUAL_CANCELLED, cancelled.getFinalReason());
        assertTrue(cancelled.isCompleted());
        assertNull(taskManager.getLatestActiveAttemptProjection(task.getTid(), message.getMessageId()));
    }

    @Test
    void terminalTaskMessageSnapshotOverlaysCompatibilityViewWithoutMutatingStoredRows() {
        Task task = taskManager.createTask(buildRequest("cancel-snapshot-overlay", List.of("a", "b")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        assignMessage(task, messages.get(0));

        assertTrue(taskManager.cancelTask(task.getTid()));

        TaskMessageSnapshot snapshot = taskManager.getTaskMessageSnapshot(task.getTid(), 10);
        assertEquals(2, snapshot.messages().size());
        assertEquals(List.of(TaskMsgStatus.EXPIRED, TaskMsgStatus.FAILED),
                snapshot.messages().stream().map(TaskMsg::getStatus).toList());

        TaskMsg storedAssigned = taskManager.getStoredTaskMessageProjection(task.getTid(), messages.get(0).getMessageId());
        TaskMsg storedQueued = taskManager.getStoredTaskMessageProjection(task.getTid(), messages.get(1).getMessageId());
        assertEquals(TaskMsgStatus.ASSIGNED, storedAssigned.getStatus());
        assertEquals(TaskMsgStatus.INIT, storedQueued.getStatus());
    }

    // ---- Bug4: Task.isCompleted() only returns true when status is final ----

    @Test
    void isCompletedReturnsTrueOnlyWhenTaskStatusIsFinal() {
        Task task = taskManager.createTask(buildRequest("is-completed", List.of("alpha")));

        // NEW: not final
        assertFalse(task.isCompleted());

        // Force taskSuccessNumber so that taskNonSuccessNumber == 0 while status is still READY
        taskManager.approveTask(task.getTid());
        Task ready = taskManager.getTask(task.getTid());
        ready.setTaskSuccessNumber(ready.getTaskEligibleNumber()); // all "succeeded" in the counter
        taskManager.updateTask(ready);

        // Status is READY, not TERMINAL, so it must still report not completed
        assertFalse(taskManager.getTask(task.getTid()).isCompleted(),
                "Task with all messages 'succeeded' in counter but status=READY must not be completed");

        // After cancellation the task is TERMINAL and must report completed
        taskManager.cancelTask(task.getTid());
        assertTrue(taskManager.getTask(task.getTid()).isCompleted());
    }

    @Test
    void runtimeRetryBudgetWinsOverStaleTaskMessageProjection() {
        Task task = taskManager.createTask(buildRequest("task-runtime-retry-budget-owner", List.of("alpha"), 1));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskMsg message = taskManager.getTaskMessages(task.getTid()).get(0);
        message.setMaxRetryCount(0);
        taskManager.updateTaskMessageProjection(task.getTid(), message);
        assignMessage(task, message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), false, "boom-once"));

        TaskMsg retriedMessage = taskManager.getTaskMessageProjection(task.getTid(), message.getMessageId());
        assertEquals(TaskMsgStatus.INIT, retriedMessage.getStatus());
        assertEquals(1, retriedMessage.getRetryCount());
        assertNull(retriedMessage.getFinalReason());
    }

    private TaskCreateRequestDto buildRequest(String taskName) {
        return buildRequest(taskName, List.of("alpha", "beta"));
    }

    private TaskCreateRequestDto buildRequest(String taskName, List<String> targets) {
        return buildRequest(taskName, targets, 3);
    }

    private TaskCreateRequestDto buildRequest(String taskName, List<String> targets, int defaultMsgMaxRetryCount) {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName(taskName);
        dto.setProject("demoApp");
        dto.setSharedConfig(java.util.Map.of("textContent", "smoke", "routingCode", "us"));
        dto.setUserId("agent");
        dto.setInputs(targets.stream()
                .map(target -> java.util.Map.<String, Object>of("target", target))
                .toList());
        dto.setBatchSize(1);
        dto.setDefaultMsgMaxRetryCount(defaultMsgMaxRetryCount);
        return dto;
    }

    private TaskMsg assignMessage(Task task, TaskMsg message) {
        return assignMessage(taskManager, task, message);
    }

    private TaskMsg assignMessage(Task task,
                                  TaskMsg message,
                                  String workerId,
                                  String workerContextId,
                                  String batchId) {
        return assignMessage(taskManager, task, message, workerId, workerContextId, batchId);
    }

    private TaskMsg assignMessage(ProjectionAwareTaskManager manager, Task task, TaskMsg message) {
        String suffix = message.getMessageId() != null ? message.getMessageId() : "msg";
        return assignMessage(manager, task, message,
                "worker-" + suffix,
                "worker-context-" + suffix,
                "batch-" + message.getRetryCount());
    }

    private TaskMsg assignMessage(ProjectionAwareTaskManager manager,
                                  Task task,
                                  TaskMsg message,
                                  String workerId,
                                  String workerContextId,
                                  String batchId) {
        if (manager.getTaskWorkRuntime().getActiveLease(task.getTid(), message.getMessageId()).isEmpty()) {
            List<ClaimedTaskWork> claimed = manager.getTaskWorkRuntime().claimReady(
                    task.getTid(),
                    List.of(new WorkerClaimTarget(workerId, workerContextId, batchId, 1)),
                    1,
                    manager.getTaskMessageLeaseSeconds()
            );
            if (!claimed.isEmpty() && !message.getMessageId().equals(claimed.get(0).messageId())) {
                // Some projection-only tests intentionally assign a later message.
                // The hot-path tests assign FIFO and get a matching runtime lease.
            }
        }
        message.applyLatestAttemptProjection(workerId, workerContextId, batchId);
        if (message.getStatus() == TaskMsgStatus.INIT) {
            assertTrue(message.markAsAssigned());
        }
        manager.updateTaskMessageProjection(task.getTid(), message);

        int attemptNo = message.getRetryCount() + 1;
        TaskMsgAttempt attempt = new TaskMsgAttempt(
                TaskMessageAttemptSupport.runtimeAttemptId(
                        message.getMessageId(),
                        attemptNo,
                        workerId,
                        workerContextId,
                        batchId
                ),
                task.getTid(),
                message.getMessageId(),
                attemptNo
        );
        attempt.setWorkerId(workerId);
        attempt.setWorkerContextId(workerContextId);
        attempt.setBatchId(batchId);
        assertTrue(attempt.markLeased(LocalDateTime.now().plusMinutes(5)));
        assertTrue(attempt.markDispatched());
        manager.addTaskMessageAttemptAuditProjection(task.getTid(), message.getMessageId(), attempt);
        return message;
    }

    private TaskMsg assignRunningMessage(Task task, TaskMsg message) {
        TaskMsg assigned = assignMessage(task, message);
        assertTrue(assigned.markAsRunning());
        taskManager.updateTaskMessageProjection(task.getTid(), assigned);
        TaskMsgAttempt activeAttempt = taskManager.getLatestActiveAttemptProjection(task.getTid(), assigned.getMessageId());
        assertNotNull(activeAttempt);
        if (activeAttempt.getStatus() != TaskMsgAttemptStatus.RUNNING) {
            assertTrue(activeAttempt.markRunning());
        }
        taskManager.updateTaskMessageAttemptAuditProjection(task.getTid(), assigned.getMessageId(), activeAttempt);
        return assigned;
    }

    private static void restoreProperty(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previousValue);
        }
    }

    private static class RecordingTaskScheduler implements TaskScheduler {
        private final List<String> pausedTaskIds = new java.util.ArrayList<>();
        private final List<String> resumedTaskIds = new java.util.ArrayList<>();
        private final List<String> cancelledTaskIds = new java.util.ArrayList<>();
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
            cancelledTaskIds.add(taskId);
            return true;
        }

        @Override
        public boolean pauseTask(String taskId) {
            pausedTaskIds.add(taskId);
            return true;
        }

        @Override
        public boolean resumeTask(String taskId) {
            resumedTaskIds.add(taskId);
            return true;
        }
    }

    private static final class PagingAwareTaskStorage extends InMemoryTaskStorage {
        private final AtomicInteger fullSnapshotReadCount = new AtomicInteger();
        private final AtomicInteger attemptSnapshotReadCount = new AtomicInteger();
        private final AtomicInteger attemptStatsReadCount = new AtomicInteger();

        @Override
        public List<TaskMsg> getTaskMessages(String taskId) {
            fullSnapshotReadCount.incrementAndGet();
            return super.getTaskMessages(taskId);
        }

        @Override
        public List<TaskMsgAttempt> getTaskMessageAttempts(String taskId, String messageId) {
            attemptSnapshotReadCount.incrementAndGet();
            return super.getTaskMessageAttempts(taskId, messageId);
        }

        @Override
        public TaskMessageAttemptStats getTaskMessageAttemptStats(String taskId, String messageId) {
            attemptStatsReadCount.incrementAndGet();
            return super.getTaskMessageAttemptStats(taskId, messageId);
        }

        private void resetTraversalCounters() {
            fullSnapshotReadCount.set(0);
            attemptSnapshotReadCount.set(0);
            attemptStatsReadCount.set(0);
        }
    }

    private static final class TrackingLatestAttemptStorage extends InMemoryTaskStorage {
        private final AtomicInteger latestAttemptReadCount = new AtomicInteger();

        @Override
        public java.util.Optional<TaskMsgAttempt> getLatestTaskMessageAttempt(String taskId, String messageId) {
            latestAttemptReadCount.incrementAndGet();
            return super.getLatestTaskMessageAttempt(taskId, messageId);
        }
    }

    private static final class ProjectionWriteFailingTaskStorage extends InMemoryTaskStorage {
        private volatile boolean failNextTaskMessageAdd;

        @Override
        public void addTaskMessage(String taskId, TaskMsg taskMsg) {
            if (failNextTaskMessageAdd) {
                failNextTaskMessageAdd = false;
                throw new IllegalStateException("simulated projection add failure");
            }
            super.addTaskMessage(taskId, taskMsg);
        }

        private void failNextTaskMessageAdd() {
            failNextTaskMessageAdd = true;
        }
    }
}






