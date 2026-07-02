package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskHoldReason;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.engine.model.TaskAppendOutcome;
import com.xa.mass.engine.model.TaskCommandOutcome;
import com.xa.mass.engine.policy.ContractAwareTaskTerminalPolicy;
import com.xa.mass.task.runtime.ClaimedWorkItem;
import com.xa.mass.task.runtime.memory.InMemoryTaskRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskKernelLifecycleTest {

    private Harness harness;

    @BeforeEach
    void setUp() {
        harness = new Harness();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void createBatchShellDefaultsContractWorkloadAndSealedIntake() {
        Task task = createTask(buildRequest("task-create", List.of("alpha", "beta"), 3));

        assertEquals(TaskStatus.NEW, task.getStatus());
        assertEquals(TaskContract.BATCH, task.getContract());
        assertEquals(TaskWorkloadClass.BULK, task.getExecutionSpec().getWorkloadClass());
        assertEquals(TaskIntakeStatus.SEALED, task.getIntakeStatus());
        assertEquals(2, task.getTaskTargetNumber());
        assertEquals(2, task.getTaskEligibleNumber());
        assertEquals(2, harness.taskManager.getTaskRuntimeProgressSnapshot(task.getTid()).readyCount());
    }

    @Test
    void createSessionShellDefaultsInteractiveWorkloadAndOpenIntake() {
        TaskCreateSpec request = buildRequest("task-session", List.of(), 3);
        request.setContract(TaskContract.SESSION);
        request.setSealIntakeAfterCreate(false);

        Task task = createTask(request);

        assertEquals(TaskStatus.NEW, task.getStatus());
        assertEquals(TaskContract.SESSION, task.getContract());
        assertEquals(TaskWorkloadClass.INTERACTIVE, task.getExecutionSpec().getWorkloadClass());
        assertEquals(TaskIntakeStatus.OPEN, task.getIntakeStatus());
        assertEquals(0, task.getTaskTargetNumber());
        assertEquals(0, harness.taskManager.getTaskRuntimeProgressSnapshot(task.getTid()).readyCount());
    }

    @Test
    void createTaskDoesNotInferContractFromInteractiveWorkload() {
        TaskCreateSpec request = buildRequest("task-workload-not-contract", List.of("alpha", "beta"), 3);
        request.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);

        Task task = createTask(request);

        assertEquals(TaskContract.BATCH, task.getContract());
        assertEquals(TaskWorkloadClass.INTERACTIVE, task.getExecutionSpec().getWorkloadClass());
        assertEquals(TaskIntakeStatus.SEALED, task.getIntakeStatus());
    }

    @Test
    void taskCanMoveFromNewToReadyToPausedAndBackToReady() {
        Task task = createTask(buildRequest("task-lifecycle", List.of("alpha"), 3));

        assertEquals(List.of(), dispatchableTaskIds());

        assertTrue(harness.taskManager.approveTask(task.getTid()).accepted());
        assertEquals(TaskStatus.READY, harness.taskManager.getTask(task.getTid()).getStatus());
        assertEquals(List.of(task.getTid()), dispatchableTaskIds());

        assertTrue(harness.taskManager.pauseTask(task.getTid()).accepted());
        assertEquals(TaskStatus.PAUSED, harness.taskManager.getTask(task.getTid()).getStatus());
        assertEquals(List.of(), dispatchableTaskIds());

        assertTrue(harness.taskManager.resumeTask(task.getTid()).accepted());
        assertEquals(TaskStatus.READY, harness.taskManager.getTask(task.getTid()).getStatus());
        assertEquals(List.of(task.getTid()), dispatchableTaskIds());
    }

    @Test
    void blockedTaskCanBeApprovedBackToReady() {
        Task task = createTask(buildRequest("task-blocked", List.of("alpha"), 3));

        assertTrue(harness.taskManager.rejectTask(task.getTid()).accepted());
        assertEquals(TaskStatus.BLOCKED, harness.taskManager.getTask(task.getTid()).getStatus());
        assertEquals(TaskHoldReason.REVIEW_REJECTED, harness.taskManager.getTask(task.getTid()).getHoldReason());
        assertEquals(List.of(), dispatchableTaskIds());

        assertTrue(harness.taskManager.approveTask(task.getTid()).accepted());
        assertEquals(TaskStatus.READY, harness.taskManager.getTask(task.getTid()).getStatus());
        assertNull(harness.taskManager.getTask(task.getTid()).getHoldReason());
        assertEquals(List.of(task.getTid()), dispatchableTaskIds());
    }

    @Test
    void invalidActionsAreRejectedOutsideExpectedStates() {
        Task task = createTask(buildRequest("task-invalid", List.of("alpha"), 3));

        assertFalse(harness.taskManager.pauseTask(task.getTid()).accepted());
        assertFalse(harness.taskManager.resumeTask(task.getTid()).accepted());

        assertTrue(harness.taskManager.approveTask(task.getTid()).accepted());
        assertFalse(harness.taskManager.rejectTask(task.getTid()).accepted());
        assertTrue(harness.taskManager.approveTask(task.getTid()).accepted());

        assertTrue(harness.taskManager.cancelTask(task.getTid()).accepted());
        assertEquals(TaskStatus.TERMINAL, harness.taskManager.getTask(task.getTid()).getStatus());
        assertEquals(TaskTerminalReason.MANUAL_CANCELLED, harness.taskManager.getTask(task.getTid()).getTerminalReason());
        assertEquals(0, harness.taskManager.getTaskRuntimeProgressSnapshot(task.getTid()).readyCount());
        assertFalse(harness.taskManager.resumeTask(task.getTid()).accepted());
    }

    @Test
    void batchTaskCanIngestItemsBeforeApprovalWithoutDispatch() {
        TaskCreateSpec request = buildRequest("file-ingest-before-approval", List.of(), 3);
        request.setContract(TaskContract.BATCH);
        request.setSealIntakeAfterCreate(false);

        Task task = createTask(request);
        AtomicInteger dispatchRequests = new AtomicInteger();
        harness.taskManager.events().addTaskDispatchListener(ignored -> dispatchRequests.incrementAndGet());

        TaskAppendOutcome append = harness.taskManager.appendTaskItems(task.getTid(), List.of(
                Map.<String, Object>of("target", "alpha"),
                Map.<String, Object>of("target", "beta")
        ));

        Task updatedTask = harness.taskManager.getTask(task.getTid());
        assertEquals(2, append.acceptedCount());
        assertEquals(TaskStatus.NEW, updatedTask.getStatus());
        assertEquals(TaskIntakeStatus.OPEN, updatedTask.getIntakeStatus());
        assertEquals(2, updatedTask.getTaskTargetNumber());
        assertEquals(2, updatedTask.getTaskEligibleNumber());
        assertEquals(2, harness.taskManager.getTaskRuntimeProgressSnapshot(task.getTid()).readyCount());
        assertEquals(0, dispatchRequests.get());
    }

    @Test
    void appendTaskItemsRejectsOversizedIngestBatch() {
        TaskCreateSpec request = buildRequest("stream-shell-ingest-limit", List.of(), 3);
        request.setContract(TaskContract.SESSION);
        request.setSealIntakeAfterCreate(false);
        Task task = createTask(request);

        List<Map<String, Object>> oversizedBatch = java.util.stream.IntStream
                .rangeClosed(0, TaskManager.MAX_INGEST_BATCH_ITEMS)
                .mapToObj(i -> Map.<String, Object>of("target", "t-" + i))
                .toList();

        TaskAppendOutcome append = harness.taskManager.appendTaskItems(task.getTid(), oversizedBatch);
        assertFalse(append.accepted());
        assertTrue(append.message().contains("append items exceed ingest batch limit"));
    }

    @Test
    void interactiveTaskAppendRespectsWorkloadAwareReadyBackpressureCap() {
        String previousInteractiveCap = System.getProperty("xa.mass.engine.interactiveMaxReadyItemsPerTask");
        String previousBulkCap = System.getProperty("xa.mass.engine.bulkMaxReadyItemsPerTask");
        harness.close();
        harness = null;
        try {
            System.setProperty("xa.mass.engine.interactiveMaxReadyItemsPerTask", "2");
            System.setProperty("xa.mass.engine.bulkMaxReadyItemsPerTask", "100");
            harness = new Harness();
            TaskCreateSpec request = buildRequest("interactive-backpressure", List.of("alpha"), 3);
            request.setContract(TaskContract.SESSION);
            request.setSealIntakeAfterCreate(false);
            request.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);

            Task task = createTask(request);
            assertTrue(harness.taskManager.approveTask(task.getTid()).accepted());
            assertEquals(1, harness.taskManager.getTaskRuntimeProgressSnapshot(task.getTid()).readyCount());

            assertEquals(1, harness.taskManager.appendTaskItems(task.getTid(), List.of(
                    Map.<String, Object>of("target", "beta")
            )).acceptedCount());

            IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                    harness.taskManager.appendTaskItems(task.getTid(), List.of(
                            Map.<String, Object>of("target", "gamma")
                    )));

            assertTrue(error.getMessage().contains("task-runtime append failed"));
            assertTrue(error.getMessage().contains("ready backlog is full"));
            assertEquals(2, harness.taskManager.getTaskRuntimeProgressSnapshot(task.getTid()).readyCount());
            assertEquals(2, harness.taskManager.getTask(task.getTid()).getTaskTargetNumber());
        } finally {
            restoreProperty("xa.mass.engine.interactiveMaxReadyItemsPerTask", previousInteractiveCap);
            restoreProperty("xa.mass.engine.bulkMaxReadyItemsPerTask", previousBulkCap);
        }
    }

    @Test
    void appendTaskItemsRejectsBeforeRuntimeOwnershipWhenTaskReadyBacklogWouldOverflow() {
        String previousBulkCap = System.getProperty("xa.mass.engine.bulkMaxReadyItemsPerTask");
        harness.close();
        harness = null;
        try {
            System.setProperty("xa.mass.engine.bulkMaxReadyItemsPerTask", "2");
            harness = new Harness();
            TaskCreateSpec request = buildRequest("append-atomic-backlog", List.of("alpha"), 3);
            request.setContract(TaskContract.BATCH);
            request.setSealIntakeAfterCreate(false);

            Task task = createTask(request);
            assertEquals(1, harness.taskManager.getTaskRuntimeProgressSnapshot(task.getTid()).readyCount());
            assertEquals(1, harness.taskManager.getTask(task.getTid()).getTaskTargetNumber());

            IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                    harness.taskManager.appendTaskItems(task.getTid(), List.of(
                            Map.<String, Object>of("target", "beta"),
                            Map.<String, Object>of("target", "gamma")
                    )));

            assertTrue(error.getMessage().contains("task-runtime append failed"));
            assertTrue(error.getMessage().contains("ready backlog is full"));
            assertEquals(1, harness.taskManager.getTaskRuntimeProgressSnapshot(task.getTid()).readyCount());
            assertEquals(1, harness.taskManager.getTask(task.getTid()).getTaskTargetNumber());
        } finally {
            restoreProperty("xa.mass.engine.bulkMaxReadyItemsPerTask", previousBulkCap);
        }
    }

    @Test
    void deleteTaskRejectedForReadyTask() {
        Task task = createTask(buildRequest("del-ready", List.of("alpha"), 3));
        assertTrue(harness.taskManager.approveTask(task.getTid()).accepted());

        assertFalse(harness.taskManager.deleteTask(task.getTid()).accepted());
        assertNotNull(harness.taskManager.getTask(task.getTid()));
    }

    @Test
    void deleteTaskAllowedForNewTask() {
        Task task = createTask(buildRequest("del-new", List.of("alpha"), 3));
        assertTrue(harness.taskRuntimeServingLane.hasDispatchReadyWork(task.getTid()));

        assertTrue(harness.taskManager.deleteTask(task.getTid()).accepted());
        assertNull(harness.taskManager.getTask(task.getTid()));
        assertFalse(harness.taskRuntimeServingLane.hasDispatchReadyWork(task.getTid()));
    }

    @Test
    void deleteTaskAllowedForTerminalTask() {
        Task task = createTask(buildRequest("del-terminal", List.of("alpha"), 3));
        assertTrue(harness.taskManager.approveTask(task.getTid()).accepted());
        assertTrue(harness.taskManager.cancelTask(task.getTid()).accepted());

        assertTrue(harness.taskManager.deleteTask(task.getTid()).accepted());
        assertNull(harness.taskManager.getTask(task.getTid()));
    }

    @Test
    void policyTerminationPreservesVisibleResultRowsUntilTaskDelete() {
        Task task = createTask(buildRequest("terminate-preserve-results", List.of("alpha", "beta"), 0));
        assertTrue(harness.taskManager.approveTask(task.getTid()).accepted());
        Task running = harness.taskManager.getTask(task.getTid());
        running.setStatus(TaskStatus.RUNNING);
        assertTrue(harness.taskManager.persistTaskShell(running));

        ClaimedWorkItem claimed = claimSingle(task.getTid(), "worker-results", "batch-results");
        assertTrue(harness.taskRuntimeServingLane.ingestTaskResult(
                task.getTid(),
                claimed.messageId(),
                true,
                "done",
                null,
                Map.of("value", "ok")
        ));
        assertEquals(1, harness.taskRuntimeServingLane.countVisibleTaskResults(task.getTid()));
        assertTrue(harness.taskRuntimeServingLane.hasDispatchReadyWork(task.getTid()));

        assertTrue(harness.taskManager.terminateTask(task.getTid(), TaskTerminalReason.MAX_RUNTIME_REACHED).accepted());

        Task terminal = harness.taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, terminal.getStatus());
        assertEquals(TaskTerminalReason.MAX_RUNTIME_REACHED, terminal.getTerminalReason());
        assertFalse(harness.taskRuntimeServingLane.hasDispatchReadyWork(task.getTid()));
        assertEquals(1, harness.taskRuntimeServingLane.countVisibleTaskResults(task.getTid()));

        assertTrue(harness.taskManager.deleteTask(task.getTid()).accepted());
        assertEquals(0, harness.taskRuntimeServingLane.countVisibleTaskResults(task.getTid()));
    }

    private Task createTask(TaskCreateSpec request) {
        if (request == null) {
            throw new IllegalArgumentException("task request body is required");
        }
        TaskContract contract = request.getContract() != null ? request.getContract() : TaskContract.BATCH;
        TaskCommandOutcome create = harness.taskManager.createTaskShell(request.toShellRequest(contract));
        assertTrue(create.accepted());
        Task task = harness.taskManager.getTask(create.taskId());
        assertNotNull(task);
        if (request.getInputs() != null && !request.getInputs().isEmpty()) {
            harness.taskManager.appendTaskItems(task.getTid(), request.getInputs());
        }
        if (!request.shouldKeepIntakeOpen(contract)) {
            assertTrue(harness.taskManager.sealTask(task.getTid()).accepted());
        }
        return harness.taskManager.getTask(task.getTid());
    }

    private ClaimedWorkItem claimSingle(String taskId, String workerId, String batchId) {
        return TaskRuntimeClaimTestSupport.claimSingle(
                harness.taskRuntimeServingLane,
                harness.taskManager.getWorkLeaseSeconds(),
                taskId,
                "group-1",
                workerId,
                batchId);
    }

    private List<String> dispatchableTaskIds() {
        return harness.taskRuntimeServingLane.getRuntimeDispatchableTasks(10).stream()
                .map(Task::getTid)
                .toList();
    }

    private TaskCreateSpec buildRequest(String sourceRef, List<String> targets, int defaultMaxRetryCount) {
        TaskCreateSpec request = new TaskCreateSpec();
        request.setSourceRef(sourceRef);
        request.setProject("demoApp");
        request.setSharedConfig(Map.of("textContent", "smoke", "routingCode", "us"));
        request.setUserId("agent");
        request.setInputs(targets.stream()
                .map(target -> Map.<String, Object>of("target", target))
                .toList());
        request.setBatchSize(1);
        request.setDefaultMaxRetryCount(defaultMaxRetryCount);
        return request;
    }

    private static void restoreProperty(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previousValue);
        }
    }

    private static final class Harness {
        private final TaskManager taskManager;
        private final TaskRuntimeServingLane taskRuntimeServingLane;

        private Harness() {
            InMemoryTaskRuntime runtime = new InMemoryTaskRuntime();
            InMemoryTaskShellRuntimeStore storage = new InMemoryTaskShellRuntimeStore();
            this.taskManager = new TaskManager(
                    storage,
                    storage,
                    new ContractAwareTaskTerminalPolicy(),
                    null);
            taskRuntimeServingLane = TaskRuntimeServingLaneTestSupport.forTaskManager(
                    runtime,
                    runtime,
                    runtime,
                    runtime,
                    runtime,
                    taskManager,
                    300L,
                    TaskManager.MAX_INGEST_BATCH_ITEMS,
                    86_400_000L);
            taskManager.installTaskRuntimeServingLane(taskRuntimeServingLane);
        }

        private void close() {
            taskManager.shutdown();
        }
    }

    private static final class TaskCreateSpec extends TaskShellCreateRequestDto {
        private List<Map<String, Object>> inputs;
        private Boolean sealIntakeAfterCreate;

        List<Map<String, Object>> getInputs() {
            return inputs;
        }

        void setInputs(List<Map<String, Object>> inputs) {
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
}
