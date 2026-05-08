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
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.model.TaskMessageSnapshot;
import com.xa.mass.engine.model.*;
import com.xa.mass.engine.policy.TaskTerminalPolicy;
import com.xa.mass.storage.api.TaskDetailStore;
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
    void createTaskStartsAsNewWithoutPersistingInlineTaskMsgPayloads() {
        Task task = createTask(buildRequest("task-create"));

        assertEquals(TaskStatus.NEW, task.getStatus());
        assertEquals(TaskSourceType.BATCH, task.getSourceType());
        assertEquals(TaskWorkloadClass.BULK, task.getWorkloadClass());
        assertEquals(TaskIngestStatus.SEALED, task.getIngestStatus());
        assertNotNull(task.getProjectRef());
        assertEquals("demoApp", task.getProjectRef().getCode());
        assertNotNull(task.getUser());
        assertEquals("agent", task.getUser().getUserId());

        List<TaskDetailStore.TaskMessageProjection> messages = taskManager.getTaskMessageRecords(task.getTid());
        assertEquals(2, messages.size());
        assertTrue(messages.get(0).input() == null || messages.get(0).input().isEmpty());
        assertTrue(messages.get(1).input() == null || messages.get(1).input().isEmpty());
        assertEquals(task.getTid(), messages.get(0).taskId());
        assertEquals(task.getTid(), messages.get(1).taskId());
        assertNotEquals(messages.get(0).messageId(), messages.get(1).messageId());
        assertEquals(2, taskManager.getTaskWorkRuntime().stats(task.getTid()).readyCount());
    }

    @Test
    void createTaskPreservesExplicitInteractiveWorkloadIndependentlyFromSourceType() {
        TaskCreateSpec dto = buildRequest("task-interactive");
        dto.setSourceType(TaskSourceType.STREAM);
        dto.setOpenEnded(true);
        dto.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);

        Task task = createTask(dto);

        assertEquals(TaskSourceType.STREAM, task.getSourceType());
        assertEquals(TaskWorkloadClass.INTERACTIVE, task.getWorkloadClass());
        assertEquals(TaskIntakeStatus.OPEN, task.getIntakeStatus());
    }

    @Test
    void taskCanMoveFromNewToReadyToPausedAndBackToReady() {
        Task task = createTask(buildRequest("task-lifecycle"));

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
        Task task = createTask(buildRequest("task-resume-detailed"));
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
        Task task = createTask(buildRequest("task-blocked"));

        assertTrue(taskManager.rejectTask(task.getTid()));
        assertEquals(TaskStatus.BLOCKED, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(TaskHoldReason.REVIEW_REJECTED, taskManager.getTask(task.getTid()).getHoldReason());

        assertTrue(taskManager.approveTask(task.getTid()));
        assertEquals(TaskStatus.READY, taskManager.getTask(task.getTid()).getStatus());
        assertNull(taskManager.getTask(task.getTid()).getHoldReason());
    }

    @Test
    void taskReadyListenersRunOnApproveAndResume() {
        Task task = createTask(buildRequest("task-ready-hook"));
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
        Task task = createTask(buildRequest("task-invalid"));

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
        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setTaskName("no-targets");
        dto.setProject("demoApp");
        dto.setSharedConfig(java.util.Map.of("textContent", "smoke", "routingCode", "us"));
        dto.setUserId("agent");
        dto.setInputs(null);
        dto.setBatchSize(0);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> createTask(dto));
        assertTrue(error.getMessage().contains("inputs"));
    }

    @Test
    void createStreamTaskAllowsEmptyInitialInputsAndCreatesShell() {
        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setTaskName("stream-shell");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setOpenEnded(true);
        dto.setSourceType(TaskSourceType.STREAM);
        dto.setInputs(List.of());

        Task task = createTask(dto);

        assertEquals(TaskStatus.NEW, task.getStatus());
        assertEquals(TaskSourceType.STREAM, task.getSourceType());
        assertEquals(TaskIngestStatus.READY, task.getIngestStatus());
        assertEquals(TaskIntakeStatus.OPEN, task.getIntakeStatus());
        assertEquals(0, task.getTaskTargetNumber());
        assertTrue(taskManager.getTaskMessageRecords(task.getTid()).isEmpty());
    }

    @Test
    void createFileTaskAllowsEmptyInitialInputsAndCreatesPendingShell() {
        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setTaskName("file-shell");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setSourceType(TaskSourceType.FILE);
        dto.setSourceRef("mock/input/demo.csv");
        dto.setInputs(List.of());

        Task task = createTask(dto);

        assertEquals(TaskStatus.NEW, task.getStatus());
        assertEquals(TaskSourceType.FILE, task.getSourceType());
        assertEquals(TaskIngestStatus.PENDING, task.getIngestStatus());
        assertEquals(TaskIntakeStatus.SEALED, task.getIntakeStatus());
        assertEquals("mock/input/demo.csv", task.getSourceRef());
        assertEquals(0, task.getTaskTargetNumber());
        assertTrue(taskManager.getTaskMessageRecords(task.getTid()).isEmpty());
    }

    @Test
    void createFileTaskRequiresSourceRef() {
        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setTaskName("file-shell-no-ref");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setSourceType(TaskSourceType.FILE);
        dto.setInputs(List.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> createTask(dto));

        assertEquals("sourceRef is required for FILE task sources", error.getMessage());
    }

    @Test
    void createFileTaskRejectsInitialInputsToKeepFileIngestChunked() {
        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setTaskName("file-shell-with-inline-input");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setSourceType(TaskSourceType.FILE);
        dto.setSourceRef("mock/input/demo.csv");
        dto.setInputs(List.of(java.util.Map.of("target", "alpha")));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> createTask(dto));

        assertEquals("FILE task sources must be created as a sourceRef shell; ingest work items in batches",
                error.getMessage());
    }

    @Test
    void payloadRefIngressEnqueuesRuntimeWorkWithoutRequiringTaskMsgInputPayload() {
        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setTaskName("payload-ref-ingress");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setOpenEnded(true);
        dto.setSourceType(TaskSourceType.STREAM);
        dto.setInputs(List.of());

        Task task = createTask(dto);
        String messageId = java.util.UUID.randomUUID().toString();
        String payloadRef = "s3://bucket/payloads/demo-1.json";

        taskManager.addTaskPayloadRef(task.getTid(), messageId, payloadRef, 5);

        TaskDetailStore.TaskMessageProjection projection =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), messageId);
        assertNotNull(projection);
        assertEquals(payloadRef, projection.payloadRef());
        assertTrue(projection.input() == null || projection.input().isEmpty());
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

        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setTaskName("payload-ref-best-effort-ingress");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setOpenEnded(true);
        dto.setSourceType(TaskSourceType.STREAM);
        dto.setInputs(List.of());

        Task task = createTask(manager, dto);
        manager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        manager.updateTask(task);

        String messageId = java.util.UUID.randomUUID().toString();
        String payloadRef = "s3://bucket/payloads/demo-best-effort.json";
        failingStorage.failNextTaskMessageAdd();

        manager.addTaskPayloadRef(task.getTid(), messageId, payloadRef, 2);

        assertNull(manager.getStoredTaskMessageRecord(task.getTid(), messageId));
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

        TaskDetailStore.TaskMessageProjection projection =
                manager.getVisibleTaskMessageProjection(task.getTid(), messageId);
        assertNotNull(projection);
        assertEquals(TaskMsgStatus.SUCCESS, projection.status());
        assertEquals(TaskMsgFinalReason.BUSINESS_SUCCESS, projection.finalReason());
        assertTrue(projection.input() == null || projection.input().isEmpty());
        assertEquals(java.util.Map.of("outcome", "success"), projection.output());
    }

    @Test
    void legacyCreateTaskCompatibilityFlowStillConvergesWhenInitialTaskMsgProjectionWriteFails() {
        ProjectionWriteFailingTaskStorage failingStorage = new ProjectionWriteFailingTaskStorage();
        ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                new RecordingTaskScheduler(),
                failingStorage,
                failingStorage,
                new InMemoryTaskWorkRuntime()
        );

        TaskCreateSpec dto = buildRequest("legacy-create-best-effort-projection", List.of("alpha"), 1);
        failingStorage.failNextTaskMessageAdd();

        Task task = createTask(manager, dto);
        assertEquals(TaskIngestStatus.SEALED, task.getIngestStatus());
        assertEquals(1, manager.getTaskWorkRuntime().stats(task.getTid()).readyCount());

        manager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        manager.updateTask(task);

        ClaimedTaskWork claimed = manager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(new WorkerClaimTarget("worker-legacy-create", "worker-context-legacy-create", "batch-legacy-create", 1)),
                1,
                manager.getTaskMessageLeaseSeconds()
        ).get(0);

        assertNull(manager.getStoredTaskMessageRecord(task.getTid(), claimed.messageId()));
        assertTrue(manager.handleTaskMessageResult(
                task.getTid(),
                claimed.messageId(),
                true,
                "done",
                null,
                java.util.Map.of("outcome", "success")
        ));

        TaskDetailStore.TaskMessageProjection projection =
                manager.getVisibleTaskMessageProjection(task.getTid(), claimed.messageId());
        assertNotNull(projection);
        assertEquals(TaskMsgStatus.SUCCESS, projection.status());
        assertEquals(TaskMsgFinalReason.BUSINESS_SUCCESS, projection.finalReason());
        assertEquals(java.util.Map.of("outcome", "success"), projection.output());
    }

    @Test
    void runtimeExpiryStillConvergesWhenInitialTaskMsgProjectionWriteFails() {
        ProjectionWriteFailingTaskStorage failingStorage = new ProjectionWriteFailingTaskStorage();
        ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                new RecordingTaskScheduler(),
                failingStorage,
                failingStorage,
                new InMemoryTaskWorkRuntime()
        );

        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setTaskName("payload-ref-best-effort-expiry");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setOpenEnded(true);
        dto.setSourceType(TaskSourceType.STREAM);
        dto.setInputs(List.of());

        Task task = createTask(manager, dto);
        manager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        manager.updateTask(task);

        String messageId = java.util.UUID.randomUUID().toString();
        String payloadRef = "s3://bucket/payloads/demo-best-effort-expiry.json";
        failingStorage.failNextTaskMessageAdd();

        manager.addTaskPayloadRef(task.getTid(), messageId, payloadRef, 0);

        assertNull(manager.getStoredTaskMessageRecord(task.getTid(), messageId));
        assertEquals(1, manager.getTaskWorkRuntime().stats(task.getTid()).readyCount());

        ClaimedTaskWork claimed = manager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(new WorkerClaimTarget("worker-expiry-best-effort", "worker-context-expiry-best-effort", "batch-expiry-best-effort", 1)),
                1,
                manager.getTaskMessageLeaseSeconds()
        ).get(0);
        assertEquals(messageId, claimed.messageId());
        assertEquals(payloadRef, claimed.payloadRef());

        assertTrue(manager.expireTaskMessage(task.getTid(), messageId));

        TaskDetailStore.TaskMessageProjection projection =
                manager.getVisibleTaskMessageProjection(task.getTid(), messageId);
        TaskDetailStore.TaskMessageAttemptProjection latestAttempt =
                manager.getLatestTaskMessageAttemptAuditProjection(task.getTid(), messageId);
        assertNotNull(projection);
        assertEquals(TaskMsgStatus.EXPIRED, projection.status());
        assertEquals(TaskMsgFinalReason.LEASE_EXPIRED, projection.finalReason());
        assertTrue(projection.input() == null || projection.input().isEmpty());
        assertEquals(payloadRef, projection.payloadRef());
        assertNotNull(latestAttempt);
        assertEquals(TaskMsgAttemptStatus.EXPIRED, latestAttempt.status());
    }

    @Test
    void runtimeLeaseOverlayExposesPayloadRefWhenTaskMsgProjectionIsMissing() {
        ProjectionWriteFailingTaskStorage failingStorage = new ProjectionWriteFailingTaskStorage();
        ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                new RecordingTaskScheduler(),
                failingStorage,
                failingStorage,
                new InMemoryTaskWorkRuntime()
        );

        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setTaskName("payload-ref-best-effort-overlay");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setOpenEnded(true);
        dto.setSourceType(TaskSourceType.STREAM);
        dto.setInputs(List.of());

        Task task = createTask(manager, dto);
        manager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        manager.updateTask(task);

        String messageId = java.util.UUID.randomUUID().toString();
        String payloadRef = "s3://bucket/payloads/demo-best-effort-overlay.json";
        failingStorage.failNextTaskMessageAdd();

        manager.addTaskPayloadRef(task.getTid(), messageId, payloadRef, 1);

        assertNull(manager.getStoredTaskMessageRecord(task.getTid(), messageId));

        ClaimedTaskWork claimed = manager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(new WorkerClaimTarget("worker-overlay-best-effort", "worker-context-overlay-best-effort", "batch-overlay-best-effort", 1)),
                1,
                manager.getTaskMessageLeaseSeconds()
        ).get(0);
        assertEquals(payloadRef, claimed.payloadRef());

        TaskDetailStore.TaskMessageProjection leasedView =
                manager.getVisibleTaskMessageProjection(task.getTid(), messageId);
        TaskMessageSnapshot snapshot = manager.getTaskMessageSnapshot(task.getTid(), 10);

        assertNotNull(leasedView);
        assertEquals(TaskMsgStatus.ASSIGNED, leasedView.status());
        assertEquals(payloadRef, leasedView.payloadRef());
        assertEquals("worker-overlay-best-effort", leasedView.latestAttemptWorkerId());
        assertEquals(1, snapshot.messages().size());
        assertEquals(messageId, snapshot.messages().get(0).getMessageId());
        assertEquals(TaskMsgStatus.ASSIGNED, snapshot.messages().get(0).getStatus());
        assertEquals(payloadRef, snapshot.messages().get(0).getPayloadRef());
    }

    @Test
    void runtimeCompatibilitySingleMessageViewRetainsRetryBudgetWhenTaskMsgProjectionIsMissing() {
        ProjectionWriteFailingTaskStorage failingStorage = new ProjectionWriteFailingTaskStorage();
        ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                new RecordingTaskScheduler(),
                failingStorage,
                failingStorage,
                new InMemoryTaskWorkRuntime()
        );

        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setTaskName("retry-budget-best-effort-overlay");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setOpenEnded(true);
        dto.setSourceType(TaskSourceType.STREAM);
        dto.setInputs(List.of());

        Task task = createTask(manager, dto);
        manager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        manager.updateTask(task);

        String messageId = java.util.UUID.randomUUID().toString();
        String payloadRef = "s3://bucket/payloads/demo-retry-budget-overlay.json";
        failingStorage.failNextTaskMessageAdd();

        manager.addTaskPayloadRef(task.getTid(), messageId, payloadRef, 7);

        TaskDetailStore.TaskMessageProjection visible =
                manager.getVisibleTaskMessageProjection(task.getTid(), messageId);

        assertNotNull(visible);
        assertEquals(TaskMsgStatus.INIT, visible.status());
        assertEquals(0, visible.retryCount());
        assertEquals(7, visible.maxRetryCount());
        assertEquals(payloadRef, visible.payloadRef());
        assertNotNull(visible.createTime());
    }

    @Test
    void createBatchTaskRejectsOversizedInlineInputs() {
        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setTaskName("oversized-inline");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setInputs(java.util.stream.IntStream.rangeClosed(0, TaskManager.MAX_INITIAL_INLINE_INPUTS)
                .mapToObj(i -> java.util.Map.<String, Object>of("target", "t-" + i))
                .toList());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> createTask(dto));

        assertTrue(error.getMessage().contains("BATCH task initial inputs exceed inline create limit"));
    }

    @Test
    void fileTaskCanIngestItemsBeforeApprovalWithoutDispatch() {
        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setTaskName("file-ingest-before-approval");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setSourceType(TaskSourceType.FILE);
        dto.setSourceRef("mock/input/demo.csv");
        dto.setInputs(List.of());

        Task task = createTask(dto);
        AtomicInteger dispatchRequests = new AtomicInteger();
        taskManager.events().addTaskDispatchListener(ignored -> dispatchRequests.incrementAndGet());

        int added = taskManager.appendTaskItems(task.getTid(), List.of(
                java.util.Map.<String, Object>of("target", "alpha"),
                java.util.Map.<String, Object>of("target", "beta")
        ));

        Task updatedTask = taskManager.getTask(task.getTid());
        List<TaskDetailStore.TaskMessageProjection> messages = taskManager.getTaskMessageRecords(task.getTid());

        assertEquals(2, added);
        assertEquals(TaskStatus.NEW, updatedTask.getStatus());
        assertEquals(TaskIngestStatus.READY, updatedTask.getIngestStatus());
        assertEquals(2, updatedTask.getTaskTargetNumber());
        assertEquals(2, messages.size());
        assertEquals(0, dispatchRequests.get());
    }

    @Test
    void appendTaskItemsRejectsOversizedIngestBatch() {
        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setTaskName("stream-shell-ingest-limit");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setOpenEnded(true);
        dto.setSourceType(TaskSourceType.STREAM);
        dto.setInputs(List.of());
        Task task = createTask(dto);

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
            TaskCreateSpec dto = new TaskCreateSpec();
            dto.setTaskName("interactive-backpressure");
            dto.setProject("demoApp");
            dto.setUserId("agent");
            dto.setOpenEnded(true);
            dto.setSourceType(TaskSourceType.STREAM);
            dto.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);
            dto.setInputs(List.of(java.util.Map.<String, Object>of("target", "alpha")));

            Task task = createTask(manager, dto);
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
            assertEquals(2, manager.getTaskMessageRecords(task.getTid()).size());
            assertEquals(2, manager.getTaskWorkRuntime().stats(task.getTid()).readyCount());
        } finally {
            restoreProperty("xa.mass.engine.interactiveMaxReadyItemsPerTask", previousInteractiveCap);
            restoreProperty("xa.mass.engine.bulkMaxReadyItemsPerTask", previousBulkCap);
        }
    }

    @Test
    void createTaskRejectsWhenProjectIsMissing() {
        TaskCreateSpec dto = buildRequest("missing-project");
        dto.setProject(null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> createTask(dto));

        assertEquals("project is required", error.getMessage());
    }

    @Test
    void createTaskRejectsWhenUserIdIsMissing() {
        TaskCreateSpec dto = buildRequest("missing-user");
        dto.setUserId("  ");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> createTask(dto));

        assertEquals("userId is required", error.getMessage());
    }

    @Test
    void createTaskPersistsRequestedBatchSize() {
        TaskCreateSpec dto = buildRequest("batch-size");
        dto.setBatchSize(3);

        Task task = createTask(dto);

        assertEquals(3, task.getBatchSize());
    }

    @Test
    void deleteTaskRejectedForReadyTask() {
        Task task = createTask(buildRequest("del-ready"));
        taskManager.approveTask(task.getTid()); // NEW -> READY

        assertFalse(taskManager.deleteTask(task.getTid()),
                "READY task must not be deletable");
        assertNotNull(taskManager.getTask(task.getTid()),
                "Task should still exist after rejected delete");
    }

    @Test
    void deleteTaskAllowedForNewTask() {
        Task task = createTask(buildRequest("del-new"));
        assertTrue(taskManager.getTaskWorkRuntime().hasReadyWork(task.getTid()));

        assertTrue(taskManager.deleteTask(task.getTid()),
                "NEW task should be deletable");
        assertNull(taskManager.getTask(task.getTid()));
        assertFalse(taskManager.getTaskWorkRuntime().hasReadyWork(task.getTid()));
    }

    @Test
    void deleteTaskAllowedForTerminalTask() {
        Task task = createTask(buildRequest("del-terminal"));
        taskManager.approveTask(task.getTid());
        taskManager.cancelTask(task.getTid()); // -> TERMINAL

        assertTrue(taskManager.deleteTask(task.getTid()),
                "TERMINAL task should be deletable");
        assertNull(taskManager.getTask(task.getTid()));
    }

    @Test
    void handleTaskMessageResultMarksSuccessAndFinishesRunningTask() {
        Task task = createTask(buildRequest("task-result-success"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskDetailStore.TaskMessageProjection> messages = taskManager.getTaskMessageRecords(task.getTid());
        messages.forEach(msg -> assignMessage(task, msg));

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).messageId(), true, "done-1"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).messageId(), true, "done-2"));

        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, updatedTask.getTerminalReason());
        assertEquals(2, updatedTask.getTaskSuccessNumber());
        TaskDetailStore.TaskMessageProjection first =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), messages.get(0).messageId());
        TaskDetailStore.TaskMessageProjection second =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), messages.get(1).messageId());
        assertEquals(TaskMsgStatus.SUCCESS, first.status());
        assertEquals(TaskMsgStatus.SUCCESS, second.status());
        assertTrue(first.input() == null || first.input().isEmpty());
        assertTrue(second.input() == null || second.input().isEmpty());
    }

    @Test
    void runtimeSuccessStillConvergesWhenFinalTaskMsgProjectionWriteFails() {
        ProjectionWriteFailingTaskStorage failingStorage = new ProjectionWriteFailingTaskStorage();
        ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                new RecordingTaskScheduler(),
                failingStorage,
                failingStorage,
                new InMemoryTaskWorkRuntime()
        );

        Task task = createTask(manager, buildRequest("task-result-success-best-effort", List.of("alpha")));
        manager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        manager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = manager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(manager, task, message, "worker-best-effort-success", "worker-context-best-effort-success", "batch-best-effort-success");

        failingStorage.failNextTaskMessageProjectionUpsert();

        assertTrue(manager.handleTaskMessageResult(task.getTid(), message.messageId(), true, "done"));

        Task updatedTask = manager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, updatedTask.getTerminalReason());
        assertEquals(1, updatedTask.getTaskSuccessNumber());
        assertEquals(1, manager.getTaskWorkRuntime().stats(task.getTid()).finalCount());
        assertEquals(0, manager.getTaskWorkRuntime().stats(task.getTid()).readyCount());
        assertEquals(0, manager.getTaskWorkRuntime().stats(task.getTid()).processingCount());

        TaskDetailStore.TaskMessageAttemptProjection latestAttempt =
                manager.getLatestTaskMessageAttemptAuditProjection(task.getTid(), message.messageId());
        assertNotNull(latestAttempt);
        assertEquals(TaskMsgAttemptStatus.SUCCEEDED, latestAttempt.status());
    }

    @Test
    void handleTaskMessageResultEmitsRunningSuccessAndTerminalTrace() {
        Task task = createTask(buildRequest("task-result-trace", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), true, "done"));
            capture.assertHasEvent("TASK_MSG_STATUS_TRANSITION", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.messageId().equals(mdc.get("messageId"))
                            && "ASSIGNED".equals(mdc.get("fromStatus"))
                            && "RUNNING".equals(mdc.get("toStatus")));
            capture.assertHasEvent("TASK_MSG_STATUS_TRANSITION", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.messageId().equals(mdc.get("messageId"))
                            && "RUNNING".equals(mdc.get("fromStatus"))
                            && "SUCCESS".equals(mdc.get("toStatus")));
            capture.assertHasEvent("TASK_TERMINAL_CLOSED", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && "ALL_MESSAGES_SUCCEEDED".equals(mdc.get("terminalReason")));
        }
    }

    @Test
    void handleTaskMessageResultEmitsTaskProgressSnapshot() {
        Task task = createTask(buildRequest("task-progress-snapshot", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), true, "done"));
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
        Task task = createTask(buildRequest("task-result-failure", List.of("alpha", "beta"), 0));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), false, "boom"));

        TaskDetailStore.TaskMessageProjection updatedMessage =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), message.messageId());
        assertEquals(TaskMsgStatus.FAILED, updatedMessage.status());
        assertEquals("boom", updatedMessage.errorMessage());
        assertEquals(0, taskManager.getTask(task.getTid()).getTaskSuccessNumber());
    }

    @Test
    void retryReusesSameTaskMessageAndFinalSuccessCountDoesNotInflate() {
        Task task = createTask(buildRequest("task-result-retry", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        String messageId = message.messageId();
        assignMessage(task, message, "worker-1", "worker-context-1", "batch-0");

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messageId, false, "boom-once"));

        List<TaskDetailStore.TaskMessageProjection> afterRetryMessages = taskManager.getTaskMessageRecords(task.getTid());
        assertEquals(1, afterRetryMessages.size());
        TaskDetailStore.TaskMessageProjection retriedMessage = afterRetryMessages.get(0);
        assertEquals(messageId, retriedMessage.messageId());
        assertEquals(TaskMsgStatus.INIT, retriedMessage.status());
        assertEquals(1, retriedMessage.retryCount());
        assertNull(retriedMessage.finalReason());
        assertNull(retriedMessage.latestAttemptWorkerId());
        assertNull(retriedMessage.latestAttemptWorkerContextId());
        assertNull(retriedMessage.latestAttemptBatchId());
        assertNull(retriedMessage.errorMessage());
        assertEquals(TaskStatus.RUNNING, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(0, taskManager.getTask(task.getTid()).getTaskSuccessNumber());

        assignMessage(task, retriedMessage, "worker-2", "worker-context-2", "batch-1");
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messageId, true, "done-after-retry"));

        TaskDetailStore.TaskMessageProjection finalMessage =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), messageId);
        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskMsgStatus.SUCCESS, finalMessage.status());
        assertEquals(TaskMsgFinalReason.BUSINESS_SUCCESS, finalMessage.finalReason());
        assertEquals(1, finalMessage.retryCount());
        assertNull(finalMessage.output());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, updatedTask.getTerminalReason());
        assertEquals(1, updatedTask.getTaskSuccessNumber());
    }

    @Test
    void runtimeRetryStillConvergesWhenRetryResetProjectionWriteFails() {
        ProjectionWriteFailingTaskStorage failingStorage = new ProjectionWriteFailingTaskStorage();
        ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                new RecordingTaskScheduler(),
                failingStorage,
                failingStorage,
                new InMemoryTaskWorkRuntime()
        );

        Task task = createTask(manager, buildRequest("task-result-retry-best-effort", List.of("alpha")));
        manager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        manager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = manager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(manager, task, message, "worker-best-effort-retry-1", "worker-context-best-effort-retry-1", "batch-best-effort-retry-1");

        failingStorage.failNextTaskMessageProjectionUpsert();

        assertTrue(manager.handleTaskMessageResult(task.getTid(), message.messageId(), false, "boom-once"));
        assertEquals(TaskStatus.RUNNING, manager.getTask(task.getTid()).getStatus());
        assertEquals(1, manager.getTaskWorkRuntime().stats(task.getTid()).readyCount());
        assertEquals(0, manager.getTaskWorkRuntime().stats(task.getTid()).finalCount());

        List<ClaimedTaskWork> retried = manager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(new WorkerClaimTarget("worker-best-effort-retry-2", "worker-context-best-effort-retry-2", "batch-best-effort-retry-2", 1)),
                1,
                manager.getTaskMessageLeaseSeconds()
        );
        assertEquals(1, retried.size());
        assertEquals(message.messageId(), retried.get(0).messageId());
        assertEquals(1, retried.get(0).retryCount());
    }

    @Test
    void retryEmitsRetryResetTrace() {
        Task task = createTask(buildRequest("task-result-retry-trace", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message, "worker-1", "worker-context-1", "batch-0");

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), false, "boom-once"));
            capture.assertHasEvent("TASK_MSG_RETRY_RESET", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.messageId().equals(mdc.get("messageId"))
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
            TaskCreateSpec dto = buildRequest("task-result-interactive-delayed-retry", List.of("alpha"));
            dto.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);
            Task task = createTask(manager, dto);
            manager.approveTask(task.getTid());
            task.setStatus(TaskStatus.RUNNING);
            manager.updateTask(task);

            TaskDetailStore.TaskMessageProjection message = manager.getTaskMessageRecords(task.getTid()).get(0);
            assignMessage(manager, task, message, "worker-1", "worker-context-1", "batch-0");

            AtomicInteger dispatchEvents = new AtomicInteger();
            CountDownLatch dispatchLatch = new CountDownLatch(1);
            manager.events().addTaskDispatchListener(ignored -> {
                dispatchEvents.incrementAndGet();
                dispatchLatch.countDown();
            });

            assertTrue(manager.handleTaskMessageResult(task.getTid(), message.messageId(), false, "boom-once"));

            TaskDetailStore.TaskMessageProjection retriedMessage =
                    manager.getVisibleTaskMessageProjection(task.getTid(), message.messageId());
            assertEquals(TaskMsgStatus.INIT, retriedMessage.status());
            assertEquals(1, retriedMessage.retryCount());
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
            TaskCreateSpec dto = buildRequest("task-result-interactive-coalesced-retry", List.of("alpha", "beta"), 1);
            dto.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);
            Task task = createTask(manager, dto);
            manager.approveTask(task.getTid());
            task.setStatus(TaskStatus.RUNNING);
            manager.updateTask(task);

            List<TaskDetailStore.TaskMessageProjection> messages = manager.getTaskMessageRecords(task.getTid());
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
                    messages.get(0).messageId(),
                    false,
                    "retry-alpha",
                    "SYNTHETIC_RETRY",
                    null
            ));
            assertTrue(manager.handleTaskMessageResult(
                    task.getTid(),
                    messages.get(1).messageId(),
                    false,
                    "retry-beta",
                    "SYNTHETIC_RETRY",
                    null
            ));

            assertTrue(dispatchLatch.await(2, TimeUnit.SECONDS));
            Thread.sleep(250);

            assertEquals(1, dispatchEvents.get());
            TaskDetailStore.TaskMessageProjection first =
                    manager.getVisibleTaskMessageProjection(task.getTid(), messages.get(0).messageId());
            TaskDetailStore.TaskMessageProjection second =
                    manager.getVisibleTaskMessageProjection(task.getTid(), messages.get(1).messageId());
            assertEquals(TaskMsgStatus.INIT, first.status());
            assertEquals(TaskMsgStatus.INIT, second.status());
            assertEquals(1, first.retryCount());
            assertEquals(1, second.retryCount());
        } finally {
            restoreProperty("xa.mass.engine.interactiveWorkRetryDelayMillis", previousInteractiveRetryDelay);
        }
    }

    @Test
    void callbackWithoutActiveLeaseIsRejectedAndTraced() {
        Task task = createTask(buildRequest("task-result-no-active-attempt", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        TaskMsg compatibilityMessage = message.toCompatibilityProjection();
        assertTrue(compatibilityMessage.markAsAssigned());
        assertTrue(taskManager.upsertTaskMessageProjectionRecord(
                task.getTid(),
                TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(compatibilityMessage)
        ));

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertFalse(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), true, "done"));
            capture.assertHasEvent("CALLBACK_REJECTED_NO_ACTIVE_LEASE", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.messageId().equals(mdc.get("messageId"))
                            && "ASSIGNED".equals(mdc.get("taskMsgStatus")));
        }
    }

    @Test
    void callbackForInitProjectionWithoutRuntimeLeaseIsRejectedAndTraced() {
        Task task = createTask(buildRequest("task-result-init-callback", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        TaskMsgAttempt attempt = new TaskMsgAttempt("attempt-init", task.getTid(), message.messageId(), 1);
        attempt.setWorkerId("worker-init");
        attempt.setWorkerContextId("worker-context-init");
        attempt.setBatchId("batch-init");
        assertTrue(attempt.markLeased(LocalDateTime.now().plusMinutes(5)));
        assertTrue(attempt.markDispatched());
        assertTrue(taskManager.upsertTaskMessageAttemptAuditProjectionRecord(
                task.getTid(),
                message.messageId(),
                TaskDetailStore.TaskMessageAttemptProjection.fromCompatibilityProjection(attempt)
        ));

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertFalse(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), true, "done"));
            capture.assertHasEvent("CALLBACK_REJECTED_NO_ACTIVE_LEASE", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.messageId().equals(mdc.get("messageId"))
                            && "INIT".equals(mdc.get("taskMsgStatus")));
        }

        TaskDetailStore.TaskMessageProjection persistedMessage =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), message.messageId());
        assertEquals(TaskMsgStatus.INIT, persistedMessage.status());
        assertEquals(TaskMsgAttemptStatus.DISPATCHED,
                taskManager.getLatestTaskMessageAttemptAuditProjection(task.getTid(), message.messageId()).status());
    }

    @Test
    void callbackWithRuntimeLeaseRepairsInitProjectionAndSucceeds() {
        Task task = createTask(buildRequest("task-result-runtime-lease-repair", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message, "worker-repair", "worker-context-repair", "batch-repair");
        TaskMsg compatibilityMessage = message.toCompatibilityProjection();
        compatibilityMessage.setStatus(TaskMsgStatus.INIT);
        compatibilityMessage.clearLatestAttemptProjection();
        assertTrue(taskManager.upsertTaskMessageProjectionRecord(
                task.getTid(),
                TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(compatibilityMessage)
        ));

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), true, "done"));

        TaskDetailStore.TaskMessageProjection updatedMessage =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), message.messageId());
        TaskDetailStore.TaskMessageAttemptProjection latestAttempt =
                taskManager.getLatestTaskMessageAttemptAuditProjection(task.getTid(), message.messageId());
        assertEquals(TaskMsgStatus.SUCCESS, updatedMessage.status());
        assertEquals(TaskMsgFinalReason.BUSINESS_SUCCESS, updatedMessage.finalReason());
        assertEquals("worker-repair", updatedMessage.latestAttemptWorkerId());
        assertEquals("worker-context-repair", updatedMessage.latestAttemptWorkerContextId());
        assertEquals("batch-repair", updatedMessage.latestAttemptBatchId());
        assertNotNull(latestAttempt);
        assertEquals(TaskMsgAttemptStatus.SUCCEEDED, latestAttempt.status());
    }

    @Test
    void callbackWithRuntimeLeaseRecoversMissingAttemptProjection() {
        Task task = createTask(buildRequest("task-result-runtime-attempt-recovery", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        List<ClaimedTaskWork> claimed = taskManager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(new WorkerClaimTarget("worker-recover", "worker-context-recover", "batch-recover", 1)),
                1,
                taskManager.getTaskMessageLeaseSeconds()
        );
        assertEquals(1, claimed.size());
        TaskMsg compatibilityMessage = message.toCompatibilityProjection();
        compatibilityMessage.applyLatestAttemptProjection("worker-recover", "worker-context-recover", "batch-recover");
        assertTrue(compatibilityMessage.markAsAssigned());
        assertTrue(taskManager.upsertTaskMessageProjectionRecord(
                task.getTid(),
                TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(compatibilityMessage)
        ));

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), true, "done"));

        TaskDetailStore.TaskMessageAttemptProjection recoveredAttempt =
                taskManager.getLatestTaskMessageAttemptAuditProjection(task.getTid(), message.messageId());
        assertNotNull(recoveredAttempt);
        assertEquals(TaskMsgAttemptStatus.SUCCEEDED, recoveredAttempt.status());
        assertEquals("worker-recover", recoveredAttempt.workerId());
        assertEquals("worker-context-recover", recoveredAttempt.workerContextId());
        assertEquals("batch-recover", recoveredAttempt.batchId());
    }

    @Test
    void callbackWithRuntimeLeaseDoesNotReadLatestAttemptAuditOnHotPath() {
        TrackingLatestAttemptStorage trackingStorage = new TrackingLatestAttemptStorage();
        taskStorage = trackingStorage;
        taskManager = new ProjectionAwareTaskManager(scheduler, trackingStorage, trackingStorage, new InMemoryTaskWorkRuntime());

        Task task = createTask(buildRequest("task-result-runtime-attempt-no-audit-read", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        List<ClaimedTaskWork> claimed = taskManager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(new WorkerClaimTarget("worker-no-read", "worker-context-no-read", "batch-no-read", 1)),
                1,
                taskManager.getTaskMessageLeaseSeconds()
        );
        assertEquals(1, claimed.size());

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), true, "done"));

        assertEquals(0, trackingStorage.latestAttemptReadCount.get(),
                "result hot path should derive attempt correlation from runtime lease without reading latest attempt audit rows");
        TaskDetailStore.TaskMessageAttemptProjection latestAttempt =
                taskManager.getLatestTaskMessageAttemptAuditProjection(task.getTid(), message.messageId());
        assertNotNull(latestAttempt);
        assertEquals(TaskMsgAttemptStatus.SUCCEEDED, latestAttempt.status());
    }

    @Test
    void callbackWithRuntimeLeaseDoesNotReadTaskMsgProjectionOnHotPath() {
        TrackingTaskMessageProjectionStorage trackingStorage = new TrackingTaskMessageProjectionStorage();
        taskStorage = trackingStorage;
        taskManager = new ProjectionAwareTaskManager(scheduler, trackingStorage, trackingStorage, new InMemoryTaskWorkRuntime());

        Task task = createTask(buildRequest("task-result-runtime-message-projection-no-read", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        List<ClaimedTaskWork> claimed = taskManager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(new WorkerClaimTarget("worker-no-msg-read", "worker-context-no-msg-read", "batch-no-msg-read", 1)),
                1,
                taskManager.getTaskMessageLeaseSeconds()
        );
        assertEquals(1, claimed.size());
        trackingStorage.taskMessageProjectionReadCount.set(0);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), true, "done"));

        assertEquals(0, trackingStorage.taskMessageProjectionReadCount.get(),
                "result hot path should accept runtime-owned callback without reading TaskMsg projection residue");
        TaskDetailStore.TaskMessageProjection finalProjection =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), message.messageId());
        assertNotNull(finalProjection);
        assertEquals(TaskMsgStatus.SUCCESS, finalProjection.status());
    }

    @Test
    void latestActiveAttemptCompatibilityViewDoesNotReadLatestAttemptAuditOnHotPath() {
        TrackingLatestAttemptStorage trackingStorage = new TrackingLatestAttemptStorage();
        taskStorage = trackingStorage;
        taskManager = new ProjectionAwareTaskManager(scheduler, trackingStorage, trackingStorage, new InMemoryTaskWorkRuntime());

        Task task = createTask(buildRequest("task-active-attempt-view-runtime-owned", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        List<ClaimedTaskWork> claimed = taskManager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(new WorkerClaimTarget("worker-active-view", "worker-context-active-view", "batch-active-view", 1)),
                1,
                taskManager.getTaskMessageLeaseSeconds()
        );
        assertEquals(1, claimed.size());

        TaskDetailStore.TaskMessageAttemptProjection activeAttempt =
                taskManager.getLatestActiveAttemptProjectionRecord(task.getTid(), message.messageId());

        assertEquals(0, trackingStorage.latestAttemptReadCount.get(),
                "active attempt compatibility view should synthesize from runtime lease without reading latest attempt audit rows");
        assertNotNull(activeAttempt);
        assertEquals(TaskMsgAttemptStatus.DISPATCHED, activeAttempt.status());
        assertEquals("worker-active-view", activeAttempt.workerId());
        assertEquals("worker-context-active-view", activeAttempt.workerContextId());
        assertEquals("batch-active-view", activeAttempt.batchId());
    }

    @Test
    void activeAttemptCompatibilityAuditViewRecoversRuntimeAttemptWhenAttemptProjectionIsMissing() {
        taskStorage = new InMemoryTaskStorage();
        taskManager = new ProjectionAwareTaskManager(scheduler, taskStorage, taskStorage, new InMemoryTaskWorkRuntime());

        Task task = createTask(buildRequest("task-attempt-audit-runtime-recovery", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        List<ClaimedTaskWork> claimed = taskManager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(new WorkerClaimTarget("worker-audit-view", "worker-context-audit-view", "batch-audit-view", 1)),
                1,
                taskManager.getTaskMessageLeaseSeconds()
        );
        assertEquals(1, claimed.size());
        assertTrue(taskStorage.getTaskMessageAttemptProjections(task.getTid(), message.messageId()).isEmpty());

        List<TaskDetailStore.TaskMessageAttemptProjection> visibleAttempts =
                taskManager.getVisibleAttemptProjectionRecords(task.getTid(), message.messageId());

        assertEquals(1, visibleAttempts.size());
        TaskDetailStore.TaskMessageAttemptProjection activeAttempt = visibleAttempts.getFirst();
        assertEquals(TaskMessageAttemptSupport.runtimeAttemptId(
                message.messageId(),
                1,
                "worker-audit-view",
                "worker-context-audit-view",
                "batch-audit-view"
        ), activeAttempt.attemptId());
        assertEquals(TaskMsgAttemptStatus.DISPATCHED, activeAttempt.status());
        assertEquals("worker-audit-view", activeAttempt.workerId());
        assertEquals("worker-context-audit-view", activeAttempt.workerContextId());
        assertEquals("batch-audit-view", activeAttempt.batchId());
    }

    @Test
    void expiryWithRuntimeLeaseDoesNotReadTaskMsgProjectionOnHotPath() {
        TrackingTaskMessageProjectionStorage trackingStorage = new TrackingTaskMessageProjectionStorage();
        taskStorage = trackingStorage;
        taskManager = new ProjectionAwareTaskManager(scheduler, trackingStorage, trackingStorage, new InMemoryTaskWorkRuntime());

        Task task = createTask(buildRequest("task-expiry-runtime-message-projection-no-read", List.of("alpha"), 0));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        List<ClaimedTaskWork> claimed = taskManager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(new WorkerClaimTarget("worker-expiry-no-msg-read", "worker-context-expiry-no-msg-read", "batch-expiry-no-msg-read", 1)),
                1,
                taskManager.getTaskMessageLeaseSeconds()
        );
        assertEquals(1, claimed.size());
        trackingStorage.taskMessageProjectionReadCount.set(0);

        assertTrue(taskManager.expireTaskMessage(task.getTid(), message.messageId()));

        assertEquals(0, trackingStorage.taskMessageProjectionReadCount.get(),
                "expiry hot path should converge from runtime lease without reading TaskMsg projection residue");
        TaskDetailStore.TaskMessageProjection finalProjection =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), message.messageId());
        assertNotNull(finalProjection);
        assertEquals(TaskMsgStatus.EXPIRED, finalProjection.status());
    }

    @Test
    void callbackWithRuntimeLeaseOverridesFinalTaskMsgProjectionResidue() {
        Task task = createTask(buildRequest("task-result-runtime-final-projection-residue", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message, "worker-final-residue", "worker-context-final-residue", "batch-final-residue");
        TaskMsg compatibilityMessage = message.toCompatibilityProjection();
        compatibilityMessage.forceFinalize(TaskMsgStatus.FAILED, TaskMsgFinalReason.BUSINESS_FAILED, "stale-final-projection");
        compatibilityMessage.setErrorCode("STALE");
        compatibilityMessage.setOutput(java.util.Map.of("stale", true));
        assertTrue(taskManager.upsertTaskMessageProjectionRecord(
                task.getTid(),
                TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(compatibilityMessage)
        ));

        assertTrue(taskManager.handleTaskMessageResult(
                task.getTid(),
                message.messageId(),
                true,
                "done",
                null,
                java.util.Map.of("fresh", true)
        ));

        TaskDetailStore.TaskMessageProjection updatedMessage =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), message.messageId());
        TaskDetailStore.TaskMessageAttemptProjection latestAttempt =
                taskManager.getLatestTaskMessageAttemptAuditProjection(task.getTid(), message.messageId());

        assertEquals(TaskMsgStatus.SUCCESS, updatedMessage.status());
        assertEquals(TaskMsgFinalReason.BUSINESS_SUCCESS, updatedMessage.finalReason());
        assertNull(updatedMessage.errorCode());
        assertEquals(java.util.Map.of("fresh", true), updatedMessage.output());
        assertEquals("worker-final-residue", updatedMessage.latestAttemptWorkerId());
        assertNotNull(latestAttempt);
        assertEquals(TaskMsgAttemptStatus.SUCCEEDED, latestAttempt.status());
    }

    @Test
    void retryableFailurePublishesAttemptClosedBeforeDispatchRequested() {
        Task task = createTask(buildRequest("task-result-retry-order", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message, "worker-1", "worker-context-1", "batch-0");

        List<String> events = new java.util.ArrayList<>();
        taskManager.events().addTaskMessageAttemptClosedListener((currentTask, attempt) ->
                events.add("attempt-closed:" + attempt.status()));
        taskManager.events().addTaskMessageLogicallyFinalListener((currentTask, event) ->
                events.add("logical-final:" + event.status()));
        taskManager.events().addTaskDispatchListener(currentTask ->
                events.add("dispatch:" + currentTask.getStatus()));

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), false, "boom-once"));

        assertEquals(List.of("attempt-closed:REVOKED", "dispatch:RUNNING"), events);
    }

    @Test
    void terminalCompletionPublishesLogicalFinalBeforeTerminalNotification() {
        Task task = createTask(buildRequest("task-terminal-event-order", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        List<String> events = new java.util.ArrayList<>();
        taskManager.events().addTaskMessageLogicallyFinalListener((currentTask, event) -> events.add("logical-final"));
        taskManager.events().addTaskTerminalListener(currentTask -> events.add("terminal"));

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), true, "done"));

        assertEquals(List.of("logical-final", "terminal"), events);
    }

    @Test
    void retryExhaustedFailureMarksAttemptAsBusinessFailure() {
        Task task = createTask(buildRequest("task-result-retry-exhausted-attempt", List.of("alpha"), 0));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), false, "boom-final"));

        TaskDetailStore.TaskMessageAttemptProjection attempt =
                taskManager.getLatestTaskMessageAttemptAuditProjection(task.getTid(), message.messageId());
        assertNotNull(attempt);
        assertEquals(TaskMsgAttemptStatus.FAILED, attempt.status());
        assertEquals(TaskMsgAttemptFinalReason.BUSINESS_FAILURE, attempt.finalReason());
    }

    @Test
    void resolveTaskStateReportsNotFinalizedWhileMessagesRemainOpen() {
        Task task = createTask(buildRequest("task-resolution-pending"));
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
        Task task = createTask(buildRequest("task-paused-completion", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.pauseTask(task.getTid()));
        assertEquals(TaskStatus.PAUSED, taskManager.getTask(task.getTid()).getStatus());

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), true, "done-while-paused"));

        Task updatedTask = taskManager.getTask(task.getTid());
        TaskDetailStore.TaskMessageProjection updatedMessage =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), message.messageId());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, updatedTask.getTerminalReason());
        assertEquals(1, updatedTask.getTaskSuccessNumber());
        assertEquals(TaskMsgStatus.SUCCESS, updatedMessage.status());
        assertEquals(List.of(task.getTid()), scheduler.pausedTaskIds);
        assertTrue(scheduler.resumedTaskIds.isEmpty());
    }

    @Test
    void resolveTaskStateFinalizesRunningTaskWhenAllMessagesAreFinal() {
        Task task = createTask(buildRequest("task-resolution-finalized"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskDetailStore.TaskMessageProjection> messages = taskManager.getTaskMessageRecords(task.getTid());
        messages.forEach(msg -> assignMessage(task, msg));
        taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).messageId(), true, "done-1");
        taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).messageId(), true, "done-2");

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
        Task task = createTask(buildRequest("task-resolution-already-final", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
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
        Task task = createTask(buildRequest("task-paused-resume-terminal", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.pauseTask(task.getTid()));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), true, "done-while-paused"));

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
        TaskCreateSpec request = buildRequest("task-open-ended", List.of("alpha"));
        request.setOpenEnded(true);
        Task task = createTask(request);

        assertEquals(TaskIntakeStatus.OPEN, task.getIntakeStatus());

        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), true, "done"));

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
        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setTaskName("file-task-open-ingest");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setSourceType(TaskSourceType.FILE);
        dto.setSourceRef("mock/input/demo.csv");
        dto.setInputs(List.of());

        Task task = createTask(dto);
        assertEquals(TaskIngestStatus.PENDING, task.getIngestStatus());

        assertEquals(1, taskManager.appendTaskItems(task.getTid(), List.of(
                java.util.Map.<String, Object>of("target", "alpha")
        )));

        assertTrue(taskManager.approveTask(task.getTid()));
        Task runningTask = taskManager.getTask(task.getTid());
        runningTask.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(runningTask);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(runningTask, message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), true, "done"));

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
        TaskCreateSpec request = buildRequest("task-open-ended-paused-append", List.of("alpha"));
        request.setOpenEnded(true);
        Task task = createTask(request);
        taskManager.approveTask(task.getTid());
        assertTrue(taskManager.pauseTask(task.getTid()));

        AtomicInteger dispatchRequests = new AtomicInteger();
        taskManager.events().addTaskDispatchListener(ignored -> dispatchRequests.incrementAndGet());

        int added = taskManager.appendTaskItems(task.getTid(), List.of(
                java.util.Map.<String, Object>of("target", "beta"),
                java.util.Map.<String, Object>of("target", "gamma")
        ));

        Task updatedTask = taskManager.getTask(task.getTid());
        List<TaskDetailStore.TaskMessageProjection> messages = taskManager.getTaskMessageRecords(task.getTid());

        assertEquals(2, added);
        assertEquals(TaskStatus.PAUSED, updatedTask.getStatus());
        assertEquals(TaskIntakeStatus.OPEN, updatedTask.getIntakeStatus());
        assertEquals(3, messages.size());
        assertEquals(0, dispatchRequests.get());
    }

    @Test
    void lateCallbackAfterCancelDoesNotMutateTerminalTask() {
        Task task = createTask(buildRequest("task-cancel-late-callback", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.cancelTask(task.getTid()));
        assertEquals(TaskStatus.TERMINAL, taskManager.getTask(task.getTid()).getStatus());

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), true, "late-success"));

        Task updatedTask = taskManager.getTask(task.getTid());
        TaskDetailStore.TaskMessageProjection updatedMessage =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), message.messageId());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.MANUAL_CANCELLED, updatedTask.getTerminalReason());
        assertEquals(0, updatedTask.getTaskSuccessNumber());
        // terminal task reads overlay the compatibility view without rewriting
        // every queued or leased TaskMsg projection row
        assertEquals(TaskMsgStatus.EXPIRED, updatedMessage.status());
    }

    @Test
    void lateCallbackEmitsIgnoredLateTrace() {
        Task task = createTask(buildRequest("task-late-trace", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        task.setStatus(TaskStatus.TERMINAL);
        task.setTerminalReason(TaskTerminalReason.MANUAL_CANCELLED);
        taskManager.updateTask(task);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), true, "late-success"));
            capture.assertHasEvent("CALLBACK_IGNORED_LATE", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.messageId().equals(mdc.get("messageId")));
        }
    }

    @Test
    void duplicateTaskMessageResultKeepsFirstFinalStateAndDoesNotTriggerSchedulerTwice() {
        Task task = createTask(buildRequest("task-result-duplicate", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), true, "done-once"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), false, "boom-twice"));

        TaskDetailStore.TaskMessageProjection updatedMessage =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), message.messageId());
        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskMsgStatus.SUCCESS, updatedMessage.status());
        assertNull(updatedMessage.output());
        assertNull(updatedMessage.errorMessage());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, updatedTask.getTerminalReason());
        assertEquals(1, updatedTask.getTaskSuccessNumber());
    }

    @Test
    void duplicateCallbackEmitsIgnoredDuplicateTrace() {
        Task task = createTask(buildRequest("task-result-duplicate-trace", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), true, "done-once"));

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), true, "done-twice"));
            capture.assertHasEvent("CALLBACK_IGNORED_DUPLICATE", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.messageId().equals(mdc.get("messageId")));
        }
    }

    @Test
    void mixedFinalTaskMessagesProduceMixedTerminalReason() {
        Task task = createTask(buildRequest("task-result-mixed", List.of("alpha", "beta"), 0));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        List<TaskDetailStore.TaskMessageProjection> messages = taskManager.getTaskMessageRecords(task.getTid());
        messages.forEach(msg -> assignMessage(task, msg));

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).messageId(), true, "done"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).messageId(), false, "boom"));

        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.MIXED_MESSAGE_RESULTS, updatedTask.getTerminalReason());
        assertEquals(1, updatedTask.getTaskSuccessNumber());
    }

    @Test
    void allFailedTaskMessagesProduceFailedTerminalReason() {
        Task task = createTask(buildRequest("task-result-all-failed", List.of("alpha", "beta"), 0));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        List<TaskDetailStore.TaskMessageProjection> messages = taskManager.getTaskMessageRecords(task.getTid());
        messages.forEach(msg -> assignMessage(task, msg));

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).messageId(), false, "boom-1"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).messageId(), false, "boom-2"));

        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_FAILED, updatedTask.getTerminalReason());
        assertEquals(0, updatedTask.getTaskSuccessNumber());
    }

    @Test
    void validateTaskStateReportsValidTerminalSuccessTask() {
        Task task = createTask(buildRequest("task-validate-valid-terminal"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskDetailStore.TaskMessageProjection> messages = taskManager.getTaskMessageRecords(task.getTid());
        messages.forEach(msg -> assignMessage(task, msg));

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).messageId(), true, "done-1"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).messageId(), true, "done-2"));

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
        Task task = createTask(buildRequest("task-validate-blocked-hold-reason"));
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
        Task task = createTask(buildRequest("task-validate-message-final-reason", List.of("alpha")));
        TaskDetailStore.TaskMessageProjection storedMessage = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        TaskMsg message = storedMessage.toCompatibilityProjection();
        message.markAsAssigned();
        message.markAsRunning();
        assertTrue(message.markAsSuccess("done"));
        message.setFinalReason(null);
        taskManager.upsertTaskMessageProjectionRecord(
                task.getTid(),
                TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(message)
        );

        TaskStateValidationResult result = taskManager.auditTaskProjectionState(task.getTid());

        assertFalse(result.isValid());
        assertEquals(TaskStateValidationResult.Scope.PROJECTION_AUDIT, result.getScope());
        assertTrue(result.getViolations().contains(
                TaskStateValidationResult.ViolationCode.TASK_MSG_FINAL_REASON_MISSING));
    }

    @Test
    void auditTaskProjectionStateFlagsActiveAttemptWithFinalMessage() {
        Task task = createTask(buildRequest("task-validate-active-attempt-final-message", List.of("alpha")));
        TaskDetailStore.TaskMessageProjection storedMessage = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        TaskMsg message = storedMessage.toCompatibilityProjection();
        message.markAsAssigned();
        message.markAsRunning();
        assertTrue(message.markAsSuccess("done", TaskMsgFinalReason.BUSINESS_SUCCESS));
        taskManager.upsertTaskMessageProjectionRecord(
                task.getTid(),
                TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(message)
        );

        TaskMsgAttempt activeAttempt = new TaskMsgAttempt("attempt-1", task.getTid(), message.getMessageId(), 1);
        activeAttempt.setWorkerId("worker-1");
        assertTrue(activeAttempt.markLeased(java.time.LocalDateTime.now().plusMinutes(1)));
        assertTrue(taskManager.upsertTaskMessageAttemptAuditProjectionRecord(
                task.getTid(),
                message.getMessageId(),
                TaskDetailStore.TaskMessageAttemptProjection.fromCompatibilityProjection(activeAttempt)
        ));

        TaskStateValidationResult result = taskManager.auditTaskProjectionState(task.getTid());

        assertFalse(result.isValid());
        assertEquals(TaskStateValidationResult.Scope.PROJECTION_AUDIT, result.getScope());
        assertTrue(result.getViolations().contains(
                TaskStateValidationResult.ViolationCode.ACTIVE_ATTEMPT_WITH_FINAL_MESSAGE));
    }

    @Test
    void auditTaskProjectionStateFlagsMultipleActiveAttemptsForMessage() {
        Task task = createTask(buildRequest("task-validate-multiple-active-attempts", List.of("alpha")));
        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message, "worker-1", "worker-context-1", "batch-0");

        TaskMsgAttempt secondActiveAttempt = new TaskMsgAttempt("attempt-2", task.getTid(), message.messageId(), 2);
        secondActiveAttempt.setWorkerId("worker-2");
        secondActiveAttempt.setWorkerContextId("worker-context-2");
        secondActiveAttempt.setBatchId("batch-1");
        assertTrue(secondActiveAttempt.markLeased(LocalDateTime.now().plusMinutes(1)));
        assertTrue(taskManager.upsertTaskMessageAttemptAuditProjectionRecord(
                task.getTid(),
                message.messageId(),
                TaskDetailStore.TaskMessageAttemptProjection.fromCompatibilityProjection(secondActiveAttempt)
        ));

        TaskStateValidationResult result = taskManager.auditTaskProjectionState(task.getTid());

        assertFalse(result.isValid());
        assertEquals(TaskStateValidationResult.Scope.PROJECTION_AUDIT, result.getScope());
        assertTrue(result.getViolations().contains(
                TaskStateValidationResult.ViolationCode.MULTIPLE_ACTIVE_ATTEMPTS_FOR_MESSAGE));
    }

    @Test
    void validateTaskStateReportsNeedsResolutionWhenMessagesAreFinalButTaskIsStillRunning() {
        Task task = createTask(buildRequest("task-validate-needs-resolution"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskDetailStore.TaskMessageProjection> messages = taskManager.getTaskMessageRecords(task.getTid());
        messages.forEach(msg -> assignMessage(task, msg));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).messageId(), true, "done-1"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).messageId(), true, "done-2"));

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
        Task task = createTask(buildRequest("task-validate-needs-resolution-trace"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskDetailStore.TaskMessageProjection> messages = taskManager.getTaskMessageRecords(task.getTid());
        messages.forEach(msg -> assignMessage(task, msg));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).messageId(), true, "done-1"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).messageId(), true, "done-2"));

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
        Task task = createTask(pagingTaskManager, buildRequest("validate-paged", List.of("a", "b", "c")));
        pagingTaskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        pagingTaskManager.updateTask(task);

        List<TaskDetailStore.TaskMessageProjection> messages = pagingTaskManager.getTaskMessageRecords(task.getTid());
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
        Task task = createTask(pagingTaskManager, buildRequest("audit-paged", List.of("a", "b", "c")));
        pagingTaskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        pagingTaskManager.updateTask(task);

        List<TaskDetailStore.TaskMessageProjection> messages = pagingTaskManager.getTaskMessageRecords(task.getTid());
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
        Task task = createTask(buildRequest("task-validate-missing-terminal-reason", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.messageId(), true, "done"));

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
        Task task = createTask(buildRequest("task-validate-reason-mismatch"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskDetailStore.TaskMessageProjection> messages = taskManager.getTaskMessageRecords(task.getTid());
        messages.forEach(msg -> assignMessage(task, msg));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(0).messageId(), true, "done"));
        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), messages.get(1).messageId(), false, "boom"));

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
        Task task = createTask(policyAwareManager, buildRequest("task-policy-keep-running", List.of("alpha")));
        policyAwareManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        policyAwareManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = policyAwareManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(policyAwareManager, task, message);
        assertTrue(policyAwareManager.handleTaskMessageResult(task.getTid(), message.messageId(), true, "done"));

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
        Task task = createTask(policyAwareManager, buildRequest("task-policy-force-terminal"));
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

        TaskCreateSpec request = buildRequest("task-open-intake-runtime-limit", List.of("alpha"));
        request.setOpenEnded(true);
        Task task = createTask(policyAwareManager, request);
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
            TaskCreateSpec request = buildRequest("task-open-intake-" + terminalReason.name(), List.of("alpha"));
            request.setOpenEnded(true);
            Task task = createTask(request);
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
        Task task = createTask(buildRequest("block-ready"));
        taskManager.approveTask(task.getTid()); // NEW -> READY

        assertTrue(taskManager.blockTask(task.getTid()));
        assertEquals(TaskStatus.BLOCKED, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(TaskHoldReason.MANUAL_BLOCKED, taskManager.getTask(task.getTid()).getHoldReason());
    }

    @Test
    void blockRunningTaskTransitionsToBlocked() {
        Task task = createTask(buildRequest("block-running"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        assertTrue(taskManager.blockTask(task.getTid()));
        assertEquals(TaskStatus.BLOCKED, taskManager.getTask(task.getTid()).getStatus());
    }

    @Test
    void blockedTaskViaBlockTaskCanBeApprovedBackToReady() {
        Task task = createTask(buildRequest("block-then-approve"));
        taskManager.approveTask(task.getTid()); // READY
        taskManager.blockTask(task.getTid());   // BLOCKED

        assertTrue(taskManager.approveTask(task.getTid()));
        assertEquals(TaskStatus.READY, taskManager.getTask(task.getTid()).getStatus());
    }

    @Test
    void blockTaskRejectedForNewAndTerminalTasks() {
        Task newTask = createTask(buildRequest("block-new"));
        assertFalse(taskManager.blockTask(newTask.getTid()), "NEW task cannot be blocked via blockTask");

        taskManager.approveTask(newTask.getTid());
        taskManager.cancelTask(newTask.getTid()); // -> TERMINAL
        assertFalse(taskManager.blockTask(newTask.getTid()), "TERMINAL task cannot be blocked");
    }

    // ---- Bug2: TaskMsg.EXPIRED -> expireTaskMessage ----

    @Test
    void expireAssignedMessageTransitionsToExpiredAndTaskAutoCompletes() {
        Task task = createTask(buildRequest("expire-msg", List.of("alpha"), 0));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.expireTaskMessage(task.getTid(), message.messageId()));

        TaskDetailStore.TaskMessageProjection updated =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), message.messageId());
        assertEquals(TaskMsgStatus.EXPIRED, updated.status());
        assertEquals(TaskMsgFinalReason.LEASE_EXPIRED, updated.finalReason());

        // All messages are final, so the task should auto-terminate
        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_FAILED, updatedTask.getTerminalReason());
    }

    @Test
    void expireRunningMessageTransitionsToExpired() {
        Task task = createTask(buildRequest("expire-running", List.of("alpha"), 0));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignRunningMessage(task, message);

        assertTrue(taskManager.expireTaskMessage(task.getTid(), message.messageId()));
        assertEquals(TaskMsgStatus.EXPIRED,
                taskManager.getVisibleTaskMessageProjection(task.getTid(), message.messageId()).status());
    }

    @Test
    void expireWithRuntimeLeaseRepairsInitProjectionAndExpires() {
        Task task = createTask(buildRequest("expire-runtime-lease-repair", List.of("alpha"), 0));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection initialMessage = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        TaskMsg message = initialMessage.toCompatibilityProjection();
        assignMessage(task, message, "worker-expire-repair", "worker-context-expire-repair", "batch-expire-repair");
        message.setStatus(TaskMsgStatus.INIT);
        message.clearLatestAttemptProjection();
        assertTrue(taskManager.upsertTaskMessageProjectionRecord(
                task.getTid(),
                TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(message)
        ));

        assertTrue(taskManager.expireTaskMessage(task.getTid(), message.getMessageId()));

        TaskDetailStore.TaskMessageProjection updatedMessage =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), message.getMessageId());
        TaskDetailStore.TaskMessageAttemptProjection latestAttempt =
                taskManager.getLatestTaskMessageAttemptAuditProjection(task.getTid(), message.getMessageId());
        assertEquals(TaskMsgStatus.EXPIRED, updatedMessage.status());
        assertEquals(TaskMsgFinalReason.LEASE_EXPIRED, updatedMessage.finalReason());
        assertNotNull(latestAttempt);
        assertEquals(TaskMsgAttemptStatus.EXPIRED, latestAttempt.status());
    }

    @Test
    void expireAssignedMessageWithRetryBudgetResetsToInitAndRequestsRedispatch() {
        Task task = createTask(buildRequest("expire-retry", List.of("alpha"), 1));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message, "worker-expire-1", "worker-context-expire-1", "batch-expire-0");

        List<String> events = new java.util.ArrayList<>();
        taskManager.events().addTaskMessageAttemptClosedListener((currentTask, attempt) ->
                events.add("attempt-closed:" + attempt.status()));
        taskManager.events().addTaskMessageLogicallyFinalListener((currentTask, event) ->
                events.add("logical-final:" + event.status()));
        taskManager.events().addTaskDispatchListener(currentTask ->
                events.add("dispatch:" + currentTask.getStatus()));

        assertTrue(taskManager.expireTaskMessage(task.getTid(), message.messageId()));

        TaskDetailStore.TaskMessageProjection retriedMessage =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), message.messageId());
        TaskDetailStore.TaskMessageAttemptProjection latestAttempt =
                taskManager.getLatestTaskMessageAttemptAuditProjection(task.getTid(), message.messageId());
        Task updatedTask = taskManager.getTask(task.getTid());

        assertEquals(TaskMsgStatus.INIT, retriedMessage.status());
        assertEquals(1, retriedMessage.retryCount());
        assertNull(retriedMessage.finalReason());
        assertNull(retriedMessage.latestAttemptWorkerId());
        assertNull(retriedMessage.latestAttemptWorkerContextId());
        assertNull(retriedMessage.latestAttemptBatchId());
        assertNull(taskManager.getLatestActiveAttemptProjectionRecord(task.getTid(), message.messageId()));

        assertNotNull(latestAttempt);
        assertEquals(TaskMsgAttemptStatus.EXPIRED, latestAttempt.status());
        assertEquals(TaskMsgAttemptFinalReason.LEASE_EXPIRED, latestAttempt.finalReason());

        assertEquals(TaskStatus.RUNNING, updatedTask.getStatus());
        assertEquals(List.of("attempt-closed:EXPIRED", "dispatch:RUNNING"), events);
    }

    @Test
    void expireInitOrBindingMessageIsRejected() {
        Task task = createTask(buildRequest("expire-init", List.of("alpha")));
        taskManager.approveTask(task.getTid());

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        // message is in INIT state and cannot be expired (never dispatched)
        assertFalse(taskManager.expireTaskMessage(task.getTid(), message.messageId()));
        assertEquals(TaskMsgStatus.INIT,
                taskManager.getVisibleTaskMessageProjection(task.getTid(), message.messageId()).status());
    }

    // ---- Bug3: cancelTask should stay task/runtime-first ----

    @Test
    void cancelTaskLeavesStoredTaskMsgProjectionUntouchedAndOverlaysTerminalView() {
        Task task = createTask(buildRequest("cancel-cleanup", List.of("a", "b", "c")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        List<TaskDetailStore.TaskMessageProjection> messages = taskManager.getTaskMessageRecords(task.getTid());
        // msg[0]: advance to ASSIGNED with a real runtime lease
        assignMessage(task, messages.get(0));
        // msg[1], msg[2]: remain INIT
        assertTrue(taskManager.upsertTaskMessageProjectionRecord(task.getTid(), messages.get(1)));
        assertTrue(taskManager.upsertTaskMessageProjectionRecord(task.getTid(), messages.get(2)));

        assertTrue(taskManager.cancelTask(task.getTid()));

        TaskDetailStore.TaskMessageProjection storedMsg0 =
                taskManager.getStoredTaskMessageRecord(task.getTid(), messages.get(0).messageId());
        TaskDetailStore.TaskMessageProjection storedMsg1 =
                taskManager.getStoredTaskMessageRecord(task.getTid(), messages.get(1).messageId());
        TaskDetailStore.TaskMessageProjection storedMsg2 =
                taskManager.getStoredTaskMessageRecord(task.getTid(), messages.get(2).messageId());
        TaskDetailStore.TaskMessageProjection viewMsg0 =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), messages.get(0).messageId());
        TaskDetailStore.TaskMessageProjection viewMsg1 =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), messages.get(1).messageId());
        TaskDetailStore.TaskMessageProjection viewMsg2 =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), messages.get(2).messageId());

        assertEquals(TaskMsgStatus.ASSIGNED, storedMsg0.status());
        assertEquals(TaskMsgStatus.INIT, storedMsg1.status());
        assertEquals(TaskMsgStatus.INIT, storedMsg2.status());

        assertTrue(viewMsg0.status().isFinal(), "assigned message should read as final after cancel");
        assertEquals(TaskMsgStatus.EXPIRED, viewMsg0.status());
        assertEquals(TaskMsgFinalReason.MANUAL_CANCELLED, viewMsg0.finalReason());
        assertTrue(viewMsg1.status().isFinal(), "INIT message should read as final after cancel");
        assertEquals(TaskMsgStatus.FAILED, viewMsg1.status());
        assertEquals(TaskMsgFinalReason.MANUAL_CANCELLED, viewMsg1.finalReason());
        assertTrue(viewMsg2.status().isFinal(), "INIT message should read as final after cancel");
        assertEquals(TaskMsgStatus.FAILED, viewMsg2.status());
        assertEquals(TaskMsgFinalReason.MANUAL_CANCELLED, viewMsg2.finalReason());
    }

    @Test
    void cancelTaskOverlaysAssignedMessageWithoutRestampingStoredProjection() {
        Task task = createTask(buildRequest("cancel-no-attempt-residue", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection storedMessage = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        TaskMsg message = storedMessage.toCompatibilityProjection();
        assertTrue(message.markAsAssigned());
        assertTrue(taskManager.upsertTaskMessageProjectionRecord(
                task.getTid(),
                TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(message)
        ));

        assertTrue(taskManager.cancelTask(task.getTid()));

        TaskDetailStore.TaskMessageProjection stored =
                taskManager.getStoredTaskMessageRecord(task.getTid(), message.getMessageId());
        TaskDetailStore.TaskMessageProjection cancelled =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), message.getMessageId());
        assertEquals(TaskMsgStatus.ASSIGNED, stored.status());
        assertEquals(TaskMsgStatus.EXPIRED, cancelled.status());
        assertEquals(TaskMsgFinalReason.MANUAL_CANCELLED, cancelled.finalReason());
        assertTrue(cancelled.status().isFinal());
        assertNull(taskManager.getLatestActiveAttemptProjectionRecord(task.getTid(), message.getMessageId()));
    }

    @Test
    void cancelTaskDoesNotRestampTaskMsgProjectionFromRuntimeLeaseOnly() {
        Task task = createTask(buildRequest("cancel-no-runtime-lease-restamp", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection storedMessage = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        List<ClaimedTaskWork> claimed = taskManager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(new WorkerClaimTarget("worker-cancel-runtime-only", "worker-context-cancel-runtime-only", "batch-cancel-runtime-only", 1)),
                1,
                taskManager.getTaskMessageLeaseSeconds()
        );
        assertEquals(1, claimed.size());
        assertEquals(storedMessage.messageId(), claimed.get(0).messageId());

        assertTrue(taskManager.cancelTask(task.getTid()));

        TaskDetailStore.TaskMessageProjection stored =
                taskManager.getStoredTaskMessageRecord(task.getTid(), storedMessage.messageId());
        TaskDetailStore.TaskMessageProjection cancelled =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), storedMessage.messageId());

        assertEquals(TaskMsgStatus.INIT, stored.status());
        assertNull(stored.latestAttemptWorkerId());
        assertEquals(TaskMsgStatus.FAILED, cancelled.status());
        assertEquals(TaskMsgFinalReason.MANUAL_CANCELLED, cancelled.finalReason());
        assertNull(cancelled.latestAttemptWorkerId());
    }

    @Test
    void terminalTaskMessageSnapshotOverlaysCompatibilityViewWithoutMutatingStoredRows() {
        Task task = createTask(buildRequest("cancel-snapshot-overlay", List.of("a", "b")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        List<TaskDetailStore.TaskMessageProjection> messages = taskManager.getTaskMessageRecords(task.getTid());
        assignMessage(task, messages.get(0));

        assertTrue(taskManager.cancelTask(task.getTid()));

        TaskMessageSnapshot snapshot = taskManager.getTaskMessageSnapshot(task.getTid(), 10);
        assertEquals(2, snapshot.messages().size());
        assertEquals(List.of(TaskMsgStatus.EXPIRED, TaskMsgStatus.FAILED),
                snapshot.messages().stream().map(TaskMsg::getStatus).toList());

        TaskDetailStore.TaskMessageProjection storedAssigned =
                taskManager.getStoredTaskMessageRecord(task.getTid(), messages.get(0).messageId());
        TaskDetailStore.TaskMessageProjection storedQueued =
                taskManager.getStoredTaskMessageRecord(task.getTid(), messages.get(1).messageId());
        assertEquals(TaskMsgStatus.ASSIGNED, storedAssigned.status());
        assertEquals(TaskMsgStatus.INIT, storedQueued.status());
    }

    @Test
    void compatibilitySnapshotRemainsBoundedWhenRuntimeOverlayAddsMissingProjectionMessages() {
        ProjectionWriteFailingTaskStorage failingStorage = new ProjectionWriteFailingTaskStorage();
        ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                new RecordingTaskScheduler(),
                failingStorage,
                failingStorage,
                new InMemoryTaskWorkRuntime()
        );

        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setTaskName("snapshot-bounded-runtime-overlay");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setOpenEnded(true);
        dto.setSourceType(TaskSourceType.STREAM);
        dto.setInputs(List.of(java.util.Map.<String, Object>of("target", "stored")));

        Task task = createTask(manager, dto);
        manager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        manager.updateTask(task);

        String payloadRef = "s3://bucket/payloads/snapshot-bounded-runtime-overlay.json";
        failingStorage.failNextTaskMessageAdd();
        manager.addTaskPayloadRef(task.getTid(), java.util.UUID.randomUUID().toString(), payloadRef, 1);

        List<ClaimedTaskWork> claimed = manager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(new WorkerClaimTarget("worker-snapshot-bound", "worker-context-snapshot-bound", "batch-snapshot-bound", 2)),
                2,
                manager.getTaskMessageLeaseSeconds()
        );
        assertEquals(2, claimed.size());

        TaskMessageSnapshot limitOneSnapshot = manager.getTaskMessageSnapshot(task.getTid(), 1);
        TaskMessageSnapshot zeroSnapshot = manager.getTaskMessageSnapshot(task.getTid(), 0);

        assertEquals(1, limitOneSnapshot.returned());
        assertTrue(limitOneSnapshot.truncated());
        assertEquals(0, zeroSnapshot.returned());
        assertTrue(zeroSnapshot.truncated());
    }

    @Test
    void compatibilitySnapshotTruncationUsesRuntimeTotalWhenProjectionResidueIsMissing() {
        ProjectionWriteFailingTaskStorage failingStorage = new ProjectionWriteFailingTaskStorage();
        ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                new RecordingTaskScheduler(),
                failingStorage,
                failingStorage,
                new InMemoryTaskWorkRuntime()
        );

        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setTaskName("snapshot-runtime-total-truncation");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setOpenEnded(true);
        dto.setSourceType(TaskSourceType.STREAM);
        dto.setInputs(List.of(java.util.Map.<String, Object>of("target", "stored")));

        Task task = createTask(manager, dto);
        manager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        manager.updateTask(task);

        failingStorage.failNextTaskMessageAdd();
        manager.addTaskPayloadRef(
                task.getTid(),
                java.util.UUID.randomUUID().toString(),
                "s3://bucket/payloads/runtime-total-only.json",
                1
        );

        TaskMessageSnapshot limitOneSnapshot = manager.getTaskMessageSnapshot(task.getTid(), 1);
        TaskMessageSnapshot zeroSnapshot = manager.getTaskMessageSnapshot(task.getTid(), 0);

        assertEquals(1, limitOneSnapshot.returned());
        assertTrue(limitOneSnapshot.truncated(),
                "snapshot truncation should follow runtime total work count even when one compatibility row is missing");
        assertEquals(0, zeroSnapshot.returned());
        assertTrue(zeroSnapshot.truncated(),
                "zero-limit compatibility snapshot should still report truncation from runtime total work count");
    }

    // ---- Bug4: Task.isCompleted() only returns true when status is final ----

    @Test
    void isCompletedReturnsTrueOnlyWhenTaskStatusIsFinal() {
        Task task = createTask(buildRequest("is-completed", List.of("alpha")));

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
        Task task = createTask(buildRequest("task-runtime-retry-budget-owner", List.of("alpha"), 1));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection storedMessage = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        TaskMsg message = storedMessage.toCompatibilityProjection();
        message.setMaxRetryCount(0);
        assertTrue(taskManager.upsertTaskMessageProjectionRecord(
                task.getTid(),
                TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(message)
        ));
        assignMessage(task, message);

        assertTrue(taskManager.handleTaskMessageResult(task.getTid(), message.getMessageId(), false, "boom-once"));

        TaskDetailStore.TaskMessageProjection retriedMessage =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), message.getMessageId());
        assertEquals(TaskMsgStatus.INIT, retriedMessage.status());
        assertEquals(1, retriedMessage.retryCount());
        assertNull(retriedMessage.finalReason());
    }

    private Task createTask(TaskCreateSpec request) {
        return createTask(taskManager, request);
    }

    private Task createTask(ProjectionAwareTaskManager manager, TaskCreateSpec request) {
        if (request == null) {
            throw new IllegalArgumentException("task request body is required");
        }
        TaskSourceType sourceType = request.getSourceType();
        if (sourceType == null) {
            sourceType = request.isOpenEnded() ? TaskSourceType.STREAM : TaskSourceType.BATCH;
            request.setSourceType(sourceType);
        }
        if (request.getWorkloadClass() == null) {
            request.setWorkloadClass(TaskWorkloadClass.BULK);
        }
        if (sourceType == TaskSourceType.FILE && request.getInputs() != null && !request.getInputs().isEmpty()) {
            throw new IllegalArgumentException("FILE task sources must be created as a sourceRef shell; ingest work items in batches");
        }
        if (sourceType == TaskSourceType.FILE && (request.getSourceRef() == null || request.getSourceRef().isBlank())) {
            throw new IllegalArgumentException("sourceRef is required for FILE task sources");
        }
        if (sourceType != TaskSourceType.FILE && !request.isOpenEnded()
                && (request.getInputs() == null || request.getInputs().isEmpty())) {
            throw new IllegalArgumentException("inputs must be a non-empty list");
        }
        if (sourceType == TaskSourceType.BATCH
                && request.getInputs() != null
                && request.getInputs().size() > TaskManager.MAX_INITIAL_INLINE_INPUTS) {
            throw new IllegalArgumentException("BATCH task initial inputs exceed inline create limit: "
                    + request.getInputs().size() + " > " + TaskManager.MAX_INITIAL_INLINE_INPUTS);
        }
        if (sourceType == TaskSourceType.STREAM
                && request.getInputs() != null
                && request.getInputs().size() > TaskManager.MAX_INGEST_BATCH_ITEMS) {
            throw new IllegalArgumentException("STREAM task initial inputs exceed ingest batch limit: "
                    + request.getInputs().size() + " > " + TaskManager.MAX_INGEST_BATCH_ITEMS);
        }

        Task task = manager.createTaskShell(request.toShellRequest());
        if (request.getInputs() != null && !request.getInputs().isEmpty()) {
            manager.appendTaskItems(task.getTid(), request.getInputs(), request.getDefaultMsgMaxRetryCount());
        }
        if (sourceType != TaskSourceType.FILE && !request.isOpenEnded()) {
            assertTrue(manager.sealTask(task.getTid()));
        }
        return manager.getTask(task.getTid());
    }

    private TaskCreateSpec buildRequest(String taskName) {
        return buildRequest(taskName, List.of("alpha", "beta"));
    }

    private TaskCreateSpec buildRequest(String taskName, List<String> targets) {
        return buildRequest(taskName, targets, 3);
    }

    private TaskCreateSpec buildRequest(String taskName, List<String> targets, int defaultMsgMaxRetryCount) {
        TaskCreateSpec dto = new TaskCreateSpec();
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

    private static final class TaskCreateSpec extends TaskShellCreateRequestDto {
        private java.util.List<java.util.Map<String, Object>> inputs;
        private boolean openEnded;
        private int defaultMsgMaxRetryCount = 3;

        java.util.List<java.util.Map<String, Object>> getInputs() {
            return inputs;
        }

        void setInputs(java.util.List<java.util.Map<String, Object>> inputs) {
            this.inputs = inputs;
        }

        boolean isOpenEnded() {
            return openEnded;
        }

        void setOpenEnded(boolean openEnded) {
            this.openEnded = openEnded;
        }

        int getDefaultMsgMaxRetryCount() {
            return defaultMsgMaxRetryCount;
        }

        void setDefaultMsgMaxRetryCount(int defaultMsgMaxRetryCount) {
            this.defaultMsgMaxRetryCount = defaultMsgMaxRetryCount;
        }

        TaskShellCreateRequestDto toShellRequest() {
            TaskShellCreateRequestDto dto = new TaskShellCreateRequestDto();
            dto.setUserId(getUserId());
            dto.setProject(getProject());
            dto.setTaskName(getTaskName());
            dto.setSharedConfig(getSharedConfig());
            dto.setBatchSize(getBatchSize());
            dto.setMaxRuntimeSeconds(getMaxRuntimeSeconds());
            dto.setSourceType(getSourceType());
            dto.setWorkloadClass(getWorkloadClass());
            dto.setSourceRef(getSourceRef());
            return dto;
        }
    }

    private TaskDetailStore.TaskMessageProjection assignMessage(Task task,
                                                                TaskDetailStore.TaskMessageProjection message) {
        return assignMessage(taskManager, task, message);
    }

    private TaskDetailStore.TaskMessageProjection assignMessage(Task task,
                                                                TaskDetailStore.TaskMessageProjection message,
                                                                String workerId,
                                                                String workerContextId,
                                                                String batchId) {
        return assignMessage(taskManager, task, message, workerId, workerContextId, batchId);
    }

    private TaskDetailStore.TaskMessageProjection assignMessage(ProjectionAwareTaskManager manager,
                                                                Task task,
                                                                TaskDetailStore.TaskMessageProjection message) {
        String suffix = message.messageId() != null ? message.messageId() : "msg";
        return assignMessage(manager, task, message,
                "worker-" + suffix,
                "worker-context-" + suffix,
                "batch-" + message.retryCount());
    }

    private TaskDetailStore.TaskMessageProjection assignMessage(ProjectionAwareTaskManager manager,
                                                                Task task,
                                                                TaskDetailStore.TaskMessageProjection message,
                                                                String workerId,
                                                                String workerContextId,
                                                                String batchId) {
        if (manager.getTaskWorkRuntime().getActiveLease(task.getTid(), message.messageId()).isEmpty()) {
            List<ClaimedTaskWork> claimed = manager.getTaskWorkRuntime().claimReady(
                    task.getTid(),
                    List.of(new WorkerClaimTarget(workerId, workerContextId, batchId, 1)),
                    1,
                    manager.getTaskMessageLeaseSeconds()
            );
            if (!claimed.isEmpty() && !message.messageId().equals(claimed.get(0).messageId())) {
                // Some projection-only tests intentionally assign a later message.
                // The hot-path tests assign FIFO and get a matching runtime lease.
            }
        }
        TaskMsg compatibilityMessage = message.toCompatibilityProjection();
        compatibilityMessage.applyLatestAttemptProjection(workerId, workerContextId, batchId);
        if (compatibilityMessage.getStatus() == TaskMsgStatus.INIT) {
            assertTrue(compatibilityMessage.markAsAssigned());
        }
        TaskDetailStore.TaskMessageProjection assignedProjection =
                TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(compatibilityMessage);
        assertTrue(manager.upsertTaskMessageProjectionRecord(task.getTid(), assignedProjection));

        int attemptNo = compatibilityMessage.getRetryCount() + 1;
        TaskMsgAttempt attempt = new TaskMsgAttempt(
                TaskMessageAttemptSupport.runtimeAttemptId(
                        compatibilityMessage.getMessageId(),
                        attemptNo,
                        workerId,
                        workerContextId,
                        batchId
                ),
                task.getTid(),
                compatibilityMessage.getMessageId(),
                attemptNo
        );
        attempt.setWorkerId(workerId);
        attempt.setWorkerContextId(workerContextId);
        attempt.setBatchId(batchId);
        assertTrue(attempt.markLeased(LocalDateTime.now().plusMinutes(5)));
        assertTrue(attempt.markDispatched());
        assertTrue(manager.upsertTaskMessageAttemptAuditProjectionRecord(
                task.getTid(),
                compatibilityMessage.getMessageId(),
                TaskDetailStore.TaskMessageAttemptProjection.fromCompatibilityProjection(attempt)
        ));
        return assignedProjection;
    }

    private TaskDetailStore.TaskMessageProjection assignRunningMessage(Task task,
                                                                       TaskDetailStore.TaskMessageProjection message) {
        TaskDetailStore.TaskMessageProjection assigned = assignMessage(task, message);
        TaskMsg compatibilityAssigned = assigned.toCompatibilityProjection();
        assertTrue(compatibilityAssigned.markAsRunning());
        TaskDetailStore.TaskMessageProjection runningProjection =
                TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(compatibilityAssigned);
        assertTrue(taskManager.upsertTaskMessageProjectionRecord(task.getTid(), runningProjection));
        TaskDetailStore.TaskMessageAttemptProjection activeAttemptRecord =
                taskManager.getLatestActiveAttemptProjectionRecord(task.getTid(), assigned.messageId());
        assertNotNull(activeAttemptRecord);
        TaskMsgAttempt activeAttempt = activeAttemptRecord.toCompatibilityProjection();
        if (activeAttempt.getStatus() != TaskMsgAttemptStatus.RUNNING) {
            assertTrue(activeAttempt.markRunning());
        }
        assertTrue(taskManager.upsertTaskMessageAttemptAuditProjectionRecord(
                task.getTid(),
                assigned.messageId(),
                TaskDetailStore.TaskMessageAttemptProjection.fromCompatibilityProjection(activeAttempt)
        ));
        return runningProjection;
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
        assertTrue(manager.upsertTaskMessageProjectionRecord(
                task.getTid(),
                TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(message)
        ));

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
        assertTrue(manager.upsertTaskMessageAttemptAuditProjectionRecord(
                task.getTid(),
                message.getMessageId(),
                TaskDetailStore.TaskMessageAttemptProjection.fromCompatibilityProjection(attempt)
        ));
        return message;
    }

    private TaskMsg assignRunningMessage(Task task, TaskMsg message) {
        TaskMsg assigned = assignMessage(task, message);
        assertTrue(assigned.markAsRunning());
        assertTrue(taskManager.upsertTaskMessageProjectionRecord(
                task.getTid(),
                TaskDetailStore.TaskMessageProjection.fromCompatibilityProjection(assigned)
        ));
        TaskDetailStore.TaskMessageAttemptProjection activeAttemptRecord =
                taskManager.getLatestActiveAttemptProjectionRecord(task.getTid(), assigned.getMessageId());
        assertNotNull(activeAttemptRecord);
        TaskMsgAttempt activeAttempt = activeAttemptRecord.toCompatibilityProjection();
        if (activeAttempt.getStatus() != TaskMsgAttemptStatus.RUNNING) {
            assertTrue(activeAttempt.markRunning());
        }
        assertTrue(taskManager.upsertTaskMessageAttemptAuditProjectionRecord(
                task.getTid(),
                assigned.getMessageId(),
                TaskDetailStore.TaskMessageAttemptProjection.fromCompatibilityProjection(activeAttempt)
        ));
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
        public List<TaskDetailStore.TaskMessageProjection> getTaskMessageProjections(String taskId) {
            fullSnapshotReadCount.incrementAndGet();
            return super.getTaskMessageProjections(taskId);
        }

        @Override
        public List<TaskDetailStore.TaskMessageAttemptProjection> getTaskMessageAttemptProjections(String taskId,
                                                                                                   String messageId) {
            attemptSnapshotReadCount.incrementAndGet();
            return super.getTaskMessageAttemptProjections(taskId, messageId);
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
        public java.util.Optional<TaskDetailStore.TaskMessageAttemptProjection> getLatestTaskMessageAttemptProjection(String taskId,
                                                                                                                     String messageId) {
            latestAttemptReadCount.incrementAndGet();
            return super.getLatestTaskMessageAttemptProjection(taskId, messageId);
        }
    }

    private static final class TrackingTaskMessageProjectionStorage extends InMemoryTaskStorage {
        private final AtomicInteger taskMessageProjectionReadCount = new AtomicInteger();

        @Override
        public java.util.Optional<TaskDetailStore.TaskMessageProjection> getTaskMessageProjection(String taskId,
                                                                                                  String messageId) {
            taskMessageProjectionReadCount.incrementAndGet();
            return super.getTaskMessageProjection(taskId, messageId);
        }
    }

    private static final class ProjectionWriteFailingTaskStorage extends InMemoryTaskStorage {
        private volatile boolean failNextTaskMessageAdd;
        private volatile boolean failNextTaskMessageProjectionUpsert;

        @Override
        public boolean upsertTaskMessageProjection(String taskId, TaskDetailStore.TaskMessageProjection projection) {
            if (failNextTaskMessageAdd) {
                failNextTaskMessageAdd = false;
                throw new IllegalStateException("simulated projection add failure");
            }
            if (failNextTaskMessageProjectionUpsert) {
                failNextTaskMessageProjectionUpsert = false;
                throw new IllegalStateException("simulated projection upsert failure");
            }
            return super.upsertTaskMessageProjection(taskId, projection);
        }

        private void failNextTaskMessageAdd() {
            failNextTaskMessageAdd = true;
        }

        private void failNextTaskMessageProjectionUpsert() {
            failNextTaskMessageProjectionUpsert = true;
        }
    }
}






