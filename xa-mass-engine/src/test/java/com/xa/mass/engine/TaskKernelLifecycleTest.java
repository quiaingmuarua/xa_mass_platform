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
import com.xa.mass.runtime.memory.InMemoryTaskResultRuntime;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.storage.memory.InMemoryTaskStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskKernelLifecycleTest {

    private InMemoryTaskStorage taskStorage;
    private TaskManager taskManager;

    @BeforeEach
    void setUp() {
        taskStorage = new InMemoryTaskStorage();
        taskManager = new TaskManager(
                taskStorage,
                taskStorage,
                new InMemoryTaskWorkRuntime(),
                new InMemoryTaskResultRuntime(),
                null
        );
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
        assertEquals(2, taskManager.getTaskWorkRuntime().stats(task.getTid()).readyCount());
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
        assertEquals(0, taskManager.getTaskWorkRuntime().stats(task.getTid()).readyCount());
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

        assertTrue(taskManager.approveTask(task.getTid()));
        assertEquals(TaskStatus.READY, taskManager.getTask(task.getTid()).getStatus());

        assertTrue(taskManager.pauseTask(task.getTid()));
        assertEquals(TaskStatus.PAUSED, taskManager.getTask(task.getTid()).getStatus());

        assertTrue(taskManager.resumeTask(task.getTid()));
        assertEquals(TaskStatus.READY, taskManager.getTask(task.getTid()).getStatus());
    }

    @Test
    void blockedTaskCanBeApprovedBackToReady() {
        Task task = createTask(buildRequest("task-blocked", List.of("alpha"), 3));

        assertTrue(taskManager.rejectTask(task.getTid()));
        assertEquals(TaskStatus.BLOCKED, taskManager.getTask(task.getTid()).getStatus());
        assertEquals(TaskHoldReason.REVIEW_REJECTED, taskManager.getTask(task.getTid()).getHoldReason());

        assertTrue(taskManager.approveTask(task.getTid()));
        assertEquals(TaskStatus.READY, taskManager.getTask(task.getTid()).getStatus());
        assertNull(taskManager.getTask(task.getTid()).getHoldReason());
    }

    @Test
    void invalidActionsAreRejectedOutsideExpectedStates() {
        Task task = createTask(buildRequest("task-invalid", List.of("alpha"), 3));

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
    void batchTaskCanIngestItemsBeforeApprovalWithoutDispatch() {
        TaskCreateSpec request = buildRequest("file-ingest-before-approval", List.of(), 3);
        request.setContract(TaskContract.BATCH);
        request.setSealIntakeAfterCreate(false);

        Task task = createTask(request);
        AtomicInteger dispatchRequests = new AtomicInteger();
        taskManager.events().addTaskDispatchListener(ignored -> dispatchRequests.incrementAndGet());

        int added = taskManager.appendTaskItems(task.getTid(), List.of(
                java.util.Map.<String, Object>of("target", "alpha"),
                java.util.Map.<String, Object>of("target", "beta")
        ));

        Task updatedTask = taskManager.getTask(task.getTid());
        assertEquals(2, added);
        assertEquals(TaskStatus.NEW, updatedTask.getStatus());
        assertEquals(TaskIntakeStatus.OPEN, updatedTask.getIntakeStatus());
        assertEquals(2, updatedTask.getTaskTargetNumber());
        assertEquals(2, updatedTask.getTaskEligibleNumber());
        assertEquals(2, taskManager.getTaskWorkRuntime().stats(task.getTid()).readyCount());
        assertEquals(0, dispatchRequests.get());
    }

    @Test
    void appendTaskItemsRejectsOversizedIngestBatch() {
        TaskCreateSpec request = buildRequest("stream-shell-ingest-limit", List.of(), 3);
        request.setContract(TaskContract.SESSION);
        request.setSealIntakeAfterCreate(false);
        Task task = createTask(request);

        List<java.util.Map<String, Object>> oversizedBatch = java.util.stream.IntStream
                .rangeClosed(0, TaskManager.MAX_INGEST_BATCH_ITEMS)
                .mapToObj(i -> java.util.Map.<String, Object>of("target", "t-" + i))
                .toList();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> taskManager.appendTaskItems(task.getTid(), oversizedBatch));

        assertTrue(error.getMessage().contains("append items exceed ingest batch limit"));
    }

    @Test
    void interactiveTaskAppendRespectsWorkloadAwareReadyBackpressureCap() {
        String previousInteractiveCap = System.getProperty("xa.mass.engine.interactiveMaxReadyItemsPerTask");
        String previousBulkCap = System.getProperty("xa.mass.engine.bulkMaxReadyItemsPerTask");
        try {
            System.setProperty("xa.mass.engine.interactiveMaxReadyItemsPerTask", "2");
            System.setProperty("xa.mass.engine.bulkMaxReadyItemsPerTask", "100");

            InMemoryTaskStorage backpressureStorage = new InMemoryTaskStorage();
            TaskManager backpressureAwareManager = new TaskManager(
                    backpressureStorage,
                    backpressureStorage,
                    new InMemoryTaskWorkRuntime(),
                    new InMemoryTaskResultRuntime(),
                    null
            );
            TaskCreateSpec request = buildRequest("interactive-backpressure", List.of("alpha"), 3);
            request.setContract(TaskContract.SESSION);
            request.setSealIntakeAfterCreate(false);
            request.setWorkloadClass(TaskWorkloadClass.INTERACTIVE);

            Task task = createTask(backpressureAwareManager, request);
            assertTrue(backpressureAwareManager.approveTask(task.getTid()));
            assertEquals(1, backpressureAwareManager.getTaskWorkRuntime().stats(task.getTid()).readyCount());

            assertEquals(1, backpressureAwareManager.appendTaskItems(task.getTid(), List.of(
                    java.util.Map.<String, Object>of("target", "beta")
            )));

            IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                    backpressureAwareManager.appendTaskItems(task.getTid(), List.of(
                            java.util.Map.<String, Object>of("target", "gamma")
                    )));

            assertTrue(error.getMessage().contains("BACKPRESSURE_REJECTED"));
            assertEquals(2, backpressureAwareManager.getTaskWorkRuntime().stats(task.getTid()).readyCount());
            assertEquals(2, backpressureAwareManager.getTask(task.getTid()).getTaskTargetNumber());
        } finally {
            restoreProperty("xa.mass.engine.interactiveMaxReadyItemsPerTask", previousInteractiveCap);
            restoreProperty("xa.mass.engine.bulkMaxReadyItemsPerTask", previousBulkCap);
        }
    }

    @Test
    void appendTaskItemsRejectsBeforeRuntimeAdmissionWhenEngineBacklogWouldOverflow() {
        InMemoryTaskStorage backlogStorage = new InMemoryTaskStorage();
        TaskManager backlogAwareManager = new TaskManager(
                backlogStorage,
                backlogStorage,
                new InMemoryTaskWorkRuntime(2),
                new InMemoryTaskResultRuntime(),
                null
        );
        TaskCreateSpec request = buildRequest("append-atomic-backlog", List.of("alpha"), 3);
        request.setContract(TaskContract.BATCH);
        request.setSealIntakeAfterCreate(false);

        Task task = createTask(backlogAwareManager, request);
        assertEquals(1, backlogAwareManager.getTaskWorkRuntime().stats(task.getTid()).readyCount());
        assertEquals(1, backlogAwareManager.getTask(task.getTid()).getTaskTargetNumber());

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                backlogAwareManager.appendTaskItems(task.getTid(), List.of(
                        java.util.Map.<String, Object>of("target", "beta"),
                        java.util.Map.<String, Object>of("target", "gamma")
                )));

        assertTrue(error.getMessage().contains("engine work backlog is full"));
        assertEquals(1, backlogAwareManager.getTaskWorkRuntime().stats(task.getTid()).readyCount());
        assertEquals(1, backlogAwareManager.getTask(task.getTid()).getTaskTargetNumber());
    }

    @Test
    void deleteTaskRejectedForReadyTask() {
        Task task = createTask(buildRequest("del-ready", List.of("alpha"), 3));
        assertTrue(taskManager.approveTask(task.getTid()));

        assertFalse(taskManager.deleteTask(task.getTid()));
        assertNotNull(taskManager.getTask(task.getTid()));
    }

    @Test
    void deleteTaskAllowedForNewTask() {
        Task task = createTask(buildRequest("del-new", List.of("alpha"), 3));
        assertTrue(taskManager.getTaskWorkRuntime().hasReadyWork(task.getTid()));

        assertTrue(taskManager.deleteTask(task.getTid()));
        assertNull(taskManager.getTask(task.getTid()));
        assertFalse(taskManager.getTaskWorkRuntime().hasReadyWork(task.getTid()));
    }

    @Test
    void deleteTaskAllowedForTerminalTask() {
        Task task = createTask(buildRequest("del-terminal", List.of("alpha"), 3));
        assertTrue(taskManager.approveTask(task.getTid()));
        assertTrue(taskManager.cancelTask(task.getTid()));

        assertTrue(taskManager.deleteTask(task.getTid()));
        assertNull(taskManager.getTask(task.getTid()));
    }

    private Task createTask(TaskCreateSpec request) {
        return createTask(taskManager, request);
    }

    private Task createTask(TaskManager manager, TaskCreateSpec request) {
        if (request == null) {
            throw new IllegalArgumentException("task request body is required");
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

    private TaskCreateSpec buildRequest(String sourceRef, List<String> targets, int defaultMaxRetryCount) {
        TaskCreateSpec request = new TaskCreateSpec();
        request.setSourceRef(sourceRef);
        request.setProject("demoApp");
        request.setSharedConfig(java.util.Map.of("textContent", "smoke", "routingCode", "us"));
        request.setUserId("agent");
        request.setInputs(targets.stream()
                .map(target -> java.util.Map.<String, Object>of("target", target))
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

    private static final class TaskCreateSpec extends TaskShellCreateRequestDto {
        private List<java.util.Map<String, Object>> inputs;
        private Boolean sealIntakeAfterCreate;

        List<java.util.Map<String, Object>> getInputs() {
            return inputs;
        }

        void setInputs(List<java.util.Map<String, Object>> inputs) {
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
