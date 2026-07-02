package com.xa.mass.engine;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.engine.model.TaskCommandOutcome;
import com.xa.mass.engine.policy.ContractAwareTaskTerminalPolicy;
import com.xa.mass.task.runtime.AppendItemInput;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.memory.InMemoryTaskRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRuntimeRecoveryPortTest {

    private Harness harness;

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    void runtimeRecoveryOnlyReturnsTasksWithRuntimeReadyBacklog() {
        harness = new Harness();

        Task first = harness.createReadyTask("runtime-ready-first");
        Task second = harness.createReadyTask("runtime-ready-second");
        harness.claimReadyWork(first);

        TaskRuntimeRecoveryPort recoveryPort = harness.servingLane;
        List<Task> recovered = recoveryPort.getRuntimeDispatchableTasks(10);

        assertEquals(List.of(second.getTid()), recovered.stream().map(Task::getTid).toList());
    }

    @Test
    void runtimeRecoveryDropsRuntimeResidueThatNoLongerHasTaskShellTruth() {
        harness = new Harness();

        Task task = harness.createReadyTask("runtime-ready-live");
        harness.appendRuntimeResidueWithoutTaskShell("missing-task-shell");

        TaskRuntimeRecoveryPort recoveryPort = harness.servingLane;
        List<Task> recovered = recoveryPort.getRuntimeDispatchableTasks(10);

        assertEquals(List.of(task.getTid()), recovered.stream().map(Task::getTid).toList());
    }

    private static TaskCreateSpec buildRequest(String taskName) {
        TaskShellCreateRequestDto dto = new TaskShellCreateRequestDto();
        dto.setSourceRef(taskName);
        dto.setProject("demoApp");
        dto.setUserId("agent");
        return new TaskCreateSpec(dto, List.of(
                Map.of("target", taskName + "-a"),
                Map.of("target", taskName + "-b")
        ));
    }

    private record TaskCreateSpec(TaskShellCreateRequestDto shell, List<Map<String, Object>> inputs) {
    }

    private static TaskExecutionSpec taskExecutionSpec(int defaultMaxRetryCount) {
        TaskExecutionSpec spec = new TaskExecutionSpec();
        spec.setDefaultMaxRetryCount(defaultMaxRetryCount);
        return spec;
    }

    private static final class Harness {
        private final InMemoryTaskRuntime runtime = new InMemoryTaskRuntime();
        private final TaskManager manager;
        private final TaskRuntimeServingLane servingLane;

        private Harness() {
            InMemoryTaskShellRuntimeStore storage = new InMemoryTaskShellRuntimeStore();
            this.manager = new TaskManager(
                    storage,
                    storage,
                    new ContractAwareTaskTerminalPolicy(),
                    null);
            this.servingLane = TaskRuntimeServingLaneTestSupport.forTaskManager(
                    runtime,
                    runtime,
                    runtime,
                    runtime,
                    runtime,
                    manager,
                    300L,
                    TaskManager.MAX_INGEST_BATCH_ITEMS,
                    86_400_000L);
            manager.installTaskRuntimeServingLane(servingLane);
        }

        private Task createReadyTask(String taskName) {
            TaskCreateSpec request = buildRequest(taskName);
            request.shell().setExecutionSpec(taskExecutionSpec(3));
            TaskCommandOutcome create = manager.createTaskShell(request.shell());
            assertTrue(create.accepted());
            Task task = manager.getTask(create.taskId());
            assertNotNull(task);
            manager.approveTask(task.getTid());
            manager.appendTaskItems(task.getTid(), request.inputs());
            manager.sealTask(task.getTid());
            return manager.getTask(task.getTid());
        }

        private void claimReadyWork(Task task) {
            TaskRuntimeClaimTestSupport.claim(
                    servingLane,
                    task.getTid(),
                    "group-1",
                    "worker-1",
                    "batch-1",
                    "selection-1",
                    1L,
                    2,
                    manager.getWorkLeaseSeconds());
        }

        private void appendRuntimeResidueWithoutTaskShell(String taskId) {
            runtime.appendBacklog(
                    taskId,
                    List.of(new AppendItemInput("message-missing-shell", "", Map.of("value", "orphan"), null)),
                    10);
        }

        private void close() {
            manager.shutdown();
        }
    }
}
