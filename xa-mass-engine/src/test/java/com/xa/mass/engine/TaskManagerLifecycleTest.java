package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskHoldReason;
import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.engine.model.*;
import com.xa.mass.engine.policy.TaskTerminalPolicy;
import com.xa.mass.storage.api.TaskDetailStore;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageAttemptProjectionStatus;
import com.xa.mass.storage.api.projection.TaskMessageProjectionFinalReason;
import com.xa.mass.storage.api.projection.TaskMessageProjectionStatus;
import com.xa.mass.storage.memory.InMemoryTaskShellStore;
import com.xa.mass.engine.util.TraceEventLogCapture;
import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.TaskResultRuntimeRow;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static com.xa.mass.engine.CompatibilityProjectionAwait.awaitVisibleTaskMessageAttemptProjection;
import static com.xa.mass.engine.CompatibilityProjectionAwait.awaitVisibleTaskMessageProjection;
import static org.junit.jupiter.api.Assertions.*;

@Tag("secondary-proof")
class TaskManagerLifecycleTest {

    private InMemoryTaskShellStore taskStorage;
    private ProjectionAwareTaskManager taskManager;
    private List<ProjectionAwareTaskManager> managedTaskManagers;

    @BeforeEach
    void setUp() {
        managedTaskManagers = new ArrayList<>();
        taskStorage = new InMemoryTaskShellStore();
        taskManager = new ProjectionAwareTaskManager(taskStorage, taskStorage, new InMemoryTaskWorkRuntime());
        managedTaskManagers.add(taskManager);
    }

    @AfterEach
    void tearDown() {
        for (ProjectionAwareTaskManager manager : managedTaskManagers) {
            manager.shutdown();
        }
    }

