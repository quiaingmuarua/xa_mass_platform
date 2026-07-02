package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.engine.policy.ContractAwareTaskTerminalPolicy;
import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;
import com.xa.mass.task.runtime.ClaimedWorkItem;
import com.xa.mass.task.runtime.FinalResultRow;
import com.xa.mass.task.runtime.FinalResultWindow;
import com.xa.mass.task.runtime.ResultApplySource;
import com.xa.mass.task.runtime.memory.InMemoryTaskRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskResultRuntimeConvergenceTest {

    private TaskManager taskManager;
    private TaskRuntimeServingLane taskRuntimeServingLane;

    @BeforeEach
    void setUp() {
        Harness harness = servingLaneTaskManager();
        taskManager = harness.manager();
        taskRuntimeServingLane = harness.lane();
    }

    @AfterEach
    void tearDown() {
        taskManager.shutdown();
    }

    @Test
    void visibleRuntimeResultCommitIsIdempotentForDuplicateCallbacks() {
        Task task = createRunningSingleItemTask(taskManager, "task-result-runtime-visible", 0);
        ClaimedWorkItem claimed = claimSingle(taskManager, task.getTid(), "worker-visible", "batch-visible");

        AtomicInteger logicalFinalEvents = new AtomicInteger();
        taskManager.events().addTaskWorkLogicallyFinalListener((currentTask, event) ->
                logicalFinalEvents.incrementAndGet());

        assertTrue(taskRuntimeServingLane.ingestTaskResult(
                task.getTid(),
                claimed.messageId(),
                true,
                "done",
                null,
                Map.of("value", "ok")));

        FinalResultWindow window = taskRuntimeServingLane.readTaskResults(task.getTid(), 0, 10);
        assertEquals(1, window.rows().size());
        FinalResultRow row = window.rows().getFirst();
        assertEquals(claimed.messageId(), row.messageId());
        assertEquals(1L, row.seq());
        assertTrue(row.success());
        assertEquals(ResultApplySource.WORKER_RESULT, row.source());
        assertEquals(Map.of("value", "ok"), row.resultPayloadJson());
        assertEquals(1, logicalFinalEvents.get());

        assertTrue(taskRuntimeServingLane.ingestTaskResult(
                task.getTid(),
                claimed.messageId(),
                false,
                "late-duplicate",
                null,
                null));

        FinalResultWindow afterDuplicate = taskRuntimeServingLane.readTaskResults(task.getTid(), 0, 10);
        assertEquals(1, afterDuplicate.rows().size());
        assertEquals(1, logicalFinalEvents.get());
    }

    @Test
    void retryableFailureReturnsToSchedulerWithoutCreatingVisibleResultRow() {
        Task task = createRunningSingleItemTask(taskManager, "task-result-runtime-retry", 1);
        ClaimedWorkItem firstAttempt = claimSingle(taskManager, task.getTid(), "worker-retry", "batch-retry-1");

        AtomicInteger attemptClosedEvents = new AtomicInteger();
        AtomicInteger logicalFinalEvents = new AtomicInteger();
        taskManager.events().addTaskWorkAttemptClosedListener((currentTask, attempt) ->
                attemptClosedEvents.incrementAndGet());
        taskManager.events().addTaskWorkLogicallyFinalListener((currentTask, event) ->
                logicalFinalEvents.incrementAndGet());

        assertTrue(taskRuntimeServingLane.ingestTaskResult(
                task.getTid(),
                firstAttempt.messageId(),
                false,
                "boom-once",
                "BOOM",
                null));

        assertEquals(0, taskRuntimeServingLane.countVisibleTaskResults(task.getTid()));
        assertEquals(1, taskManager.getTaskRuntimeProgressSnapshot(task.getTid()).readyCount());
        assertEquals(0, taskManager.getTaskRuntimeProgressSnapshot(task.getTid()).activeCount());
        assertEquals(TaskStatus.RUNNING, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(List.of(task.getTid()), taskRuntimeServingLane.getRuntimeDispatchableTasks(10)
                .stream()
                .map(Task::getTid)
                .toList());

        ClaimedWorkItem retryAttempt = claimSingle(taskManager, task.getTid(), "worker-retry", "batch-retry-2");
        assertEquals(firstAttempt.messageId(), retryAttempt.messageId());
        assertNotEquals(firstAttempt.leaseToken(), retryAttempt.leaseToken());
        assertEquals(1, retryAttempt.attemptNo() - 1);

        assertTrue(taskRuntimeServingLane.ingestTaskResult(
                task.getTid(),
                retryAttempt.messageId(),
                true,
                "done-after-retry",
                null,
                Map.of("value", "ok-after-retry")));

        FinalResultWindow window = taskRuntimeServingLane.readTaskResults(task.getTid(), 0, 10);
        assertEquals(1, window.rows().size());
        FinalResultRow row = window.rows().getFirst();
        assertEquals(retryAttempt.messageId(), row.messageId());
        assertEquals(1, row.attemptNo() - 1);
        assertTrue(row.success());
        assertEquals(TaskStatus.TERMINAL, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, taskManager.getTask(task.getTid()).getTerminalReason());
        assertEquals(2, attemptClosedEvents.get());
        assertEquals(1, logicalFinalEvents.get());
    }

    @Test
    void nonRetryableFailureCreatesSingleFailedFinalRow() {
        Task task = createRunningSingleItemTask(taskManager, "task-result-runtime-failed-final", 0);
        ClaimedWorkItem claimed = claimSingle(taskManager, task.getTid(), "worker-failed", "batch-failed");

        assertTrue(taskRuntimeServingLane.ingestTaskResult(
                task.getTid(),
                claimed.messageId(),
                false,
                "boom-final",
                "BOOM",
                null));

        FinalResultWindow window = taskRuntimeServingLane.readTaskResults(task.getTid(), 0, 10);
        assertEquals(1, window.rows().size());
        FinalResultRow row = window.rows().getFirst();
        assertEquals(claimed.messageId(), row.messageId());
        assertFalse(row.success());
        assertEquals(ResultApplySource.WORKER_RESULT, row.source());
        assertEquals("boom-final", row.failureReason());
        assertEquals("BOOM", row.resultPayloadJson().get("errorCode"));
        assertEquals(TaskStatus.TERMINAL, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_FAILED, taskManager.getTask(task.getTid()).getTerminalReason());
        assertFalse(taskRuntimeServingLane.getResultCorrelation(task.getTid(), claimed.messageId()).activeLeasePresent());
    }

    @Test
    void expiredLeaseConvergesThroughTaskRuntimeRepairPortAndFinalRead() {
        Task task = createRunningSingleItemTask(taskManager, "task-result-runtime-lease-timeout", 0);
        ClaimedWorkItem claimed = claimSingle(taskManager, task.getTid(), "worker-timeout", "batch-timeout");

        List<ActiveLeaseRepairCandidate> expired = taskRuntimeServingLane.pollExpiredLeases(
                10,
                Instant.ofEpochMilli(claimed.leaseExpireAtMillis() + 1));
        assertEquals(List.of(claimed.messageId()), expired.stream().map(ActiveLeaseRepairCandidate::messageId).toList());

        assertTrue(taskRuntimeServingLane.expireLeasedWork(task.getTid(), claimed.messageId()));

        FinalResultWindow window = taskRuntimeServingLane.readTaskResults(task.getTid(), 0, 10);
        assertEquals(1, window.rows().size());
        FinalResultRow row = window.rows().getFirst();
        assertEquals(claimed.messageId(), row.messageId());
        assertFalse(row.success());
        assertEquals(ResultApplySource.LEASE_TIMEOUT, row.source());
        assertEquals(TaskStatus.TERMINAL, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_FAILED, taskManager.getTask(task.getTid()).getTerminalReason());
        assertTrue(taskRuntimeServingLane.pollExpiredLeases(10, Instant.now()).isEmpty());
    }

    private static Harness servingLaneTaskManager() {
        InMemoryTaskRuntime runtime = new InMemoryTaskRuntime();
        InMemoryTaskShellRuntimeStore storage = new InMemoryTaskShellRuntimeStore();
        TaskManager manager = new TaskManager(
                storage,
                storage,
                new ContractAwareTaskTerminalPolicy(),
                null);
        var commands = new TaskCommandService(manager);
        var queries = new TaskQueryService(manager);
        var events = new TaskEventService(manager);
        var lane = new TaskRuntimeServingLane(
                runtime,
                runtime,
                runtime,
                runtime,
                runtime,
                queries,
                commands,
                events,
                300L,
                TaskManager.MAX_INGEST_BATCH_ITEMS,
                86_400_000L);
        manager.installTaskRuntimeServingLane(lane);
        return new Harness(manager, lane);
    }

    private static Task createRunningSingleItemTask(TaskManager manager,
                                                    String sourceRef,
                                                    int defaultMaxRetryCount) {
        TaskShellCreateRequestDto request = new TaskShellCreateRequestDto();
        request.setSourceRef(sourceRef);
        request.setProject("demoApp");
        request.setContract(TaskContract.BATCH);
        request.setSharedConfig(Map.of(TaskSharedConfig.WORKER_GROUP_ID, "group-1"));
        request.setUserId("agent");
        request.setExecutionSpec(taskExecutionSpec(defaultMaxRetryCount));

        Task task = manager.createTaskShell(request);
        assertEquals(1, manager.appendTaskItems(task.getTid(), List.of(Map.of("target", "alpha"))));
        assertTrue(manager.sealTask(task.getTid()));
        assertTrue(manager.approveTask(task.getTid()));
        Task running = manager.getTask(task.getTid());
        running.setStatus(TaskStatus.RUNNING);
        assertTrue(manager.updateTask(running));
        return manager.getTask(task.getTid());
    }

    private ClaimedWorkItem claimSingle(TaskManager manager,
                                        String taskId,
                                        String workerId,
                                        String batchId) {
        return TaskRuntimeClaimTestSupport.claimSingle(
                taskRuntimeServingLane,
                manager.getWorkLeaseSeconds(),
                taskId,
                "group-1",
                workerId,
                batchId);
    }

    private static TaskExecutionSpec taskExecutionSpec(int defaultMaxRetryCount) {
        TaskExecutionSpec spec = new TaskExecutionSpec();
        spec.setBatchSize(1);
        spec.setDefaultMaxRetryCount(defaultMaxRetryCount);
        return spec;
    }

    private record Harness(TaskManager manager, TaskRuntimeServingLane lane) {
    }

}
