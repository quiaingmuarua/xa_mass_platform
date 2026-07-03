package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.engine.model.TaskStateResolutionResult;
import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import com.xa.mass.runtime.memory.InMemoryTaskResultRuntime;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskIdleClosePolicyBehaviorTest {

    private TaskManager taskManager;

    @BeforeEach
    void setUp() {
        InMemoryTaskShellRuntimeStore taskStorage = new InMemoryTaskShellRuntimeStore();
        taskManager = new TaskManager(
                taskStorage,
                new InMemoryTaskWorkRuntime(),
                new InMemoryTaskResultRuntime(),
                null
        );
    }

    @Test
    void batchTaskStaysNonTerminalUntilIntakeSealed() {
        TaskCreateSpec request = buildRequest("batch-open-intake", List.of());
        request.setContract(TaskContract.BATCH);
        request.setSealIntakeAfterCreate(false);

        Task task = createTask(request);
        assertEquals(TaskIntakeStatus.OPEN, task.getIntakeStatus());

        assertEquals(1, taskManager.appendTaskItems(task.getTid(), List.of(Map.of("target", "alpha"))));
        assertTrue(taskManager.approveTask(task.getTid()));
        markRunning(task.getTid());

        ClaimedTaskWork claimed = claimSingle(task.getTid(), "worker-batch");
        assertTrue(taskManager.ingestTaskResult(
                task.getTid(),
                claimed.messageId(),
                true,
                "done",
                null,
                Map.of("outcome", "success")
        ));

        Task beforeSeal = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.RUNNING, beforeSeal.getStatus());
        assertNull(beforeSeal.getTerminalReason());
        assertEquals(TaskIntakeStatus.OPEN, beforeSeal.getIntakeStatus());

        assertTrue(taskManager.sealTask(task.getTid()));

        Task sealed = taskManager.getTask(task.getTid());
        assertEquals(TaskIntakeStatus.SEALED, sealed.getIntakeStatus());
        assertEquals(TaskStatus.TERMINAL, sealed.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, sealed.getTerminalReason());
    }

    @Test
    void sessionTaskSealClosesAppendWindowWithoutTerminalClosure() {
        TaskCreateSpec request = buildRequest("session-open-intake", List.of("alpha"));
        request.setContract(TaskContract.SESSION);
        request.setSealIntakeAfterCreate(false);

        Task task = createTask(request);
        assertEquals(TaskIntakeStatus.OPEN, task.getIntakeStatus());

        assertTrue(taskManager.approveTask(task.getTid()));
        markRunning(task.getTid());

        ClaimedTaskWork claimed = claimSingle(task.getTid(), "worker-session");
        assertTrue(taskManager.ingestTaskResult(
                task.getTid(),
                claimed.messageId(),
                true,
                "done",
                null,
                Map.of("outcome", "success")
        ));

        Task beforeSeal = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.RUNNING, beforeSeal.getStatus());
        assertNull(beforeSeal.getTerminalReason());
        assertEquals(TaskIntakeStatus.OPEN, beforeSeal.getIntakeStatus());

        assertTrue(taskManager.sealTask(task.getTid()));
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                taskManager.appendTaskItems(task.getTid(), List.of(Map.of("target", "beta"))));

        assertTrue(error.getMessage().contains("sealed"));
        Task current = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.RUNNING, current.getStatus());
        assertNull(current.getTerminalReason());
        assertEquals(TaskIntakeStatus.SEALED, current.getIntakeStatus());
        assertEquals(1, current.getTaskTargetNumber());

        TaskStateResolutionResult resolution = taskManager.resolveTaskState(task.getTid());
        assertEquals(TaskStateResolutionResult.Outcome.NOT_FINALIZED, resolution.getOutcome());
        assertEquals(TaskStatus.RUNNING, resolution.getStatus());
        assertNull(resolution.getTerminalReason());
    }

    @Test
    void sessionTaskTerminalClosureClosesAppendWindow() {
        TaskCreateSpec request = buildRequest("session-terminal-close", List.of("alpha"));
        request.setContract(TaskContract.SESSION);
        request.setSealIntakeAfterCreate(false);

        Task task = createTask(request);
        assertTrue(taskManager.cancelTask(task.getTid()));

        Task terminalTask = taskManager.getTask(task.getTid());
        assertEquals(TaskStatus.TERMINAL, terminalTask.getStatus());
        assertEquals(TaskTerminalReason.MANUAL_CANCELLED, terminalTask.getTerminalReason());
        assertEquals(TaskIntakeStatus.SEALED, terminalTask.getIntakeStatus());

        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
                taskManager.appendTaskItems(task.getTid(), List.of(Map.of("target", "beta"))));
        assertTrue(error.getMessage().contains("sealed"));
    }

    private Task createTask(TaskCreateSpec request) {
        TaskContract contract = request.getContract() != null ? request.getContract() : TaskContract.BATCH;
        Task task = taskManager.createTaskShell(request.toShellRequest(contract));
        if (request.getInputs() != null && !request.getInputs().isEmpty()) {
            taskManager.appendTaskItems(task.getTid(), request.getInputs());
        }
        if (!request.shouldKeepIntakeOpen(contract)) {
            assertTrue(taskManager.sealTask(task.getTid()));
        }
        return taskManager.getTask(task.getTid());
    }

    private ClaimedTaskWork claimSingle(String taskId, String workerId) {
        List<ClaimedTaskWork> claimed = taskManager.getTaskWorkRuntime().claimReady(
                taskId,
                List.of(WorkerClaimTarget.workerLevel(workerId, "batch-0", 1)),
                1,
                taskManager.getWorkLeaseSeconds()
        );
        assertEquals(1, claimed.size());
        return claimed.getFirst();
    }

    private void markRunning(String taskId) {
        Task running = taskManager.getTask(taskId);
        running.setStatus(TaskStatus.RUNNING);
        assertTrue(taskManager.updateTask(running));
    }

    private TaskCreateSpec buildRequest(String sourceRef, List<String> targets) {
        TaskCreateSpec request = new TaskCreateSpec();
        request.setSourceRef(sourceRef);
        request.setProject("demoApp");
        request.setSharedConfig(Map.of("textContent", "smoke", "routingCode", "us"));
        request.setUserId("agent");
        request.setInputs(targets.stream()
                .map(target -> Map.<String, Object>of("target", target))
                .toList());
        request.setBatchSize(1);
        request.setDefaultMaxRetryCount(0);
        return request;
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
