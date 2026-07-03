package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.InMemoryTaskShellRuntimeStore;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.TaskQueryService;
import com.xa.mass.engine.resource.WorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourceUsage;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.memory.InMemoryTaskResultRuntime;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.worker.runtime.selection.SelectedWorkerEvidence;
import com.xa.mass.worker.runtime.selection.SelectedWorkerHandle;
import com.xa.mass.worker.runtime.selection.WorkerSelectionRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SimpleTaskDispatchBinderTest {

    private WorkerSelectionRuntime workerSelectionRuntime;
    private AssignmentRecordService recordService;
    private InMemoryTaskWorkRuntime taskWorkRuntime;
    private TaskManager taskManager;
    private TaskCommandService taskCommands;
    private TaskQueryService taskQueries;
    private SimpleTaskDispatchBinder listener;

    @BeforeEach
    void setUp() {
        workerSelectionRuntime = mock(WorkerSelectionRuntime.class);
        recordService = mock(AssignmentRecordService.class);
        taskWorkRuntime = new InMemoryTaskWorkRuntime();
        taskManager = new TaskManager(
                new InMemoryTaskShellRuntimeStore(),
                taskWorkRuntime,
                new InMemoryTaskResultRuntime(),
                null
        );
        taskCommands = new TaskCommandService(taskManager);
        taskQueries = new TaskQueryService(taskManager);
        when(workerSelectionRuntime.confirmSelected(any(SelectedWorkerHandle.class))).thenReturn(true);
        listener = newAssignmentListener();
    }

    @Test
    void dispatchUsesRuntimeReadyWorkInsteadOfProjectionRows() {
        Task task = createTask(3);
        task.getExecutionSpec().setBatchSize(10);
        AtomicReference<List<TaskDispatchBinding>> dispatched = new AtomicReference<>();
        listener = new SimpleTaskDispatchBinder(
                taskManager,
                workerSelectionRuntime,
                recordService,
                (t, bindings) -> dispatched.set(bindings)
        );

        listener.bindDispatches(task, List.of(matched("d1"), matched("d2")));

        List<TaskDispatchBinding> pushed = dispatched.get();
        assertNotNull(pushed);
        assertEquals(3, pushed.size());
        assertEquals(List.of("target-0", "target-1", "target-2"),
                pushed.stream().map(binding -> binding.payload().get("target")).collect(Collectors.toList()));
        assertEquals(0, taskWorkRuntime.stats(task.getTid()).readyCount());
        assertEquals(3, taskWorkRuntime.stats(task.getTid()).inflightCount());
    }

    @Test
    void assignmentUsesConfiguredTaskMessageLeaseWindow() {
        taskManager.setWorkLeaseSeconds(2L);
        Task task = createTask(1);
        task.getExecutionSpec().setBatchSize(1);

        LocalDateTime beforeAssign = LocalDateTime.now();
        List<TaskDispatchBinding> dispatched = listener.bindDispatches(task, List.of(matched("d1")));
        LocalDateTime afterAssign = LocalDateTime.now();

        assertEquals(1, dispatched.size());
        ActiveLeaseRecord activeLease = taskWorkRuntime
                .getActiveLease(task.getTid(), dispatched.getFirst().messageId())
                .orElse(null);

        assertNotNull(activeLease);
        assertNotNull(activeLease.leaseExpireAt());
        LocalDateTime leaseExpireTime = LocalDateTime.ofInstant(activeLease.leaseExpireAt(), java.time.ZoneId.systemDefault());
        long lowerBound = Duration.between(beforeAssign, leaseExpireTime).getSeconds();
        long upperBound = Duration.between(afterAssign, leaseExpireTime).getSeconds();
        assertTrue(lowerBound >= 1, "lease should be at least about 2 seconds after assignment start");
        assertTrue(upperBound <= 2, "lease should stay close to configured 2-second window");
    }

    @Test
    void workerWithNoClaimedMessagesReleasesReservationAndUnlocks() {
        Task task = createTask(1);
        task.getExecutionSpec().setBatchSize(1);
        SelectedWorkerHandle first = matched("d1", task.getTid());
        SelectedWorkerHandle second = matched("d2", task.getTid());

        List<TaskDispatchBinding> dispatched = listener.bindDispatches(
                task,
                List.of(first, second)
        );

        assertEquals(1, dispatched.size());
        verify(workerSelectionRuntime).releaseSelected(second);
    }

    @Test
    void taskManagerCountsActiveDispatchWorkersFromTaskRuntimeLeases() {
        Task task = createTask(2);
        task.getExecutionSpec().setBatchSize(1);

        List<TaskDispatchBinding> dispatched = listener.bindDispatches(
                task,
                List.of(matched("d1"), matched("d2"))
        );

        assertEquals(2, dispatched.size());
        assertEquals(2, taskManager.countActiveDispatchWorkers(task.getTid()));
    }

    @Test
    void dispatchSubmitFailureCompensatesRuntimeClaimAndReleasesWorkerResources() {
        Task task = createTask(1);
        task.getExecutionSpec().setBatchSize(1);
        AtomicReference<List<TaskDispatchBinding>> failedBindings = new AtomicReference<>();
        listener = new SimpleTaskDispatchBinder(
                taskManager,
                workerSelectionRuntime,
                recordService,
                (context, bindings) -> {
                    failedBindings.set(bindings);
                    throw new IllegalStateException("transport down");
                }
        );
        SelectedWorkerHandle selected = matched("d1", task.getTid());

        List<TaskDispatchBinding> dispatched = listener.bindDispatches(task, List.of(selected));

        assertTrue(dispatched.isEmpty());
        assertNotNull(failedBindings.get());
        assertEquals(1, failedBindings.get().size());
        assertEquals(1, taskWorkRuntime.stats(task.getTid()).readyCount());
        assertEquals(0, taskWorkRuntime.stats(task.getTid()).inflightCount());
        verify(workerSelectionRuntime).confirmSelected(selected);
        verify(workerSelectionRuntime).recordSelectedFinal(new SelectedWorkerEvidence(
                "d1",
                "group-a",
                task.getTid(),
                selected.selectionToken(),
                null,
                false
        ));
        verify(workerSelectionRuntime).releaseSelectedLock(selected);
    }

    @Test
    void interactiveWorkloadUsesSmallPerWorkerClaimWindow() {
        Task task = createTask(5);
        task.getExecutionSpec().setBatchSize(4);
        task.getExecutionSpec().setWorkloadClass(TaskWorkloadClass.INTERACTIVE);

        List<TaskDispatchBinding> dispatched = listener.bindDispatches(task, List.of(matched("d1"), matched("d2")));

        assertEquals(2, dispatched.size());
        assertEquals(3, taskWorkRuntime.stats(task.getTid()).readyCount());
        assertEquals(2, taskWorkRuntime.stats(task.getTid()).inflightCount());
    }

    @Test
    void injectedResourcePolicyOwnsBinderContextAndUnlockDecision() {
        Task task = createTask(1);
        listener = new SimpleTaskDispatchBinder(
                taskManager,
                workerSelectionRuntime,
                recordService,
                null,
                com.xa.mass.engine.TraceEventLogger.noop(),
                new NonExclusiveResourcePolicy()
        );

        List<TaskDispatchBinding> dispatched = listener.bindDispatches(task, List.of(matched("d1")));

        assertEquals(1, dispatched.size());
        verify(workerSelectionRuntime, never()).releaseSelectedLock(any(SelectedWorkerHandle.class));
    }

    @Test
    void successfulNonExclusiveDispatchReleasesScoreBandSelectionAfterSubmit() {
        Task task = createTask(1);
        AtomicReference<List<TaskDispatchBinding>> dispatchedBatch = new AtomicReference<>();
        listener = new SimpleTaskDispatchBinder(
                taskManager,
                workerSelectionRuntime,
                recordService,
                (context, bindings) -> dispatchedBatch.set(bindings),
                com.xa.mass.engine.TraceEventLogger.noop(),
                new NonExclusiveResourcePolicy()
        );
        SelectedWorkerHandle selected = matched("d1", task.getTid(), false);

        List<TaskDispatchBinding> dispatched = listener.bindDispatches(task, List.of(selected));

        assertEquals(1, dispatched.size());
        assertNotNull(dispatchedBatch.get());
        verify(workerSelectionRuntime).releaseSelected(selected);
        verify(workerSelectionRuntime, never()).releaseSelectedLock(selected);
    }

    private Task createTask(int messageCount) {
        TaskShellCreateRequestDto dto = new TaskShellCreateRequestDto();
        dto.setSourceRef("task");
        dto.setProject("demoApp");
        dto.setSharedConfig(java.util.Map.of(
                "textContent", "hello",
                "routingCode", "us",
                TaskSharedConfig.WORKER_GROUP_ID, "group-a"
        ));
        dto.setUserId("agent");
        TaskExecutionSpec spec = new TaskExecutionSpec();
        spec.setBatchSize(1);
        spec.setDefaultMaxRetryCount(3);
        dto.setExecutionSpec(spec);
        Task task = taskCommands.createTaskShell(dto);
        taskCommands.appendTaskItems(task.getTid(), IntStream.range(0, messageCount)
                .mapToObj(i -> java.util.Map.<String, Object>of("target", "target-" + i))
                .collect(Collectors.toCollection(ArrayList::new)));
        assertTrue(taskCommands.sealTask(task.getTid()));
        return taskQueries.getTask(task.getTid());
    }

    private SelectedWorkerHandle matched(String workerId) {
        return matched(workerId, "task");
    }

    private SelectedWorkerHandle matched(String workerId, String taskId) {
        return matched(workerId, taskId, true);
    }

    private SelectedWorkerHandle matched(String workerId, String taskId, boolean exclusiveWorkerLock) {
        return SelectedWorkerHandle.of(
                workerId,
                "group-a",
                taskId,
                exclusiveWorkerLock
        );
    }

    private SimpleTaskDispatchBinder newAssignmentListener() {
        return new SimpleTaskDispatchBinder(
                taskManager,
                workerSelectionRuntime,
                recordService
        );
    }

    private static final class NonExclusiveResourcePolicy implements WorkerDispatchResourcePolicy {
        @Override
        public WorkerDispatchResourceUsage usageForTask(Task task) {
            return new WorkerDispatchResourceUsage(false);
        }

        @Override
        public WorkerDispatchResourceUsage usageForAttempt(Task task) {
            return new WorkerDispatchResourceUsage(false);
        }
    }
}
