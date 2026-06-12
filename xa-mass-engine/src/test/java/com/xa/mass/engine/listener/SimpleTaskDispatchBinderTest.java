package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.InMemoryTaskShellRuntimeStore;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.TaskQueryService;
import com.xa.mass.engine.TestWorkerCandidateRows;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.model.WorkerSchedulingView;
import com.xa.mass.engine.resource.WorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourceUsage;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.memory.InMemoryTaskResultRuntime;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.worker.runtime.WorkerManager;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionTarget;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
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

    private WorkerManager workerManager;
    private AssignmentRecordService recordService;
    private InMemoryTaskWorkRuntime taskWorkRuntime;
    private TaskManager taskManager;
    private TaskCommandService taskCommands;
    private TaskQueryService taskQueries;
    private SimpleTaskDispatchBinder listener;

    @BeforeEach
    void setUp() {
        workerManager = mock(WorkerManager.class);
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
        when(workerManager.confirmWorkerReservation(any(WorkerAdmissionTarget.class))).thenReturn(true);
        when(workerManager.hasWorkerExclusiveLease(anyString())).thenReturn(true);
        listener = newAssignmentListener();
    }

    @Test
    void dispatchUsesRuntimeReadyWorkInsteadOfProjectionRows() {
        Task task = createTask(3);
        task.getExecutionSpec().setBatchSize(10);
        AtomicReference<List<TaskDispatchBinding>> dispatched = new AtomicReference<>();
        listener = new SimpleTaskDispatchBinder(
                taskManager,
                workerManager,
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

        List<TaskDispatchBinding> dispatched = listener.bindDispatches(
                task,
                List.of(matched("d1"), matched("d2"))
        );

        assertEquals(1, dispatched.size());
        verify(workerManager).releaseWorkerReservation(admissionTarget("d2", task.getTid()));
        verify(workerManager).releaseWorkerExclusiveLease("d2");
    }

    @Test
    void dispatchSubmitFailureCompensatesRuntimeClaimAndReleasesWorkerResources() {
        Task task = createTask(1);
        task.getExecutionSpec().setBatchSize(1);
        AtomicReference<List<TaskDispatchBinding>> failedBindings = new AtomicReference<>();
        listener = new SimpleTaskDispatchBinder(
                taskManager,
                workerManager,
                recordService,
                (context, bindings) -> {
                    failedBindings.set(bindings);
                    throw new IllegalStateException("transport down");
                }
        );

        List<TaskDispatchBinding> dispatched = listener.bindDispatches(task, List.of(matched("d1")));

        assertTrue(dispatched.isEmpty());
        assertNotNull(failedBindings.get());
        assertEquals(1, failedBindings.get().size());
        assertEquals(1, taskWorkRuntime.stats(task.getTid()).readyCount());
        assertEquals(0, taskWorkRuntime.stats(task.getTid()).inflightCount());
        verify(workerManager).confirmWorkerReservation(admissionTarget("d1", task.getTid()));
        verify(workerManager).recordWorkFinal(admissionTarget("d1", task.getTid()));
        verify(workerManager).releaseWorkerExclusiveLease("d1");
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
                workerManager,
                recordService,
                null,
                com.xa.mass.engine.TraceEventLogger.noop(),
                new NonExclusiveResourcePolicy()
        );

        List<TaskDispatchBinding> dispatched = listener.bindDispatches(task, List.of(matched("d1")));

        assertEquals(1, dispatched.size());
        verify(workerManager, never()).releaseWorkerExclusiveLease("d1");
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

    private Worker worker(String id) {
        Worker w = new Worker();
        w.setWorkerId(id);
        return w;
    }

    private WorkerSchedulingCandidate matched(String workerId) {
        Worker worker = worker(workerId);
        return new WorkerSchedulingCandidate(
                TestWorkerCandidateRows.from(worker),
                WorkerSchedulingView.from(TestWorkerCandidateRows.from(worker), WorkerReachabilityState.ONLINE,
                        true, false)
        );
    }

    private SimpleTaskDispatchBinder newAssignmentListener() {
        return new SimpleTaskDispatchBinder(
                taskManager,
                workerManager,
                recordService
        );
    }

    private static WorkerAdmissionTarget admissionTarget(String workerId, String taskId) {
        return WorkerAdmissionTarget.groupScoped("group-a", workerId, taskId);
    }

    private static final class NonExclusiveResourcePolicy implements WorkerDispatchResourcePolicy {
        @Override
        public WorkerDispatchResourceUsage usageForTask(Task task) {
            return new WorkerDispatchResourceUsage(false);
        }

        @Override
        public WorkerDispatchResourceUsage usageForCandidate(Task task, WorkerSchedulingCandidate candidate) {
            return new WorkerDispatchResourceUsage(false);
        }

        @Override
        public WorkerDispatchResourceUsage usageForAttempt(Task task) {
            return new WorkerDispatchResourceUsage(false);
        }
    }
}