    @Test
    void createTaskStartsAsNewWithoutPersistingInlineMessagePayloads() {
        Task task = createTask(buildRequest("task-create"));

        assertEquals(TaskStatus.NEW, task.getStatus());
        assertEquals(TaskContract.BATCH, task.getContract());
        assertEquals(TaskWorkloadClass.BULK, task.getExecutionSpec().getWorkloadClass());
        assertEquals(TaskIntakeStatus.SEALED, task.getIntakeStatus());
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
    void payloadRefIngressEnqueuesRuntimeWorkWithoutRequiringMessageInputPayload() {
        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setSourceRef("payload-ref-ingress");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setSealIntakeAfterCreate(false);
        dto.setContract(TaskContract.SESSION);
        dto.setInputs(List.of());

        Task task = createTask(dto);
        String messageId = java.util.UUID.randomUUID().toString();
        String payloadRef = "s3://bucket/payloads/demo-1.json";

        taskManager.ingestRuntimePayloadRef(task.getTid(), messageId, payloadRef, 5);

        TaskDetailStore.TaskMessageProjection projection =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), messageId);
        assertNotNull(projection);
        assertEquals(payloadRef, projection.payloadRef());
        assertTrue(projection.input() == null || projection.input().isEmpty());
        assertEquals(1, taskManager.getTaskWorkRuntime().stats(task.getTid()).readyCount());
        ClaimedTaskWork claimed = taskManager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(WorkerClaimTarget.workerLevel("worker-payload-ref", "batch-0", 1)),
                1,
                taskManager.getWorkLeaseSeconds()
        ).get(0);
        assertEquals(payloadRef, claimed.payloadRef());
        assertTrue(claimed.payload().isEmpty());
    }

    @Test
    void runtimeIngressStillConvergesWhenInitialMessageProjectionWriteFails() {
        ProjectionWriteFailingTaskShellStore failingStorage = new ProjectionWriteFailingTaskShellStore();
        ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                failingStorage,
                failingStorage,
                new InMemoryTaskWorkRuntime()
        );

        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setSourceRef("payload-ref-best-effort-ingress");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setSealIntakeAfterCreate(false);
        dto.setContract(TaskContract.SESSION);
        dto.setInputs(List.of());

        Task task = createTask(manager, dto);
        manager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        manager.updateTask(task);

        String messageId = java.util.UUID.randomUUID().toString();
        String payloadRef = "s3://bucket/payloads/demo-best-effort.json";
        failingStorage.failNextTaskMessageAdd();

        manager.ingestRuntimePayloadRef(task.getTid(), messageId, payloadRef, 2);

        assertNull(manager.getStoredTaskMessageRecord(task.getTid(), messageId));
        assertEquals(1, manager.getTaskWorkRuntime().stats(task.getTid()).readyCount());

        ClaimedTaskWork claimed = manager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(WorkerClaimTarget.workerLevel("worker-best-effort", "batch-best-effort", 1)),
                1,
                manager.getWorkLeaseSeconds()
        ).get(0);
        assertEquals(messageId, claimed.messageId());
        assertEquals(payloadRef, claimed.payloadRef());
        assertTrue(claimed.payload().isEmpty());

        assertTrue(manager.ingestTaskResult(
                task.getTid(),
                messageId,
                true,
                "done",
                null,
                java.util.Map.of("outcome", "success")
        ));

        TaskDetailStore.TaskMessageProjection projection = awaitVisibleTaskMessageProjection(
                manager,
                task.getTid(),
                messageId,
                TaskMessageProjectionStatus.SUCCESS
        );
        assertNotNull(projection);
        assertEquals(TaskMessageProjectionStatus.SUCCESS, projection.status());
        assertEquals(TaskMessageProjectionFinalReason.BUSINESS_SUCCESS, projection.finalReason());
        assertTrue(projection.input() == null || projection.input().isEmpty());
        assertEquals(java.util.Map.of("outcome", "success"), projection.output());
    }

    @Test
    void legacyCreateTaskCompatibilityFlowStillConvergesWhenInitialMessageProjectionWriteFails() {
        ProjectionWriteFailingTaskShellStore failingStorage = new ProjectionWriteFailingTaskShellStore();
        ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                failingStorage,
                failingStorage,
                new InMemoryTaskWorkRuntime()
        );

        TaskCreateSpec dto = buildRequest("legacy-create-best-effort-projection", List.of("alpha"), 1);
        failingStorage.failNextTaskMessageAdd();

        Task task = createTask(manager, dto);
        assertEquals(TaskIntakeStatus.SEALED, task.getIntakeStatus());
        assertEquals(1, manager.getTaskWorkRuntime().stats(task.getTid()).readyCount());

        manager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        manager.updateTask(task);

        ClaimedTaskWork claimed = manager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(WorkerClaimTarget.workerLevel("worker-legacy-create", "batch-legacy-create", 1)),
                1,
                manager.getWorkLeaseSeconds()
        ).get(0);

        assertNull(manager.getStoredTaskMessageRecord(task.getTid(), claimed.messageId()));
        assertTrue(manager.ingestTaskResult(
                task.getTid(),
                claimed.messageId(),
                true,
                "done",
                null,
                java.util.Map.of("outcome", "success")
        ));

        TaskDetailStore.TaskMessageProjection projection = awaitVisibleTaskMessageProjection(
                manager,
                task.getTid(),
                claimed.messageId(),
                TaskMessageProjectionStatus.SUCCESS
        );
        assertNotNull(projection);
        assertEquals(TaskMessageProjectionStatus.SUCCESS, projection.status());
        assertEquals(TaskMessageProjectionFinalReason.BUSINESS_SUCCESS, projection.finalReason());
        assertEquals(java.util.Map.of("outcome", "success"), projection.output());
    }

    @Test
    void runtimeExpiryStillConvergesWhenInitialMessageProjectionWriteFails() {
        ProjectionWriteFailingTaskShellStore failingStorage = new ProjectionWriteFailingTaskShellStore();
        ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                failingStorage,
                failingStorage,
                new InMemoryTaskWorkRuntime()
        );

        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setSourceRef("payload-ref-best-effort-expiry");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setSealIntakeAfterCreate(false);
        dto.setContract(TaskContract.SESSION);
        dto.setInputs(List.of());

        Task task = createTask(manager, dto);
        manager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        manager.updateTask(task);

        String messageId = java.util.UUID.randomUUID().toString();
        String payloadRef = "s3://bucket/payloads/demo-best-effort-expiry.json";
        failingStorage.failNextTaskMessageAdd();

        manager.ingestRuntimePayloadRef(task.getTid(), messageId, payloadRef, 0);

        assertNull(manager.getStoredTaskMessageRecord(task.getTid(), messageId));
        assertEquals(1, manager.getTaskWorkRuntime().stats(task.getTid()).readyCount());

        ClaimedTaskWork claimed = manager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(WorkerClaimTarget.workerLevel("worker-expiry-best-effort", "batch-expiry-best-effort", 1)),
                1,
                manager.getWorkLeaseSeconds()
        ).get(0);
        assertEquals(messageId, claimed.messageId());
        assertEquals(payloadRef, claimed.payloadRef());

        assertTrue(manager.expireLeasedWork(task.getTid(), messageId));

        TaskDetailStore.TaskMessageProjection projection = awaitVisibleTaskMessageProjection(
                manager,
                task.getTid(),
                messageId,
                TaskMessageProjectionStatus.EXPIRED
        );
        TaskDetailStore.TaskMessageAttemptProjection latestAttempt = awaitVisibleTaskMessageAttemptProjection(
                manager,
                task.getTid(),
                messageId,
                TaskMessageAttemptProjectionStatus.EXPIRED
        );
        assertNotNull(projection);
        assertEquals(TaskMessageProjectionStatus.EXPIRED, projection.status());
        assertEquals(TaskMessageProjectionFinalReason.LEASE_EXPIRED, projection.finalReason());
        assertTrue(projection.input() == null || projection.input().isEmpty());
        assertEquals(payloadRef, projection.payloadRef());
        assertNotNull(latestAttempt);
        assertEquals(TaskMessageAttemptProjectionStatus.EXPIRED, latestAttempt.status());
    }

    @Test
    void runtimeLeaseOverlayExposesPayloadRefWhenMessageProjectionIsMissing() {
        ProjectionWriteFailingTaskShellStore failingStorage = new ProjectionWriteFailingTaskShellStore();
        ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                failingStorage,
                failingStorage,
                new InMemoryTaskWorkRuntime()
        );

        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setSourceRef("payload-ref-best-effort-overlay");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setSealIntakeAfterCreate(false);
        dto.setContract(TaskContract.SESSION);
        dto.setInputs(List.of());

        Task task = createTask(manager, dto);
        manager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        manager.updateTask(task);

        String messageId = java.util.UUID.randomUUID().toString();
        String payloadRef = "s3://bucket/payloads/demo-best-effort-overlay.json";
        failingStorage.failNextTaskMessageAdd();

        manager.ingestRuntimePayloadRef(task.getTid(), messageId, payloadRef, 1);

        assertNull(manager.getStoredTaskMessageRecord(task.getTid(), messageId));

        ClaimedTaskWork claimed = manager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(WorkerClaimTarget.workerLevel("worker-overlay-best-effort", "batch-overlay-best-effort", 1)),
                1,
                manager.getWorkLeaseSeconds()
        ).get(0);
        assertEquals(payloadRef, claimed.payloadRef());

        TaskDetailStore.TaskMessageProjection leasedView =
                manager.getVisibleTaskMessageProjection(task.getTid(), messageId);
        ProjectionTestSupport.MessageSnapshot snapshot = manager.getTaskMessageSnapshot(task.getTid(), 10);

        assertNotNull(leasedView);
        assertEquals(TaskMessageProjectionStatus.ASSIGNED, leasedView.status());
        assertEquals(payloadRef, leasedView.payloadRef());
        assertEquals("worker-overlay-best-effort", leasedView.latestAttemptWorkerId());
        assertEquals(1, snapshot.messages().size());
        assertEquals(messageId, snapshot.messages().get(0).messageId());
        assertEquals(TaskMessageProjectionStatus.ASSIGNED, snapshot.messages().get(0).status());
        assertEquals(payloadRef, snapshot.messages().get(0).payloadRef());
    }

    @Test
    void runtimeCompatibilitySingleMessageViewRetainsRetryBudgetWhenMessageProjectionIsMissing() {
        ProjectionWriteFailingTaskShellStore failingStorage = new ProjectionWriteFailingTaskShellStore();
        ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                failingStorage,
                failingStorage,
                new InMemoryTaskWorkRuntime()
        );

        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setSourceRef("retry-budget-best-effort-overlay");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setSealIntakeAfterCreate(false);
        dto.setContract(TaskContract.SESSION);
        dto.setInputs(List.of());

        Task task = createTask(manager, dto);
        manager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        manager.updateTask(task);

        String messageId = java.util.UUID.randomUUID().toString();
        String payloadRef = "s3://bucket/payloads/demo-retry-budget-overlay.json";
        failingStorage.failNextTaskMessageAdd();

        manager.ingestRuntimePayloadRef(task.getTid(), messageId, payloadRef, 7);

        TaskDetailStore.TaskMessageProjection visible =
                manager.getVisibleTaskMessageProjection(task.getTid(), messageId);

        assertNotNull(visible);
        assertEquals(TaskMessageProjectionStatus.INIT, visible.status());
        assertEquals(0, visible.retryCount());
        assertEquals(7, visible.maxRetryCount());
        assertEquals(payloadRef, visible.payloadRef());
        assertNotNull(visible.createTime());
    }

    @Test
    void batchTaskCanIngestItemsBeforeApprovalWithoutDispatch() {
        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setSourceRef("file-ingest-before-approval");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setContract(TaskContract.BATCH);
        dto.setSourceRef("mock/input/demo.csv");
        dto.setSealIntakeAfterCreate(false);
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
        assertEquals(TaskIntakeStatus.OPEN, updatedTask.getIntakeStatus());
        assertEquals(2, updatedTask.getTaskTargetNumber());
        assertEquals(2, messages.size());
        assertEquals(0, dispatchRequests.get());
    }

    @Test
    void interactiveTaskAppendRespectsWorkloadAwareReadyBackpressureCap() {
        String previousInteractiveCap = System.getProperty("xa.mass.engine.interactiveMaxReadyItemsPerTask");
        String previousBulkCap = System.getProperty("xa.mass.engine.bulkMaxReadyItemsPerTask");
        try {
            System.setProperty("xa.mass.engine.interactiveMaxReadyItemsPerTask", "2");
            System.setProperty("xa.mass.engine.bulkMaxReadyItemsPerTask", "100");

            InMemoryTaskShellStore managerStorage = new InMemoryTaskShellStore();
            ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                    managerStorage,
                    managerStorage,
                    new InMemoryTaskWorkRuntime());
            TaskCreateSpec dto = new TaskCreateSpec();
            dto.setSourceRef("interactive-backpressure");
            dto.setProject("demoApp");
            dto.setUserId("agent");
            dto.setSealIntakeAfterCreate(false);
            dto.setContract(TaskContract.SESSION);
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
    void appendTaskItemsRejectsBeforeRuntimeAdmissionWhenEngineBacklogWouldOverflow() {
        InMemoryTaskShellStore managerStorage = new InMemoryTaskShellStore();
        ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                managerStorage,
                managerStorage,
                new InMemoryTaskWorkRuntime(2)
        );
        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setSourceRef("append-atomic-backlog");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setContract(TaskContract.BATCH);
        dto.setSealIntakeAfterCreate(false);
        dto.setInputs(List.of(java.util.Map.<String, Object>of("target", "alpha")));

        Task task = createTask(manager, dto);
        assertEquals(1, manager.getTaskWorkRuntime().stats(task.getTid()).readyCount());
        assertEquals(1, manager.getTaskMessageRecords(task.getTid()).size());
        assertEquals(1, manager.getTask(task.getTid()).getTaskTargetNumber());
        assertEquals(1, manager.getTask(task.getTid()).getTaskEligibleNumber());

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                manager.appendTaskItems(task.getTid(), List.of(
                        java.util.Map.<String, Object>of("target", "beta"),
                        java.util.Map.<String, Object>of("target", "gamma")
                )));

        assertTrue(error.getMessage().contains("engine work backlog is full"));
        assertEquals(1, manager.getTaskWorkRuntime().stats(task.getTid()).readyCount());
        assertEquals(1, manager.getTaskWorkRuntime().stats().readyItems());
        assertEquals(1, manager.getTaskMessageRecords(task.getTid()).size());
        assertEquals(1, manager.getTask(task.getTid()).getTaskTargetNumber());
        assertEquals(1, manager.getTask(task.getTid()).getTaskEligibleNumber());
    }

    @Test
    void ingestTaskResultMarksSuccessAndFinishesRunningTask() {
        Task task = createTask(buildRequest("task-result-success"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskDetailStore.TaskMessageProjection> messages = taskManager.getTaskMessageRecords(task.getTid());
        messages.forEach(msg -> assignMessage(task, msg));

        assertTrue(taskManager.ingestTaskResult(task.getTid(), messages.get(0).messageId(), true, "done-1"));
        assertTrue(taskManager.ingestTaskResult(task.getTid(), messages.get(1).messageId(), true, "done-2"));

        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, updatedTask.getTerminalReason());
        assertEquals(2, updatedTask.getTaskSuccessNumber());
        TaskDetailStore.TaskMessageProjection first = awaitVisibleTaskMessageProjection(
                taskManager,
                task.getTid(),
                messages.get(0).messageId(),
                TaskMessageProjectionStatus.SUCCESS
        );
        TaskDetailStore.TaskMessageProjection second = awaitVisibleTaskMessageProjection(
                taskManager,
                task.getTid(),
                messages.get(1).messageId(),
                TaskMessageProjectionStatus.SUCCESS
        );
        assertEquals(TaskMessageProjectionStatus.SUCCESS, first.status());
        assertEquals(TaskMessageProjectionStatus.SUCCESS, second.status());
        assertTrue(first.input() == null || first.input().isEmpty());
        assertTrue(second.input() == null || second.input().isEmpty());
    }

    @Test
    void runtimeSuccessStillConvergesWhenFinalMessageProjectionWriteFails() {
        ProjectionWriteFailingTaskShellStore failingStorage = new ProjectionWriteFailingTaskShellStore();
        ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                failingStorage,
                failingStorage,
                new InMemoryTaskWorkRuntime()
        );

        Task task = createTask(manager, buildRequest("task-result-success-best-effort", List.of("alpha")));
        manager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        manager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = manager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(manager, task, message, "worker-best-effort-success", "batch-best-effort-success");

        failingStorage.failNextTaskMessageProjectionUpsert();

        assertTrue(manager.ingestTaskResult(task.getTid(), message.messageId(), true, "done"));

        Task updatedTask = manager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, updatedTask.getTerminalReason());
        assertEquals(1, updatedTask.getTaskSuccessNumber());
        assertEquals(1, manager.getTaskWorkRuntime().stats(task.getTid()).finalCount());
        assertEquals(0, manager.getTaskWorkRuntime().stats(task.getTid()).readyCount());
        assertEquals(0, manager.getTaskWorkRuntime().stats(task.getTid()).processingCount());

        TaskDetailStore.TaskMessageAttemptProjection latestAttempt = awaitVisibleTaskMessageAttemptProjection(
                manager,
                task.getTid(),
                message.messageId(),
                TaskMessageAttemptProjectionStatus.SUCCEEDED
        );
        assertNotNull(latestAttempt);
        assertEquals(TaskMessageAttemptProjectionStatus.SUCCEEDED, latestAttempt.status());
    }

    @Test
    void ingestTaskResultEmitsRunningSuccessAndTerminalTrace() {
        Task task = createTask(buildRequest("task-result-trace", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), true, "done"));
            capture.assertHasEvent("TASK_WORK_STATUS_TRANSITION", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.messageId().equals(mdc.get("messageId"))
                            && "ASSIGNED".equals(mdc.get("fromStatus"))
                            && "RUNNING".equals(mdc.get("toStatus")));
            capture.assertHasEvent("TASK_WORK_STATUS_TRANSITION", mdc ->
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
    void ingestTaskResultEmitsTaskProgressSnapshot() {
        Task task = createTask(buildRequest("task-progress-snapshot", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), true, "done"));
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
    void ingestTaskResultMarksFailureAndKeepsExecutedCountAtSuccessOnly() {
        Task task = createTask(buildRequest("task-result-failure", List.of("alpha", "beta"), 0));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), false, "boom"));

        TaskDetailStore.TaskMessageProjection updatedMessage = awaitVisibleTaskMessageProjection(
                taskManager,
                task.getTid(),
                message.messageId(),
                TaskMessageProjectionStatus.FAILED
        );
        assertNotNull(updatedMessage);
        assertEquals(TaskMessageProjectionStatus.FAILED, updatedMessage.status());
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
        assignMessage(task, message, "worker-1", "batch-0");

        assertTrue(taskManager.ingestTaskResult(task.getTid(), messageId, false, "boom-once"));

        TaskDetailStore.TaskMessageProjection retriedMessage = awaitVisibleTaskMessageProjection(
                taskManager,
                task.getTid(),
                messageId,
                TaskMessageProjectionStatus.INIT
        );
        assertNotNull(retriedMessage);
        assertEquals(messageId, retriedMessage.messageId());
        assertEquals(TaskMessageProjectionStatus.INIT, retriedMessage.status());
        assertEquals(1, retriedMessage.retryCount());
        assertNull(retriedMessage.finalReason());
        assertNull(retriedMessage.latestAttemptWorkerId());        assertNull(retriedMessage.latestAttemptBatchId());
        assertNull(retriedMessage.errorMessage());
        assertEquals(TaskStatus.RUNNING, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(0, taskManager.getTask(task.getTid()).getTaskSuccessNumber());

        assignMessage(task, retriedMessage, "worker-2", "batch-1");
        assertTrue(taskManager.ingestTaskResult(task.getTid(), messageId, true, "done-after-retry"));

        TaskDetailStore.TaskMessageProjection finalMessage = awaitVisibleTaskMessageProjection(
                taskManager,
                task.getTid(),
                messageId,
                TaskMessageProjectionStatus.SUCCESS
        );
        assertNotNull(finalMessage);
        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskMessageProjectionStatus.SUCCESS, finalMessage.status());
        assertEquals(TaskMessageProjectionFinalReason.BUSINESS_SUCCESS, finalMessage.finalReason());
        assertEquals(1, finalMessage.retryCount());
        assertNull(finalMessage.output());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, updatedTask.getTerminalReason());
        assertEquals(1, updatedTask.getTaskSuccessNumber());
    }

    @Test
    void runtimeRetryStillConvergesWhenRetryResetProjectionWriteFails() {
        ProjectionWriteFailingTaskShellStore failingStorage = new ProjectionWriteFailingTaskShellStore();
        ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                failingStorage,
                failingStorage,
                new InMemoryTaskWorkRuntime()
        );

        Task task = createTask(manager, buildRequest("task-result-retry-best-effort", List.of("alpha")));
        manager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        manager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = manager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(manager, task, message, "worker-best-effort-retry-1", "batch-best-effort-retry-1");

        failingStorage.failNextTaskMessageProjectionUpsert();

        assertTrue(manager.ingestTaskResult(task.getTid(), message.messageId(), false, "boom-once"));
        assertEquals(TaskStatus.RUNNING, manager.getTask(task.getTid()).getStatus());
        assertEquals(1, manager.getTaskWorkRuntime().stats(task.getTid()).readyCount());
        assertEquals(0, manager.getTaskWorkRuntime().stats(task.getTid()).finalCount());

        List<ClaimedTaskWork> retried = manager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(WorkerClaimTarget.workerLevel("worker-best-effort-retry-2", "batch-best-effort-retry-2", 1)),
                1,
                manager.getWorkLeaseSeconds()
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
        assignMessage(task, message, "worker-1", "batch-0");

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), false, "boom-once"));
            capture.assertHasEvent("TASK_WORK_RETRY_RESET", mdc ->
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

        InMemoryTaskShellStore managerStorage = new InMemoryTaskShellStore();
            ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                    managerStorage,
                    managerStorage,
                    new InMemoryTaskWorkRuntime());
            TaskCreateSpec dto = buildRequest("task-result-interactive-delayed-retry", List.of("alpha"));
            dto.setContract(TaskContract.SESSION);
            dto.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);
            Task task = createTask(manager, dto);
            manager.approveTask(task.getTid());
            task.setStatus(TaskStatus.RUNNING);
            manager.updateTask(task);

            TaskDetailStore.TaskMessageProjection message = manager.getTaskMessageRecords(task.getTid()).get(0);
            assignMessage(manager, task, message, "worker-1", "batch-0");

            AtomicInteger dispatchEvents = new AtomicInteger();
            CountDownLatch dispatchLatch = new CountDownLatch(1);
            manager.events().addTaskDispatchListener(ignored -> {
                dispatchEvents.incrementAndGet();
                dispatchLatch.countDown();
            });

            assertTrue(manager.ingestTaskResult(task.getTid(), message.messageId(), false, "boom-once"));

            TaskDetailStore.TaskMessageProjection retriedMessage = awaitVisibleTaskMessageProjection(
                    manager,
                    task.getTid(),
                    message.messageId(),
                    TaskMessageProjectionStatus.INIT
            );
            assertEquals(TaskMessageProjectionStatus.INIT, retriedMessage.status());
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
    void batchRetryableFailureDelaysRuntimeVisibilityWithoutTaskLevelRedispatchWakeup() throws InterruptedException {
        String previousBulkRetryDelay = System.getProperty("xa.mass.engine.bulkWorkRetryDelayMillis");
        try {
            System.setProperty("xa.mass.engine.bulkWorkRetryDelayMillis", "200");

            InMemoryTaskShellStore managerStorage = new InMemoryTaskShellStore();
            ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                    managerStorage,
                    managerStorage,
                    new InMemoryTaskWorkRuntime());
            Task task = createTask(manager, buildRequest("task-result-batch-delayed-retry", List.of("alpha")));
            manager.approveTask(task.getTid());
            task.setStatus(TaskStatus.RUNNING);
            manager.updateTask(task);

            TaskDetailStore.TaskMessageProjection message = manager.getTaskMessageRecords(task.getTid()).get(0);
            assignMessage(manager, task, message, "worker-batch-1", "batch-0");

            AtomicInteger dispatchEvents = new AtomicInteger();
            CountDownLatch dispatchLatch = new CountDownLatch(1);
            manager.events().addTaskDispatchListener(ignored -> {
                dispatchEvents.incrementAndGet();
                dispatchLatch.countDown();
            });

            assertTrue(manager.ingestTaskResult(task.getTid(), message.messageId(), false, "boom-once"));

            TaskDetailStore.TaskMessageProjection retriedMessage = awaitVisibleTaskMessageProjection(
                    manager,
                    task.getTid(),
                    message.messageId(),
                    TaskMessageProjectionStatus.INIT
            );
            assertEquals(TaskMessageProjectionStatus.INIT, retriedMessage.status());
            assertEquals(1, retriedMessage.retryCount());
            assertEquals(0, manager.getTaskWorkRuntime().stats(task.getTid()).readyCount());
            assertEquals(1, manager.getTaskWorkRuntime().stats(task.getTid()).delayedCount());
            assertEquals(0, dispatchEvents.get());
            assertFalse(dispatchLatch.await(400, TimeUnit.MILLISECONDS));
            assertEquals(0, dispatchEvents.get());
        } finally {
            restoreProperty("xa.mass.engine.bulkWorkRetryDelayMillis", previousBulkRetryDelay);
        }
    }

    @Test
    void delayedRetryWakeupIsCoalescedPerTaskUnderMultipleRetryableFailures() throws InterruptedException {
        String previousInteractiveRetryDelay = System.getProperty("xa.mass.engine.interactiveWorkRetryDelayMillis");
        try {
            System.setProperty("xa.mass.engine.interactiveWorkRetryDelayMillis", "200");

        InMemoryTaskShellStore managerStorage = new InMemoryTaskShellStore();
            ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                    managerStorage,
                    managerStorage,
                    new InMemoryTaskWorkRuntime());
            TaskCreateSpec dto = buildRequest("task-result-interactive-coalesced-retry", List.of("alpha", "beta"), 1);
            dto.setContract(TaskContract.SESSION);
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

            assertTrue(manager.ingestTaskResult(
                    task.getTid(),
                    messages.get(0).messageId(),
                    false,
                    "retry-alpha",
                    "SYNTHETIC_RETRY",
                    null
            ));
            assertTrue(manager.ingestTaskResult(
                    task.getTid(),
                    messages.get(1).messageId(),
                    false,
                    "retry-beta",
                    "SYNTHETIC_RETRY",
                    null
            ));

            awaitCondition(
                    () -> dispatchEvents.get() >= 1,
                    "coalesced delayed retry wakeup should publish one task dispatch request",
                    10,
                    TimeUnit.SECONDS);
            assertEquals(0, dispatchLatch.getCount());
            waitForNoAdditionalDispatchEvents(dispatchEvents, 1, TimeUnit.SECONDS);

            assertEquals(1, dispatchEvents.get());
            TaskDetailStore.TaskMessageProjection first = awaitVisibleTaskMessageProjection(
                    manager,
                    task.getTid(),
                    messages.get(0).messageId(),
                    TaskMessageProjectionStatus.INIT
            );
            TaskDetailStore.TaskMessageProjection second = awaitVisibleTaskMessageProjection(
                    manager,
                    task.getTid(),
                    messages.get(1).messageId(),
                    TaskMessageProjectionStatus.INIT
            );
            assertEquals(TaskMessageProjectionStatus.INIT, first.status());
            assertEquals(TaskMessageProjectionStatus.INIT, second.status());
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
        TaskDetailStore.TaskMessageProjection compatibilityMessage = ProjectionTestSupport.markAssigned(
                message,
                null,
                null,
                null
        );
        assertTrue(taskManager.upsertTaskMessageProjectionRecord(
                task.getTid(),
                compatibilityMessage
        ));

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertFalse(taskManager.ingestTaskResult(task.getTid(), message.messageId(), true, "done"));
            capture.assertHasEvent("CALLBACK_REJECTED_NO_ACTIVE_LEASE", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.messageId().equals(mdc.get("messageId"))
                            && "INIT".equals(mdc.get("workStatus")));
        }
    }

    @Test
    void callbackForInitProjectionWithoutRuntimeLeaseIsRejectedAndTraced() {
        TrackingTaskMessageProjectionStorage trackingStorage = new TrackingTaskMessageProjectionStorage();
        taskStorage = trackingStorage;
        taskManager = new ProjectionAwareTaskManager(trackingStorage, trackingStorage, new InMemoryTaskWorkRuntime());

        Task task = createTask(buildRequest("task-result-init-callback", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        TaskDetailStore.TaskMessageAttemptProjection attempt = ProjectionTestSupport.attempt(
                "attempt-init",
                task.getTid(),
                message.messageId(),
                1,
                "worker-init",
                "batch-init",
                TaskMessageAttemptProjectionStatus.DISPATCHED
        );
        assertTrue(taskManager.upsertTaskMessageAttemptAuditProjectionRecord(
                task.getTid(),
                message.messageId(),
                attempt
        ));
        trackingStorage.taskMessageProjectionReadCount.set(0);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertFalse(taskManager.ingestTaskResult(task.getTid(), message.messageId(), true, "done"));
            capture.assertHasEvent("CALLBACK_REJECTED_NO_ACTIVE_LEASE", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.messageId().equals(mdc.get("messageId"))
                            && "INIT".equals(mdc.get("workStatus")));
        }
        assertEquals(0, trackingStorage.taskMessageProjectionReadCount.get(),
                "runtime-owned queued work should reject no-lease callbacks without reading message projection residue");

        TaskDetailStore.TaskMessageProjection persistedMessage =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), message.messageId());
        assertEquals(TaskMessageProjectionStatus.INIT, persistedMessage.status());
        assertEquals(TaskMessageAttemptProjectionStatus.DISPATCHED,
                taskManager.getLatestTaskMessageAttemptAuditProjection(task.getTid(), message.messageId()).status());
    }

    @Test
    void callbackDoesNotAcceptFinalProjectionWithoutRuntimeReceiptOrLease() {
        TrackingTaskMessageProjectionStorage trackingStorage = new TrackingTaskMessageProjectionStorage();
        taskStorage = trackingStorage;
        taskManager = new ProjectionAwareTaskManager(trackingStorage, trackingStorage, new InMemoryTaskWorkRuntime());

        Task task = createTask(buildRequest("task-result-final-projection-no-runtime-truth", List.of("alpha", "beta")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        TaskDetailStore.TaskMessageProjection projectionOnlySuccess = ProjectionTestSupport.markSuccess(
                message,
                Map.of("status", "SUCCESS"),
                TaskMessageProjectionFinalReason.BUSINESS_SUCCESS
        );
        assertTrue(taskManager.upsertTaskMessageProjectionRecord(
                task.getTid(),
                projectionOnlySuccess
        ));
        trackingStorage.taskMessageProjectionReadCount.set(0);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertFalse(taskManager.ingestTaskResult(task.getTid(), message.messageId(), true, "done"));
            capture.assertHasEvent("CALLBACK_REJECTED_NO_ACTIVE_LEASE", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.messageId().equals(mdc.get("messageId"))
                            && "INIT".equals(mdc.get("workStatus")));
        }
        assertEquals(0, trackingStorage.taskMessageProjectionReadCount.get(),
                "no-active-lease callback rejection should not consult final compatibility projection residue");

        TaskDetailStore.TaskMessageProjection persistedMessage =
                taskManager.getStoredTaskMessageRecord(task.getTid(), message.messageId());
        assertEquals(TaskMessageProjectionStatus.SUCCESS, persistedMessage.status());
        assertEquals(TaskStatus.RUNNING, taskManager.getTask(task.getTid()).getStatus());
    }

    @Test
    void callbackWithRuntimeLeaseRepairsInitProjectionAndSucceeds() {
        Task task = createTask(buildRequest("task-result-runtime-lease-repair", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message, "worker-repair", "batch-repair");
        TaskDetailStore.TaskMessageProjection compatibilityMessage = ProjectionTestSupport.resetToInit(
                taskManager.getStoredTaskMessageRecord(task.getTid(), message.messageId())
        );
        assertTrue(taskManager.upsertTaskMessageProjectionRecord(
                task.getTid(),
                compatibilityMessage
        ));

        assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), true, "done"));

        TaskDetailStore.TaskMessageProjection updatedMessage = awaitVisibleTaskMessageProjection(
                taskManager,
                task.getTid(),
                message.messageId(),
                TaskMessageProjectionStatus.SUCCESS
        );
        TaskDetailStore.TaskMessageAttemptProjection latestAttempt = awaitVisibleTaskMessageAttemptProjection(
                taskManager,
                task.getTid(),
                message.messageId(),
                TaskMessageAttemptProjectionStatus.SUCCEEDED
        );
        assertEquals(TaskMessageProjectionStatus.SUCCESS, updatedMessage.status());
        assertEquals(TaskMessageProjectionFinalReason.BUSINESS_SUCCESS, updatedMessage.finalReason());
        assertEquals("worker-repair", updatedMessage.latestAttemptWorkerId());        assertEquals("batch-repair", updatedMessage.latestAttemptBatchId());
        assertNotNull(latestAttempt);
        assertEquals(TaskMessageAttemptProjectionStatus.SUCCEEDED, latestAttempt.status());
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
                List.of(WorkerClaimTarget.workerLevel("worker-recover", "batch-recover", 1)),
                1,
                taskManager.getWorkLeaseSeconds()
        );
        assertEquals(1, claimed.size());
        TaskDetailStore.TaskMessageProjection compatibilityMessage = ProjectionTestSupport.markAssigned(
                message,
                TaskWorkAttemptIdSupport.workerLevelRuntimeAttemptId(
                        message.messageId(),
                        Math.max(1, message.retryCount() + 1),
                        "worker-recover",
                        "batch-recover"
                ),
                "worker-recover",
                "batch-recover"
        );
        assertTrue(taskManager.upsertTaskMessageProjectionRecord(
                task.getTid(),
                compatibilityMessage
        ));

        assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), true, "done"));

        TaskDetailStore.TaskMessageAttemptProjection recoveredAttempt =
                awaitVisibleTaskMessageAttemptProjection(
                        taskManager,
                        task.getTid(),
                        message.messageId(),
                        TaskMessageAttemptProjectionStatus.SUCCEEDED
                );
        assertNotNull(recoveredAttempt);
        assertEquals(TaskMessageAttemptProjectionStatus.SUCCEEDED, recoveredAttempt.status());
        assertEquals("worker-recover", recoveredAttempt.workerId());        assertEquals("batch-recover", recoveredAttempt.batchId());
    }

    @Test
    void callbackWithRuntimeLeaseDoesNotReadLatestAttemptAuditOnHotPath() {
        TrackingLatestAttemptStorage trackingStorage = new TrackingLatestAttemptStorage();
        taskStorage = trackingStorage;
        taskManager = new ProjectionAwareTaskManager(trackingStorage, trackingStorage, new InMemoryTaskWorkRuntime());

        Task task = createTask(buildRequest("task-result-runtime-attempt-no-audit-read", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        List<ClaimedTaskWork> claimed = taskManager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(WorkerClaimTarget.workerLevel("worker-no-read", "batch-no-read", 1)),
                1,
                taskManager.getWorkLeaseSeconds()
        );
        assertEquals(1, claimed.size());

        assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), true, "done"));

        assertEquals(0, trackingStorage.latestAttemptReadCount.get(),
                "result hot path should derive attempt correlation from runtime lease without reading latest attempt audit rows");
        TaskDetailStore.TaskMessageAttemptProjection latestAttempt =
                awaitVisibleTaskMessageAttemptProjection(
                        taskManager,
                        task.getTid(),
                        message.messageId(),
                        TaskMessageAttemptProjectionStatus.SUCCEEDED
                );
        assertNotNull(latestAttempt);
        assertEquals(TaskMessageAttemptProjectionStatus.SUCCEEDED, latestAttempt.status());
    }

    @Test
    void callbackWithRuntimeLeaseDoesNotReadMessageProjectionOnHotPath() {
        TrackingTaskMessageProjectionStorage trackingStorage = new TrackingTaskMessageProjectionStorage();
        taskStorage = trackingStorage;
        taskManager = new ProjectionAwareTaskManager(trackingStorage, trackingStorage, new InMemoryTaskWorkRuntime());

        Task task = createTask(buildRequest("task-result-runtime-message-projection-no-read", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        List<ClaimedTaskWork> claimed = taskManager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(WorkerClaimTarget.workerLevel("worker-no-msg-read", "batch-no-msg-read", 1)),
                1,
                taskManager.getWorkLeaseSeconds()
        );
        assertEquals(1, claimed.size());
        trackingStorage.taskMessageProjectionReadCount.set(0);

        assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), true, "done"));

        assertEquals(0, trackingStorage.taskMessageProjectionReadCount.get(),
                "result hot path should accept runtime-owned callback without reading message projection residue");
        TaskDetailStore.TaskMessageProjection finalProjection = awaitVisibleTaskMessageProjection(
                taskManager,
                task.getTid(),
                message.messageId(),
                TaskMessageProjectionStatus.SUCCESS
        );
        assertNotNull(finalProjection);
        assertEquals(TaskMessageProjectionStatus.SUCCESS, finalProjection.status());
    }

    @Test
    void latestActiveAttemptCompatibilityViewDoesNotReadLatestAttemptAuditOnHotPath() {
        TrackingLatestAttemptStorage trackingStorage = new TrackingLatestAttemptStorage();
        taskStorage = trackingStorage;
        taskManager = new ProjectionAwareTaskManager(trackingStorage, trackingStorage, new InMemoryTaskWorkRuntime());

        Task task = createTask(buildRequest("task-active-attempt-view-runtime-owned", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        List<ClaimedTaskWork> claimed = taskManager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(WorkerClaimTarget.workerLevel("worker-active-view", "batch-active-view", 1)),
                1,
                taskManager.getWorkLeaseSeconds()
        );
        assertEquals(1, claimed.size());

        TaskDetailStore.TaskMessageAttemptProjection activeAttempt =
                taskManager.getLatestActiveAttemptProjectionRecord(task.getTid(), message.messageId());

        assertEquals(0, trackingStorage.latestAttemptReadCount.get(),
                "active attempt compatibility view should synthesize from runtime lease without reading latest attempt audit rows");
        assertNotNull(activeAttempt);
        assertEquals(TaskMessageAttemptProjectionStatus.DISPATCHED, activeAttempt.status());
        assertEquals("worker-active-view", activeAttempt.workerId());        assertEquals("batch-active-view", activeAttempt.batchId());
    }

    @Test
    void latestActiveAttemptCompatibilityViewDoesNotReadMessageProjectionOnHotPath() {
        TrackingTaskMessageProjectionStorage trackingStorage = new TrackingTaskMessageProjectionStorage();
        taskStorage = trackingStorage;
        taskManager = new ProjectionAwareTaskManager(trackingStorage, trackingStorage, new InMemoryTaskWorkRuntime());

        Task task = createTask(buildRequest("task-active-attempt-view-no-message-read", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        List<ClaimedTaskWork> claimed = taskManager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(WorkerClaimTarget.workerLevel("worker-active-no-msg", "batch-active-no-msg", 1)),
                1,
                taskManager.getWorkLeaseSeconds()
        );
        assertEquals(1, claimed.size());
        trackingStorage.taskMessageProjectionReadCount.set(0);

        TaskDetailStore.TaskMessageAttemptProjection activeAttempt =
                taskManager.getLatestActiveAttemptProjectionRecord(task.getTid(), message.messageId());

        assertEquals(0, trackingStorage.taskMessageProjectionReadCount.get(),
                "active attempt compatibility view should synthesize from runtime lease without reading message projection residue");
        assertNotNull(activeAttempt);
        assertEquals(TaskMessageAttemptProjectionStatus.DISPATCHED, activeAttempt.status());
    }

    @Test
    void activeAttemptCompatibilityAuditViewRecoversRuntimeAttemptWhenAttemptProjectionIsMissing() {
        taskStorage = new InMemoryTaskShellStore();
        taskManager = new ProjectionAwareTaskManager(taskStorage, taskStorage, new InMemoryTaskWorkRuntime());

        Task task = createTask(buildRequest("task-attempt-audit-runtime-recovery", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        List<ClaimedTaskWork> claimed = taskManager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(WorkerClaimTarget.workerLevel("worker-audit-view", "batch-audit-view", 1)),
                1,
                taskManager.getWorkLeaseSeconds()
        );
        assertEquals(1, claimed.size());
        assertTrue(taskStorage.getTaskMessageAttemptProjections(task.getTid(), message.messageId()).isEmpty());

        List<TaskDetailStore.TaskMessageAttemptProjection> visibleAttempts =
                taskManager.getVisibleAttemptProjectionRecords(task.getTid(), message.messageId());

        assertEquals(1, visibleAttempts.size());
        TaskDetailStore.TaskMessageAttemptProjection activeAttempt = visibleAttempts.getFirst();
        assertEquals(TaskWorkAttemptIdSupport.workerLevelRuntimeAttemptId(
                message.messageId(),
                1,
                "worker-audit-view",
                "batch-audit-view"
        ), activeAttempt.attemptId());
        assertEquals(TaskMessageAttemptProjectionStatus.DISPATCHED, activeAttempt.status());
        assertEquals("worker-audit-view", activeAttempt.workerId());        assertEquals("batch-audit-view", activeAttempt.batchId());
    }

    @Test
    void activeAttemptCompatibilityAuditViewDoesNotReadMessageProjectionOnRecoveryPath() {
        TrackingTaskMessageProjectionStorage trackingStorage = new TrackingTaskMessageProjectionStorage();
        taskStorage = trackingStorage;
        taskManager = new ProjectionAwareTaskManager(trackingStorage, trackingStorage, new InMemoryTaskWorkRuntime());

        Task task = createTask(buildRequest("task-attempt-audit-no-message-read", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        List<ClaimedTaskWork> claimed = taskManager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(WorkerClaimTarget.workerLevel("worker-audit-no-msg", "batch-audit-no-msg", 1)),
                1,
                taskManager.getWorkLeaseSeconds()
        );
        assertEquals(1, claimed.size());
        assertTrue(taskStorage.getTaskMessageAttemptProjections(task.getTid(), message.messageId()).isEmpty());
        trackingStorage.taskMessageProjectionReadCount.set(0);

        List<TaskDetailStore.TaskMessageAttemptProjection> visibleAttempts =
                taskManager.getVisibleAttemptProjectionRecords(task.getTid(), message.messageId());

        assertEquals(0, trackingStorage.taskMessageProjectionReadCount.get(),
                "attempt audit recovery should synthesize active attempt from runtime lease without reading message projection residue");
        assertEquals(1, visibleAttempts.size());
        assertEquals(TaskMessageAttemptProjectionStatus.DISPATCHED, visibleAttempts.getFirst().status());
    }

    @Test
    void expiryWithRuntimeLeaseDoesNotReadMessageProjectionOnHotPath() {
        TrackingTaskMessageProjectionStorage trackingStorage = new TrackingTaskMessageProjectionStorage();
        taskStorage = trackingStorage;
        taskManager = new ProjectionAwareTaskManager(trackingStorage, trackingStorage, new InMemoryTaskWorkRuntime());

        Task task = createTask(buildRequest("task-expiry-runtime-message-projection-no-read", List.of("alpha"), 0));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        List<ClaimedTaskWork> claimed = taskManager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(WorkerClaimTarget.workerLevel("worker-expiry-no-msg-read", "batch-expiry-no-msg-read", 1)),
                1,
                taskManager.getWorkLeaseSeconds()
        );
        assertEquals(1, claimed.size());
        trackingStorage.taskMessageProjectionReadCount.set(0);

        assertTrue(taskManager.expireLeasedWork(task.getTid(), message.messageId()));

        assertEquals(0, trackingStorage.taskMessageProjectionReadCount.get(),
                "expiry hot path should converge from runtime lease without reading message projection residue");
        TaskDetailStore.TaskMessageProjection finalProjection = awaitVisibleTaskMessageProjection(
                taskManager,
                task.getTid(),
                message.messageId(),
                TaskMessageProjectionStatus.FAILED
        );
        assertNotNull(finalProjection);
        assertEquals(TaskMessageProjectionStatus.FAILED, finalProjection.status());
    }

    @Test
    void callbackWithRuntimeLeaseOverridesFinalMessageProjectionResidue() {
        Task task = createTask(buildRequest("task-result-runtime-final-projection-residue", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message, "worker-final-residue", "batch-final-residue");
        TaskDetailStore.TaskMessageProjection compatibilityMessage = ProjectionTestSupport.forceFinal(
                taskManager.getStoredTaskMessageRecord(task.getTid(), message.messageId()),
                TaskMessageProjectionStatus.FAILED,
                TaskMessageProjectionFinalReason.BUSINESS_FAILED,
                "stale-final-projection",
                "STALE",
                java.util.Map.of("stale", true)
        );
        assertTrue(taskManager.upsertTaskMessageProjectionRecord(
                task.getTid(),
                compatibilityMessage
        ));

        assertTrue(taskManager.ingestTaskResult(
                task.getTid(),
                message.messageId(),
                true,
                "done",
                null,
                java.util.Map.of("fresh", true)
        ));

        TaskDetailStore.TaskMessageProjection updatedMessage = awaitVisibleTaskMessageProjection(
                taskManager,
                task.getTid(),
                message.messageId(),
                TaskMessageProjectionStatus.SUCCESS
        );
        TaskDetailStore.TaskMessageAttemptProjection latestAttempt = awaitVisibleTaskMessageAttemptProjection(
                taskManager,
                task.getTid(),
                message.messageId(),
                TaskMessageAttemptProjectionStatus.SUCCEEDED
        );

        assertNotNull(updatedMessage);
        assertEquals(TaskMessageProjectionStatus.SUCCESS, updatedMessage.status());
        assertEquals(TaskMessageProjectionFinalReason.BUSINESS_SUCCESS, updatedMessage.finalReason());
        assertNull(updatedMessage.errorCode());
        assertEquals(java.util.Map.of("fresh", true), updatedMessage.output());
        assertEquals("worker-final-residue", updatedMessage.latestAttemptWorkerId());
        assertNotNull(latestAttempt);
        assertEquals(TaskMessageAttemptProjectionStatus.SUCCEEDED, latestAttempt.status());
    }

    @Test
    void retryableFailurePublishesAttemptClosedBeforeDispatchRequested() {
        String previousDelay = System.getProperty("xa.mass.engine.interactiveWorkRetryDelayMillis");
        try {
            System.setProperty("xa.mass.engine.interactiveWorkRetryDelayMillis", "0");
            taskManager = new ProjectionAwareTaskManager(
                    taskStorage,
                    taskStorage,
                    new InMemoryTaskWorkRuntime()
            );

            TaskCreateSpec request = buildRequest("task-result-retry-order", List.of("alpha"));
            request.setContract(TaskContract.SESSION);
            request.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);
            Task task = createTask(request);
            taskManager.approveTask(task.getTid());
            task.setStatus(TaskStatus.RUNNING);
            taskManager.updateTask(task);

            TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
            assignMessage(task, message, "worker-1", "batch-0");

            List<String> events = new java.util.ArrayList<>();
            taskManager.events().addTaskWorkAttemptClosedListener((currentTask, attempt) ->
                    events.add("attempt-closed:" + attempt.status()));
            taskManager.events().addTaskWorkLogicallyFinalListener((currentTask, event) ->
                    events.add("logical-final:" + event.status()));
            taskManager.events().addTaskDispatchListener(currentTask ->
                    events.add("dispatch:" + currentTask.getStatus()));

            assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), false, "boom-once"));

            assertEquals(List.of("attempt-closed:REVOKED", "dispatch:RUNNING"), events);
        } finally {
            restoreProperty("xa.mass.engine.interactiveWorkRetryDelayMillis", previousDelay);
        }
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
        taskManager.events().addTaskWorkLogicallyFinalListener((currentTask, event) -> events.add("logical-final"));
        taskManager.events().addTaskTerminalListener(currentTask -> events.add("terminal"));

        assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), true, "done"));

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

        assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), false, "boom-final"));

        TaskDetailStore.TaskMessageAttemptProjection attempt = awaitVisibleTaskMessageAttemptProjection(
                taskManager,
                task.getTid(),
                message.messageId(),
                TaskMessageAttemptProjectionStatus.FAILED
        );
        assertNotNull(attempt);
        assertEquals(TaskMessageAttemptProjectionStatus.FAILED, attempt.status());
        assertEquals(TaskMessageAttemptProjectionFinalReason.BUSINESS_FAILURE, attempt.finalReason());
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

        assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), true, "done-while-paused"));

        Task updatedTask = taskManager.getTask(task.getTid());
        TaskDetailStore.TaskMessageProjection updatedMessage =
                awaitVisibleTaskMessageProjection(taskManager,
                        task.getTid(),
                        message.messageId(),
                        TaskMessageProjectionStatus.SUCCESS);
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, updatedTask.getTerminalReason());
        assertEquals(1, updatedTask.getTaskSuccessNumber());
        assertEquals(TaskMessageProjectionStatus.SUCCESS, updatedMessage.status());
    }

    @Test
    void resolveTaskStateFinalizesRunningTaskWhenAllMessagesAreFinal() {
        Task task = createTask(buildRequest("task-resolution-finalized"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskDetailStore.TaskMessageProjection> messages = taskManager.getTaskMessageRecords(task.getTid());
        messages.forEach(msg -> assignMessage(task, msg));
        taskManager.ingestTaskResult(task.getTid(), messages.get(0).messageId(), true, "done-1");
        taskManager.ingestTaskResult(task.getTid(), messages.get(1).messageId(), true, "done-2");

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
        assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), true, "done-while-paused"));

        task.setStatus(TaskStatus.PAUSED);
        task.setTerminalReason(null);
        taskManager.updateTask(task);

        TaskResumeResult result = taskManager.resumeTaskDetailed(task.getTid());

        assertTrue(result.isSuccess());
        assertEquals(TaskResumeResult.Outcome.COMPLETED_TO_TERMINAL, result.getOutcome());
        assertEquals(TaskStatus.TERMINAL, result.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, result.getTerminalReason());
    }

    @Test
    void batchTaskDispatchRequestsDoNotEmitSessionDispatchSignals() {
        Task task = createTask(buildRequest("task-batch-runtime-dispatch", List.of("alpha")));
        taskManager.approveTask(task.getTid());

        AtomicInteger dispatchRequests = new AtomicInteger();
        taskManager.events().addTaskDispatchListener(ignored -> dispatchRequests.incrementAndGet());

        taskManager.requestTaskDispatch(taskManager.getTask(task.getTid()));

        assertEquals(0, dispatchRequests.get());
    }

    @Test
    void pausedOpenEndedTaskCanAppendWithoutImmediateDispatch() {
        TaskCreateSpec request = buildRequest("task-open-ended-paused-append", List.of("alpha"));
        request.setContract(TaskContract.SESSION);
        request.setSealIntakeAfterCreate(false);
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

        assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), true, "late-success"));

        Task updatedTask = taskManager.getTask(task.getTid());
        TaskDetailStore.TaskMessageProjection updatedMessage = awaitVisibleTaskMessageProjection(
                taskManager,
                task.getTid(),
                message.messageId(),
                TaskMessageProjectionStatus.SUCCESS
        );
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.MANUAL_CANCELLED, updatedTask.getTerminalReason());
        assertEquals(0, updatedTask.getTaskSuccessNumber());
        // terminal task reads overlay the compatibility view without rewriting
        // every queued or leased message projection row
        assertEquals(TaskMessageProjectionStatus.EXPIRED, updatedMessage.status());
    }

    @Test
    void lateCallbackEmitsIgnoredLateTrace() {
        TrackingTaskMessageProjectionStorage trackingStorage = new TrackingTaskMessageProjectionStorage();
        taskStorage = trackingStorage;
        taskManager = new ProjectionAwareTaskManager(trackingStorage, trackingStorage, new InMemoryTaskWorkRuntime());

        Task task = createTask(buildRequest("task-late-trace", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        task.setStatus(TaskStatus.TERMINAL);
        task.setTerminalReason(TaskTerminalReason.MANUAL_CANCELLED);
        taskManager.updateTask(task);
        trackingStorage.taskMessageProjectionReadCount.set(0);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), true, "late-success"));
            capture.assertHasEvent("CALLBACK_IGNORED_LATE", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.messageId().equals(mdc.get("messageId")));
        }
        assertEquals(0, trackingStorage.taskMessageProjectionReadCount.get(),
                "manual/policy terminal callbacks should not re-read message projection residue on the accepted path");
    }

    @Test
    void duplicateTaskMessageResultKeepsFirstFinalStateAndDoesNotTriggerSchedulerTwice() {
        Task task = createTask(buildRequest("task-result-duplicate", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), true, "done-once"));
        assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), false, "boom-twice"));

        TaskDetailStore.TaskMessageProjection updatedMessage = awaitVisibleTaskMessageProjection(
                taskManager,
                task.getTid(),
                message.messageId(),
                TaskMessageProjectionStatus.SUCCESS
        );
        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskMessageProjectionStatus.SUCCESS, updatedMessage.status());
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

        assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), true, "done-once"));

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), true, "done-twice"));
            capture.assertHasEvent("CALLBACK_IGNORED_DUPLICATE", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.messageId().equals(mdc.get("messageId")));
        }
    }

    @Test
    void runningTaskDuplicateCallbackUsesRuntimeFinalReceiptWithoutProjectionRead() {
        TrackingTaskMessageProjectionStorage trackingStorage = new TrackingTaskMessageProjectionStorage();
        taskStorage = trackingStorage;
        taskManager = new ProjectionAwareTaskManager(trackingStorage, trackingStorage, new InMemoryTaskWorkRuntime());

        Task task = createTask(buildRequest("task-running-duplicate-runtime-final-receipt", List.of("alpha", "beta")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        List<TaskDetailStore.TaskMessageProjection> messages = taskManager.getTaskMessageRecords(task.getTid());
        messages.forEach(msg -> assignMessage(task, msg));

        assertTrue(taskManager.ingestTaskResult(task.getTid(), messages.get(0).messageId(), true, "done-once"));
        trackingStorage.taskMessageProjectionReadCount.set(0);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.ingestTaskResult(task.getTid(), messages.get(0).messageId(), false, "boom-twice"));
            capture.assertHasEvent("CALLBACK_IGNORED_DUPLICATE", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && messages.get(0).messageId().equals(mdc.get("messageId")));
        }
        assertEquals(0, trackingStorage.taskMessageProjectionReadCount.get(),
                "recent runtime final receipts should absorb duplicate callbacks without re-reading message projection residue");
        assertEquals(TaskStatus.RUNNING, taskManager.getTask(task.getTid()).getStatus());
    }

    @Test
    void terminalDuplicateCallbackDoesNotReadMessageProjectionResidue() {
        TrackingTaskMessageProjectionStorage trackingStorage = new TrackingTaskMessageProjectionStorage();
        taskStorage = trackingStorage;
        taskManager = new ProjectionAwareTaskManager(trackingStorage, trackingStorage, new InMemoryTaskWorkRuntime());

        Task task = createTask(buildRequest("task-terminal-duplicate-no-projection-read", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), true, "done-once"));
        trackingStorage.taskMessageProjectionReadCount.set(0);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), false, "boom-twice"));
            capture.assertHasEvent("CALLBACK_IGNORED_DUPLICATE", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.messageId().equals(mdc.get("messageId")));
        }
        assertEquals(0, trackingStorage.taskMessageProjectionReadCount.get(),
                "terminal duplicate callbacks should not re-read message projection residue on the accepted path");
    }

    @Test
    void mixedFinalTaskMessagesProduceMixedTerminalReason() {
        Task task = createTask(buildRequest("task-result-mixed", List.of("alpha", "beta"), 0));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        List<TaskDetailStore.TaskMessageProjection> messages = taskManager.getTaskMessageRecords(task.getTid());
        messages.forEach(msg -> assignMessage(task, msg));

        assertTrue(taskManager.ingestTaskResult(task.getTid(), messages.get(0).messageId(), true, "done"));
        assertTrue(taskManager.ingestTaskResult(task.getTid(), messages.get(1).messageId(), false, "boom"));

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

        assertTrue(taskManager.ingestTaskResult(task.getTid(), messages.get(0).messageId(), false, "boom-1"));
        assertTrue(taskManager.ingestTaskResult(task.getTid(), messages.get(1).messageId(), false, "boom-2"));

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

        assertTrue(taskManager.ingestTaskResult(task.getTid(), messages.get(0).messageId(), true, "done-1"));
        assertTrue(taskManager.ingestTaskResult(task.getTid(), messages.get(1).messageId(), true, "done-2"));

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
    void validateTaskStateReportsNeedsResolutionWhenMessagesAreFinalButTaskIsStillRunning() {
        Task task = createTask(buildRequest("task-validate-needs-resolution"));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        List<TaskDetailStore.TaskMessageProjection> messages = taskManager.getTaskMessageRecords(task.getTid());
        messages.forEach(msg -> assignMessage(task, msg));
        assertTrue(taskManager.ingestTaskResult(task.getTid(), messages.get(0).messageId(), true, "done-1"));
        assertTrue(taskManager.ingestTaskResult(task.getTid(), messages.get(1).messageId(), true, "done-2"));

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
        assertTrue(taskManager.ingestTaskResult(task.getTid(), messages.get(0).messageId(), true, "done-1"));
        assertTrue(taskManager.ingestTaskResult(task.getTid(), messages.get(1).messageId(), true, "done-2"));

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
        PagingAwareTaskShellStore pagingStorage = new PagingAwareTaskShellStore();
        ProjectionAwareTaskManager pagingTaskManager = new ProjectionAwareTaskManager(pagingStorage, pagingStorage, new InMemoryTaskWorkRuntime());
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
    void validateTaskStateRejectsTerminalTaskWithoutTerminalReason() {
        Task task = createTask(buildRequest("task-validate-missing-terminal-reason", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);
        assertTrue(taskManager.ingestTaskResult(task.getTid(), message.messageId(), true, "done"));

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
        assertTrue(taskManager.ingestTaskResult(task.getTid(), messages.get(0).messageId(), true, "done"));
        assertTrue(taskManager.ingestTaskResult(task.getTid(), messages.get(1).messageId(), false, "boom"));

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
        InMemoryTaskShellStore policyStorage = new InMemoryTaskShellStore();
        ProjectionAwareTaskManager policyAwareManager = new ProjectionAwareTaskManager(
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
        assertTrue(policyAwareManager.ingestTaskResult(task.getTid(), message.messageId(), true, "done"));

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
        InMemoryTaskShellStore policyStorage = new InMemoryTaskShellStore();
        ProjectionAwareTaskManager policyAwareManager = new ProjectionAwareTaskManager(
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
    void validateTaskStateClosesIntakeDuringRuntimeLimitTerminalClosure() {
        TaskTerminalPolicy runtimeLimitPolicy = (task, stats) ->
                TaskTerminalPolicyDecision.finalizeToTerminal(TaskTerminalReason.MAX_RUNTIME_REACHED);
        InMemoryTaskShellStore policyStorage = new InMemoryTaskShellStore();
        ProjectionAwareTaskManager policyAwareManager = new ProjectionAwareTaskManager(
                policyStorage,
                policyStorage,
                runtimeLimitPolicy,
                new InMemoryTaskWorkRuntime()
        );

        TaskCreateSpec request = buildRequest("task-open-intake-runtime-limit", List.of("alpha"));
        request.setSealIntakeAfterCreate(false);
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
        assertEquals(TaskIntakeStatus.SEALED, policyAwareManager.getTask(task.getTid()).getIntakeStatus());
        assertFalse(validationResult.getViolations().contains(
                TaskStateValidationResult.ViolationCode.TERMINAL_TASK_WITH_OPEN_INTAKE));
    }

    @Test
    void validateTaskStateFlagsOpenIntakeViolationForTerminalTasks() {
        List<TaskTerminalReason> policyDrivenReasons = List.of(
                TaskTerminalReason.MAX_RUNTIME_REACHED,
                TaskTerminalReason.SUCCESS_RATE_REACHED,
                TaskTerminalReason.RETRY_BUDGET_EXHAUSTED
        );

        for (TaskTerminalReason terminalReason : policyDrivenReasons) {
            TaskCreateSpec request = buildRequest("task-open-intake-" + terminalReason.name(), List.of("alpha"));
            request.setSealIntakeAfterCreate(false);
            Task task = createTask(request);
            task.setStatus(TaskStatus.TERMINAL);
            task.setTerminalReason(terminalReason);
            taskManager.updateTask(task);

            TaskStateValidationResult result = taskManager.validateTaskState(task.getTid());

            assertFalse(result.isValid(), terminalReason.name());
            assertFalse(result.isNeedsResolution(), terminalReason.name());
            assertEquals(TaskStatus.TERMINAL, result.getStatus(), terminalReason.name());
            assertEquals(terminalReason, result.getTerminalReason(), terminalReason.name());
            assertTrue(result.getViolations().contains(
                    TaskStateValidationResult.ViolationCode.TERMINAL_TASK_WITH_OPEN_INTAKE), terminalReason.name());
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

    // ---- Bug2: expired message projection -> expireLeasedWork ----

    @Test
    void expireAssignedBatchMessageWithoutRetryBudgetFinalizesAsFailureAndTaskAutoCompletes() {
        Task task = createTask(buildRequest("expire-msg", List.of("alpha"), 0));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignMessage(task, message);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(taskManager.expireLeasedWork(task.getTid(), message.messageId()));
            capture.assertHasEvent("LEASE_EXPIRED", mdc ->
                    task.getTid().equals(mdc.get("taskId"))
                            && message.messageId().equals(mdc.get("messageId"))
                            && "FAILED".equals(mdc.get("toStatus"))
                            && "FAILED".equals(mdc.get("result")));
        }

        TaskDetailStore.TaskMessageProjection updated = awaitVisibleTaskMessageProjection(
                taskManager,
                task.getTid(),
                message.messageId(),
                TaskMessageProjectionStatus.FAILED
        );
        assertNotNull(updated);
        assertEquals(TaskMessageProjectionStatus.FAILED, updated.status());
        assertEquals(TaskMessageProjectionFinalReason.RETRY_EXHAUSTED, updated.finalReason());

        TaskResultRuntimeRow resultRow = taskManager.getTaskResultRuntime()
                .getVisibleByMessageId(task.getTid(), message.messageId())
                .orElseThrow();
        assertEquals("FAILED", resultRow.status());
        assertEquals("RETRY_EXHAUSTED", resultRow.finalReason());
        assertEquals("LEASE_EXPIRED", resultRow.errorCode());
        assertTrue(resultRow.logicalFinalPublished());
        assertTrue(resultRow.progressApplied());

        // All messages are final, so the task should auto-terminate
        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, updatedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_FAILED, updatedTask.getTerminalReason());
    }

    @Test
    void expireRunningSessionMessageTransitionsToExpired() {
        TaskCreateSpec request = buildRequest("expire-running", List.of("alpha"), 0);
        request.setContract(TaskContract.SESSION);
        request.setSealIntakeAfterCreate(false);
        Task task = createTask(request);
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        assignRunningMessage(task, message);

        assertTrue(taskManager.expireLeasedWork(task.getTid(), message.messageId()));
        TaskDetailStore.TaskMessageProjection updated = awaitVisibleTaskMessageProjection(
                taskManager,
                task.getTid(),
                message.messageId(),
                TaskMessageProjectionStatus.EXPIRED
        );
        assertEquals(TaskMessageProjectionStatus.EXPIRED, updated.status());
    }

    @Test
    void expireWithRuntimeLeaseRepairsInitProjectionAndFinalizesBatchFailure() {
        Task task = createTask(buildRequest("expire-runtime-lease-repair", List.of("alpha"), 0));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection initialMessage = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        TaskDetailStore.TaskMessageProjection assigned = assignMessage(
                task,
                initialMessage,
                "worker-expire-repair",
                "batch-expire-repair"
        );
        TaskDetailStore.TaskMessageProjection repairedProjection = ProjectionTestSupport.resetToInit(assigned);
        assertTrue(taskManager.upsertTaskMessageProjectionRecord(
                task.getTid(),
                repairedProjection
        ));

        assertTrue(taskManager.expireLeasedWork(task.getTid(), initialMessage.messageId()));

        TaskDetailStore.TaskMessageProjection updatedMessage = awaitVisibleTaskMessageProjection(
                taskManager,
                task.getTid(),
                initialMessage.messageId(),
                TaskMessageProjectionStatus.FAILED
        );
        TaskDetailStore.TaskMessageAttemptProjection latestAttempt = awaitVisibleTaskMessageAttemptProjection(
                taskManager,
                task.getTid(),
                initialMessage.messageId(),
                TaskMessageAttemptProjectionStatus.EXPIRED
        );
        assertEquals(TaskMessageProjectionStatus.FAILED, updatedMessage.status());
        assertEquals(TaskMessageProjectionFinalReason.RETRY_EXHAUSTED, updatedMessage.finalReason());
        assertNotNull(latestAttempt);
        assertEquals(TaskMessageAttemptProjectionStatus.EXPIRED, latestAttempt.status());
    }

    @Test
    void expireAssignedMessageWithRetryBudgetResetsToInitAndRequestsRedispatch() {
        String previousDelay = System.getProperty("xa.mass.engine.interactiveWorkRetryDelayMillis");
        try {
            System.setProperty("xa.mass.engine.interactiveWorkRetryDelayMillis", "0");
            taskManager = new ProjectionAwareTaskManager(
                    taskStorage,
                    taskStorage,
                    new InMemoryTaskWorkRuntime()
            );

            TaskCreateSpec request = buildRequest("expire-retry", List.of("alpha"), 1);
            request.setContract(TaskContract.SESSION);
            request.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);
            Task task = createTask(request);
            taskManager.approveTask(task.getTid());
            task.setStatus(TaskStatus.RUNNING);
            taskManager.updateTask(task);

            TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
            assignMessage(task, message, "worker-expire-1", "batch-expire-0");

            List<String> events = new java.util.ArrayList<>();
            taskManager.events().addTaskWorkAttemptClosedListener((currentTask, attempt) ->
                    events.add("attempt-closed:" + attempt.status()));
            taskManager.events().addTaskWorkLogicallyFinalListener((currentTask, event) ->
                    events.add("logical-final:" + event.status()));
            taskManager.events().addTaskDispatchListener(currentTask ->
                    events.add("dispatch:" + currentTask.getStatus()));

            assertTrue(taskManager.expireLeasedWork(task.getTid(), message.messageId()));

            assertEquals(0, taskManager.getTaskResultRuntime().countVisibleResults(task.getTid()));
            assertTrue(taskManager.getTaskResultRuntime().scanRepairCandidates(10).isEmpty());

            TaskDetailStore.TaskMessageProjection retriedMessage = awaitVisibleTaskMessageProjection(
                    taskManager,
                    task.getTid(),
                    message.messageId(),
                    TaskMessageProjectionStatus.INIT
            );
            Task updatedTask = taskManager.getTask(task.getTid());

            assertEquals(TaskMessageProjectionStatus.INIT, retriedMessage.status());
            assertEquals(1, retriedMessage.retryCount());
            assertNull(retriedMessage.finalReason());
            assertNull(retriedMessage.latestAttemptWorkerId());            assertNull(retriedMessage.latestAttemptBatchId());
            assertNull(taskManager.getLatestActiveAttemptProjectionRecord(task.getTid(), message.messageId()));

            List<TaskDetailStore.TaskMessageAttemptProjection> attemptProjections = awaitAttemptProjectionHistory(
                    taskStorage,
                    task.getTid(),
                    message.messageId(),
                    attempts -> attempts.stream().anyMatch(attempt ->
                            attempt.status() == TaskMessageAttemptProjectionStatus.EXPIRED
                                    && attempt.finalReason() == TaskMessageAttemptProjectionFinalReason.LEASE_EXPIRED)
            );
            assertTrue(
                    attemptProjections.stream().anyMatch(attempt ->
                            attempt.status() == TaskMessageAttemptProjectionStatus.EXPIRED
                                    && attempt.finalReason() == TaskMessageAttemptProjectionFinalReason.LEASE_EXPIRED),
                    "attempt audit should retain the expired closed attempt even after redispatch window reopens"
            );

            assertEquals(TaskStatus.RUNNING, updatedTask.getStatus());
            assertEquals(List.of("attempt-closed:EXPIRED", "dispatch:RUNNING"), events);
        } finally {
            restoreProperty("xa.mass.engine.interactiveWorkRetryDelayMillis", previousDelay);
        }
    }

    @Test
    void expireInitOrBindingMessageIsRejected() {
        Task task = createTask(buildRequest("expire-init", List.of("alpha")));
        taskManager.approveTask(task.getTid());

        TaskDetailStore.TaskMessageProjection message = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        // message is in INIT state and cannot be expired (never dispatched)
        assertFalse(taskManager.expireLeasedWork(task.getTid(), message.messageId()));
        assertEquals(TaskMessageProjectionStatus.INIT,
                taskManager.getVisibleTaskMessageProjection(task.getTid(), message.messageId()).status());
    }

    // ---- Bug3: cancelTask should stay task/runtime-first ----

    @Test
    void cancelTaskLeavesStoredMessageProjectionUntouchedAndOverlaysTerminalView() {
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

        assertEquals(TaskMessageProjectionStatus.ASSIGNED, storedMsg0.status());
        assertEquals(TaskMessageProjectionStatus.INIT, storedMsg1.status());
        assertEquals(TaskMessageProjectionStatus.INIT, storedMsg2.status());

        assertTrue(viewMsg0.status().isFinal(), "assigned message should read as final after cancel");
        assertEquals(TaskMessageProjectionStatus.EXPIRED, viewMsg0.status());
        assertEquals(TaskMessageProjectionFinalReason.MANUAL_CANCELLED, viewMsg0.finalReason());
        assertTrue(viewMsg1.status().isFinal(), "INIT message should read as final after cancel");
        assertEquals(TaskMessageProjectionStatus.FAILED, viewMsg1.status());
        assertEquals(TaskMessageProjectionFinalReason.MANUAL_CANCELLED, viewMsg1.finalReason());
        assertTrue(viewMsg2.status().isFinal(), "INIT message should read as final after cancel");
        assertEquals(TaskMessageProjectionStatus.FAILED, viewMsg2.status());
        assertEquals(TaskMessageProjectionFinalReason.MANUAL_CANCELLED, viewMsg2.finalReason());
    }

    @Test
    void cancelTaskOverlaysAssignedMessageWithoutRestampingStoredProjection() {
        Task task = createTask(buildRequest("cancel-no-attempt-residue", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection storedMessage = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        TaskDetailStore.TaskMessageProjection assignedProjection = ProjectionTestSupport.markAssigned(
                storedMessage,
                null,
                null,
                null
        );
        assertTrue(taskManager.upsertTaskMessageProjectionRecord(
                task.getTid(),
                assignedProjection
        ));

        assertTrue(taskManager.cancelTask(task.getTid()));

        TaskDetailStore.TaskMessageProjection stored =
                taskManager.getStoredTaskMessageRecord(task.getTid(), storedMessage.messageId());
        TaskDetailStore.TaskMessageProjection cancelled =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), storedMessage.messageId());
        assertEquals(TaskMessageProjectionStatus.ASSIGNED, stored.status());
        assertEquals(TaskMessageProjectionStatus.EXPIRED, cancelled.status());
        assertEquals(TaskMessageProjectionFinalReason.MANUAL_CANCELLED, cancelled.finalReason());
        assertTrue(cancelled.status().isFinal());
        assertNull(taskManager.getLatestActiveAttemptProjectionRecord(task.getTid(), storedMessage.messageId()));
    }

    @Test
    void cancelTaskDoesNotRestampMessageProjectionFromRuntimeLeaseOnly() {
        Task task = createTask(buildRequest("cancel-no-runtime-lease-restamp", List.of("alpha")));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection storedMessage = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        List<ClaimedTaskWork> claimed = taskManager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(WorkerClaimTarget.workerLevel("worker-cancel-runtime-only", "batch-cancel-runtime-only", 1)),
                1,
                taskManager.getWorkLeaseSeconds()
        );
        assertEquals(1, claimed.size());
        assertEquals(storedMessage.messageId(), claimed.get(0).messageId());

        assertTrue(taskManager.cancelTask(task.getTid()));

        TaskDetailStore.TaskMessageProjection stored =
                taskManager.getStoredTaskMessageRecord(task.getTid(), storedMessage.messageId());
        TaskDetailStore.TaskMessageProjection cancelled =
                taskManager.getVisibleTaskMessageProjection(task.getTid(), storedMessage.messageId());

        assertEquals(TaskMessageProjectionStatus.INIT, stored.status());
        assertNull(stored.latestAttemptWorkerId());
        assertEquals(TaskMessageProjectionStatus.FAILED, cancelled.status());
        assertEquals(TaskMessageProjectionFinalReason.MANUAL_CANCELLED, cancelled.finalReason());
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

        ProjectionTestSupport.MessageSnapshot snapshot = taskManager.getTaskMessageSnapshot(task.getTid(), 10);
        assertEquals(2, snapshot.messages().size());
        assertEquals(List.of(TaskMessageProjectionStatus.EXPIRED, TaskMessageProjectionStatus.FAILED),
                snapshot.messages().stream().map(TaskDetailStore.TaskMessageProjection::status).toList());

        TaskDetailStore.TaskMessageProjection storedAssigned =
                taskManager.getStoredTaskMessageRecord(task.getTid(), messages.get(0).messageId());
        TaskDetailStore.TaskMessageProjection storedQueued =
                taskManager.getStoredTaskMessageRecord(task.getTid(), messages.get(1).messageId());
        assertEquals(TaskMessageProjectionStatus.ASSIGNED, storedAssigned.status());
        assertEquals(TaskMessageProjectionStatus.INIT, storedQueued.status());
    }

    @Test
    void compatibilitySnapshotRemainsBoundedWhenRuntimeOverlayAddsMissingProjectionMessages() {
        ProjectionWriteFailingTaskShellStore failingStorage = new ProjectionWriteFailingTaskShellStore();
        ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                failingStorage,
                failingStorage,
                new InMemoryTaskWorkRuntime()
        );

        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setSourceRef("snapshot-bounded-runtime-overlay");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setSealIntakeAfterCreate(false);
        dto.setContract(TaskContract.SESSION);
        dto.setInputs(List.of(java.util.Map.<String, Object>of("target", "stored")));

        Task task = createTask(manager, dto);
        manager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        manager.updateTask(task);

        String payloadRef = "s3://bucket/payloads/snapshot-bounded-runtime-overlay.json";
        failingStorage.failNextTaskMessageAdd();
        manager.ingestRuntimePayloadRef(task.getTid(), java.util.UUID.randomUUID().toString(), payloadRef, 1);

        List<ClaimedTaskWork> claimed = manager.getTaskWorkRuntime().claimReady(
                task.getTid(),
                List.of(WorkerClaimTarget.workerLevel("worker-snapshot-bound", "batch-snapshot-bound", 2)),
                2,
                manager.getWorkLeaseSeconds()
        );
        assertEquals(2, claimed.size());

        ProjectionTestSupport.MessageSnapshot limitOneSnapshot = manager.getTaskMessageSnapshot(task.getTid(), 1);
        ProjectionTestSupport.MessageSnapshot zeroSnapshot = manager.getTaskMessageSnapshot(task.getTid(), 0);

        assertEquals(1, limitOneSnapshot.messages().size());
        assertTrue(limitOneSnapshot.truncated());
        assertEquals(0, zeroSnapshot.messages().size());
        assertTrue(zeroSnapshot.truncated());
    }

    @Test
    void compatibilitySnapshotTruncationUsesRuntimeTotalWhenProjectionResidueIsMissing() {
        ProjectionWriteFailingTaskShellStore failingStorage = new ProjectionWriteFailingTaskShellStore();
        ProjectionAwareTaskManager manager = new ProjectionAwareTaskManager(
                failingStorage,
                failingStorage,
                new InMemoryTaskWorkRuntime()
        );

        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setSourceRef("snapshot-runtime-total-truncation");
        dto.setProject("demoApp");
        dto.setUserId("agent");
        dto.setSealIntakeAfterCreate(false);
        dto.setContract(TaskContract.SESSION);
        dto.setInputs(List.of(java.util.Map.<String, Object>of("target", "stored")));

        Task task = createTask(manager, dto);
        manager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        manager.updateTask(task);

        failingStorage.failNextTaskMessageAdd();
        manager.ingestRuntimePayloadRef(
                task.getTid(),
                java.util.UUID.randomUUID().toString(),
                "s3://bucket/payloads/runtime-total-only.json",
                1
        );

        ProjectionTestSupport.MessageSnapshot limitOneSnapshot = manager.getTaskMessageSnapshot(task.getTid(), 1);
        ProjectionTestSupport.MessageSnapshot zeroSnapshot = manager.getTaskMessageSnapshot(task.getTid(), 0);

        assertEquals(1, limitOneSnapshot.messages().size());
        assertTrue(limitOneSnapshot.truncated(),
                "snapshot truncation should follow runtime total work count even when one compatibility row is missing");
        assertEquals(0, zeroSnapshot.messages().size());
        assertTrue(zeroSnapshot.truncated(),
                "zero-limit compatibility snapshot should still report truncation from runtime total work count");
    }

    @Test
    void runtimeRetryBudgetWinsOverStaleTaskMessageProjection() {
        Task task = createTask(buildRequest("task-runtime-retry-budget-owner", List.of("alpha"), 1));
        taskManager.approveTask(task.getTid());
        task.setStatus(TaskStatus.RUNNING);
        taskManager.updateTask(task);

        TaskDetailStore.TaskMessageProjection storedMessage = taskManager.getTaskMessageRecords(task.getTid()).get(0);
        TaskDetailStore.TaskMessageProjection staleProjection =
                ProjectionTestSupport.withMaxRetryCount(storedMessage, 0);
        assertTrue(taskManager.upsertTaskMessageProjectionRecord(
                task.getTid(),
                staleProjection
        ));
        assignMessage(task, staleProjection);

        assertTrue(taskManager.ingestTaskResult(task.getTid(), storedMessage.messageId(), false, "boom-once"));

        TaskDetailStore.TaskMessageProjection retriedMessage = awaitVisibleTaskMessageProjection(
                taskManager,
                task.getTid(),
                storedMessage.messageId(),
                TaskMessageProjectionStatus.INIT
        );
        assertEquals(TaskMessageProjectionStatus.INIT, retriedMessage.status());
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
        if (!managedTaskManagers.contains(manager)) {
            managedTaskManagers.add(manager);
        }
        TaskContract contract = request.getContract() != null ? request.getContract() : TaskContract.BATCH;
        Task task = manager.createTaskShell(request.toShellRequest(contract));
        if (request.getInputs() != null && !request.getInputs().isEmpty()) {
            manager.appendTaskItems(task.getTid(), request.getInputs());
        }
        if (!request.shouldKeepIntakeOpen(contract)) {
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

    private TaskCreateSpec buildRequest(String taskName, List<String> targets, int defaultMaxRetryCount) {
        TaskCreateSpec dto = new TaskCreateSpec();
        dto.setSourceRef(taskName);
        dto.setProject("demoApp");
        dto.setSharedConfig(java.util.Map.of("textContent", "smoke", "routingCode", "us"));
        dto.setUserId("agent");
        dto.setInputs(targets.stream()
                .map(target -> java.util.Map.<String, Object>of("target", target))
                .toList());
        dto.setBatchSize(1);
        dto.setDefaultMaxRetryCount(defaultMaxRetryCount);
        return dto;
    }

    private static final class TaskCreateSpec extends TaskShellCreateRequestDto {
        private java.util.List<java.util.Map<String, Object>> inputs;
        private Boolean sealIntakeAfterCreate;

        java.util.List<java.util.Map<String, Object>> getInputs() {
            return inputs;
        }

        void setInputs(java.util.List<java.util.Map<String, Object>> inputs) {
            this.inputs = inputs;
        }

        boolean shouldKeepIntakeOpen(TaskContract contract) {
            if (sealIntakeAfterCreate != null) {
                return !sealIntakeAfterCreate;
            }
            return contract == TaskContract.SESSION;
        }

        void setSealIntakeAfterCreate(boolean sealIntakeAfterCreate) {
            this.sealIntakeAfterCreate = sealIntakeAfterCreate;
        }

        void setWorkloadClass(TaskWorkloadClass workloadClass) {
            TaskExecutionSpec executionSpec = TaskExecutionSpec.normalized(getExecutionSpec());
            executionSpec.setWorkloadClass(workloadClass);
            setExecutionSpec(executionSpec);
        }

        void setBatchSize(int batchSize) {
            TaskExecutionSpec executionSpec = TaskExecutionSpec.normalized(getExecutionSpec());
            executionSpec.setBatchSize(batchSize);
            setExecutionSpec(executionSpec);
        }

        void setDefaultMaxRetryCount(int defaultMaxRetryCount) {
            TaskExecutionSpec executionSpec = TaskExecutionSpec.normalized(getExecutionSpec());
            executionSpec.setDefaultMaxRetryCount(defaultMaxRetryCount);
            setExecutionSpec(executionSpec);
        }

        TaskShellCreateRequestDto toShellRequest(TaskContract contract) {
            TaskShellCreateRequestDto dto = new TaskShellCreateRequestDto();
            dto.setUserId(getUserId());
            dto.setProject(getProject());
            dto.setSharedConfig(getSharedConfig());
            dto.setContract(contract);
            dto.setExecutionSpec(TaskExecutionSpec.normalized(getExecutionSpec()));
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
                                                                String batchId) {
        return assignMessage(taskManager, task, message, workerId, batchId);
    }

    private TaskDetailStore.TaskMessageProjection assignMessage(ProjectionAwareTaskManager manager,
                                                                Task task,
                                                                TaskDetailStore.TaskMessageProjection message) {
        String suffix = message.messageId() != null ? message.messageId() : "msg";
        return assignMessage(manager, task, message,
                "worker-" + suffix,
                "batch-" + message.retryCount());
    }

    private TaskDetailStore.TaskMessageProjection assignMessage(ProjectionAwareTaskManager manager,
                                                                Task task,
                                                                TaskDetailStore.TaskMessageProjection message,
                                                                String workerId,
                                                                String batchId) {
        if (manager.getTaskWorkRuntime().getActiveLease(task.getTid(), message.messageId()).isEmpty()) {
            List<ClaimedTaskWork> claimed = manager.getTaskWorkRuntime().claimReady(
                    task.getTid(),
                    List.of(WorkerClaimTarget.workerLevel(workerId, batchId, 1)),
                    1,
                    manager.getWorkLeaseSeconds()
            );
            if (!claimed.isEmpty() && !message.messageId().equals(claimed.get(0).messageId())) {
                // Some projection-only tests intentionally assign a later message.
                // The hot-path tests assign FIFO and get a matching runtime lease.
            }
        }
        TaskDetailStore.TaskMessageProjection assignedProjection = ProjectionTestSupport.markAssigned(
                message,
                TaskWorkAttemptIdSupport.workerLevelRuntimeAttemptId(
                        message.messageId(),
                        message.retryCount() + 1,
                        workerId,
                        batchId
                ),
                workerId,
                batchId
        );
        assertTrue(manager.upsertTaskMessageProjectionRecord(task.getTid(), assignedProjection));

        int attemptNo = message.retryCount() + 1;
        TaskDetailStore.TaskMessageAttemptProjection attempt = ProjectionTestSupport.attempt(
                TaskWorkAttemptIdSupport.workerLevelRuntimeAttemptId(
                        message.messageId(),
                        attemptNo,
                        workerId,
                        batchId
                ),
                task.getTid(),
                message.messageId(),
                attemptNo,
                workerId,
                batchId,
                TaskMessageAttemptProjectionStatus.DISPATCHED
        );
        assertTrue(manager.upsertTaskMessageAttemptAuditProjectionRecord(
                task.getTid(),
                message.messageId(),
                attempt
        ));
        return assignedProjection;
    }

    private TaskDetailStore.TaskMessageProjection assignRunningMessage(Task task,
                                                                       TaskDetailStore.TaskMessageProjection message) {
        TaskDetailStore.TaskMessageProjection assigned = assignMessage(task, message);
        TaskDetailStore.TaskMessageProjection runningProjection = ProjectionTestSupport.markRunning(assigned);
        assertTrue(taskManager.upsertTaskMessageProjectionRecord(task.getTid(), runningProjection));
        TaskDetailStore.TaskMessageAttemptProjection activeAttemptRecord =
                taskManager.getLatestActiveAttemptProjectionRecord(task.getTid(), assigned.messageId());
        assertNotNull(activeAttemptRecord);
        TaskDetailStore.TaskMessageAttemptProjection activeAttempt = activeAttemptRecord.status() == TaskMessageAttemptProjectionStatus.RUNNING
                ? activeAttemptRecord
                : ProjectionTestSupport.withAttemptStatus(
                        activeAttemptRecord,
                        TaskMessageAttemptProjectionStatus.RUNNING,
                        activeAttemptRecord.finalReason(),
                        activeAttemptRecord.errorMessage(),
                        activeAttemptRecord.errorCode(),
                        activeAttemptRecord.output()
                );
        assertTrue(taskManager.upsertTaskMessageAttemptAuditProjectionRecord(
                task.getTid(),
                assigned.messageId(),
                activeAttempt
        ));
        return runningProjection;
    }

    private static void restoreProperty(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previousValue);
        }
    }

    private static List<TaskDetailStore.TaskMessageAttemptProjection> awaitAttemptProjectionHistory(
            TaskDetailStore taskDetailStore,
            String taskId,
            String messageId,
            java.util.function.Predicate<List<TaskDetailStore.TaskMessageAttemptProjection>> condition) {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        List<TaskDetailStore.TaskMessageAttemptProjection> lastSeen = List.of();
        while (System.nanoTime() < deadlineNanos) {
            lastSeen = taskDetailStore.getTaskMessageAttemptProjections(taskId, messageId);
            if (condition.test(lastSeen)) {
                return lastSeen;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return lastSeen;
    }

    private static void awaitCondition(BooleanSupplier condition, String failureMessage) {
        awaitCondition(condition, failureMessage, 2, TimeUnit.SECONDS);
    }

    private static void awaitCondition(BooleanSupplier condition,
                                       String failureMessage,
                                       long timeout,
                                       TimeUnit unit) {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadlineNanos) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean(), failureMessage);
    }

    private static void waitForNoAdditionalDispatchEvents(AtomicInteger dispatchEvents,
                                                          long quietPeriod,
                                                          TimeUnit unit) {
        long deadlineNanos = System.nanoTime() + unit.toNanos(quietPeriod);
        int lastSeen = dispatchEvents.get();
        while (System.nanoTime() < deadlineNanos) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            int current = dispatchEvents.get();
            assertEquals(lastSeen, current, "delayed retry wakeup should be coalesced per task");
        }
    }

    private static final class PagingAwareTaskShellStore extends InMemoryTaskShellStore {
        private final AtomicInteger fullSnapshotReadCount = new AtomicInteger();
        private final AtomicInteger attemptSnapshotReadCount = new AtomicInteger();
        private final AtomicInteger attemptStatsReadCount = new AtomicInteger();

        @Override
        public TaskMessageStats getTaskMessageStats(String taskId) {
            fullSnapshotReadCount.incrementAndGet();
            return super.getTaskMessageStats(taskId);
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

    private static final class TrackingLatestAttemptStorage extends InMemoryTaskShellStore {
        private final AtomicInteger latestAttemptReadCount = new AtomicInteger();

        @Override
        public java.util.Optional<TaskDetailStore.TaskMessageAttemptProjection> getLatestTaskMessageAttemptProjection(String taskId,
                                                                                                                     String messageId) {
            latestAttemptReadCount.incrementAndGet();
            return super.getLatestTaskMessageAttemptProjection(taskId, messageId);
        }
    }

    private static final class TrackingTaskMessageProjectionStorage extends InMemoryTaskShellStore {
        private final AtomicInteger taskMessageProjectionReadCount = new AtomicInteger();

        @Override
        public java.util.Optional<TaskDetailStore.TaskMessageProjection> getTaskMessageProjection(String taskId,
                                                                                                  String messageId) {
            taskMessageProjectionReadCount.incrementAndGet();
            return super.getTaskMessageProjection(taskId, messageId);
        }
    }

    private static final class ProjectionWriteFailingTaskShellStore extends InMemoryTaskShellStore {
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
