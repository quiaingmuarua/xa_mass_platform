package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.TaskDispatchWakeupPort;
import com.xa.mass.engine.TaskLeaseMaintenancePort;
import com.xa.mass.engine.TaskWorkLifecycleState.AttemptFinalReason;
import com.xa.mass.engine.TaskWorkLifecycleState.AttemptStatus;
import com.xa.mass.engine.TaskWorkAttemptClosedEvent;
import com.xa.mass.worker.runtime.WorkerManager;
import com.xa.mass.engine.assignment.AssignmentRefillDecision;
import com.xa.mass.engine.resource.DefaultWorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourceReleaser;
import com.xa.mass.engine.resource.WorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourceUsage;
import com.xa.mass.engine.util.TraceEventLogCapture;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.runtime.api.TaskWorkEnvelope;
import com.xa.mass.runtime.api.WorkEnqueueOptions;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

public class TaskResourceReleaseListenerTest {

    private TaskLeaseMaintenancePort leaseMaintenancePort;
    private TaskDispatchWakeupPort dispatchWakeupPort;
    private WorkerManager workerManager;
    private TaskResourceReleaseListener listener;

    @BeforeEach
    void setUp() {
        leaseMaintenancePort = mock(TaskLeaseMaintenancePort.class);
        dispatchWakeupPort = mock(TaskDispatchWakeupPort.class);
        workerManager = mock(WorkerManager.class);
        listener = new TaskResourceReleaseListener(leaseMaintenancePort, dispatchWakeupPort, workerManager);
    }

    @Test
    void terminalTaskReleasesWorkerLoadAndUnlocksWorker() {
        Task task = new Task();
        task.setTid("task-1");

        when(leaseMaintenancePort.getActiveLeases("task-1")).thenReturn(activeLeases("task-1", "msg-1", "worker-1"));

        listener.onTaskTerminal(task);

        verify(workerManager).recordWorkFinal("worker-1", "task-1");
        verify(workerManager).releaseWorkerExclusiveLease("worker-1");
    }

    @Test
    void terminalBackgroundTaskReleasesLoadButDoesNotUnlockWorker() {
        Task task = new Task();
        task.setTid("task-1");
        task.getExecutionSpec().setForeground(false);

        when(leaseMaintenancePort.getActiveLeases("task-1")).thenReturn(activeLeases("task-1", "msg-1", "worker-1"));

        listener.onTaskTerminal(task);

        verify(workerManager).recordWorkFinal("worker-1", "task-1");
        verify(workerManager, never()).releaseWorkerExclusiveLease("worker-1");
    }

    @Test
    void terminalBackgroundTaskDoesNotReleaseExclusiveWorkerLock() {
        Task task = new Task();
        task.setTid("task-1");
        task.getExecutionSpec().setForeground(false);

        when(leaseMaintenancePort.getActiveLeases("task-1")).thenReturn(activeLeases("task-1", "msg-1", "worker-1"));

        listener.onTaskTerminal(task);

        verify(workerManager).recordWorkFinal("worker-1", "task-1");
        verify(workerManager, never()).releaseWorkerExclusiveLease("worker-1");
    }

    @Test
    void terminalTaskEmitsReleaseTrace() {
        Task task = new Task();
        task.setTid("task-1");

        when(leaseMaintenancePort.getActiveLeases("task-1")).thenReturn(activeLeases("task-1", "msg-1", "worker-1"));

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            listener.onTaskTerminal(task);
            capture.assertHasEvent("RESOURCE_RELEASED", mdc ->
                    "task-1".equals(mdc.get("taskId"))
                            && "worker-1".equals(mdc.get("workerId"))
                            && !mdc.containsKey("workerContextId")
                            && "WORKER_LOCK".equals(mdc.get("resourceKind")));
        }
    }

    @Test
    void listenerReleasesWorkerLevelLockOnTerminalTask() {
        Task task = new Task();
        task.setTid("task-1");

        when(leaseMaintenancePort.getActiveLeases("task-1")).thenReturn(activeLeases("task-1", "msg-1", "worker-1"));

        listener.onTaskTerminal(task);

        verify(workerManager).releaseWorkerExclusiveLease("worker-1");
    }

    @Test
    void attemptClosedReleasesIdleWorkerAndRequestsReplenishment() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);

        TaskWorkAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1");

        when(leaseMaintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(false);
        when(dispatchWakeupPort.hasDispatchReadyWork("task-1")).thenReturn(true);

        listener.onTaskWorkAttemptClosed(task, closedAttempt);

        verify(workerManager).recordWorkFinal("worker-1", "task-1");
        verify(workerManager).releaseWorkerExclusiveLease("worker-1");
        verify(dispatchWakeupPort).requestTaskDispatch(same(task));
    }

    @Test
    void attemptClosedReleasesWorkerWhenRemainingMessagesAreNotProcessing() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);

        TaskWorkAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1");

        when(leaseMaintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(false);
        when(dispatchWakeupPort.hasDispatchReadyWork("task-1")).thenReturn(true);

        listener.onTaskWorkAttemptClosed(task, closedAttempt);

        verify(workerManager).releaseWorkerExclusiveLease("worker-1");
        verify(dispatchWakeupPort).requestTaskDispatch(same(task));
    }

    @Test
    void backgroundAttemptClosedReleasesLoadAndRequestsReplenishmentWithoutUnlock() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);
        task.getExecutionSpec().setForeground(false);

        TaskWorkAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1");

        when(leaseMaintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(false);
        when(dispatchWakeupPort.hasDispatchReadyWork("task-1")).thenReturn(true);

        listener.onTaskWorkAttemptClosed(task, closedAttempt);

        verify(workerManager).recordWorkFinal("worker-1", "task-1");
        verify(workerManager, never()).releaseWorkerExclusiveLease("worker-1");
        verify(dispatchWakeupPort).requestTaskDispatch(same(task));
    }

    @Test
    void injectedRefillPolicyCanSuppressRefillWithoutBlockingRelease() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);

        TaskWorkAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1");

        listener = new TaskResourceReleaseListener(
                leaseMaintenancePort,
                dispatchWakeupPort,
                workerManager,
                TraceEventLogger.noop(),
                request -> AssignmentRefillDecision.skip("refill suppressed by test policy")
        );

        when(leaseMaintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(false);

        listener.onTaskWorkAttemptClosed(task, closedAttempt);

        verify(workerManager).recordWorkFinal("worker-1", "task-1");
        verify(workerManager).releaseWorkerExclusiveLease("worker-1");
        verify(dispatchWakeupPort, never()).hasDispatchReadyWork("task-1");
        verify(dispatchWakeupPort, never()).requestTaskDispatch(any());
    }

    @Test
    void injectedResourcePolicyOwnsReleaseUnlockDecision() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);

        TaskWorkAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1");

        listener = new TaskResourceReleaseListener(
                leaseMaintenancePort,
                dispatchWakeupPort,
                workerManager,
                TraceEventLogger.noop(),
                request -> AssignmentRefillDecision.skip("refill suppressed by test policy"),
                new NonExclusiveResourcePolicy()
        );

        when(leaseMaintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(false);

        listener.onTaskWorkAttemptClosed(task, closedAttempt);

        verify(workerManager).recordWorkFinal("worker-1", "task-1");
        verify(workerManager, never()).releaseWorkerExclusiveLease("worker-1");
    }

    @Test
    void injectedResourceReleaserOwnsAttemptUnlock() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);
        TaskWorkAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1");
        WorkerDispatchResourceReleaser resourceReleaser = mock(WorkerDispatchResourceReleaser.class);
        listener = new TaskResourceReleaseListener(
                leaseMaintenancePort,
                dispatchWakeupPort,
                workerManager,
                TraceEventLogger.noop(),
                request -> AssignmentRefillDecision.skip("refill suppressed by test policy"),
                new DefaultWorkerDispatchResourcePolicy(),
                resourceReleaser
        );

        when(leaseMaintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(false);

        listener.onTaskWorkAttemptClosed(task, closedAttempt);

        verify(resourceReleaser).releaseAttemptLockIfExclusive(
                task,
                "worker-1",
                "ON_TASK_MESSAGE_ATTEMPT_CLOSED",
                "TaskResourceReleaseListener",
                "worker has no in-flight messages"
        );
        verify(workerManager, never()).releaseWorkerExclusiveLease("worker-1");
    }

    @Test
    void repairedAttemptClosedStillReleasesWorkerForTerminalTask() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.TERMINAL);

        TaskWorkAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1");

        when(leaseMaintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(false);

        listener.onTaskWorkAttemptClosed(task, closedAttempt);

        verify(workerManager).releaseWorkerExclusiveLease("worker-1");
        verify(dispatchWakeupPort, never()).hasDispatchReadyWork("task-1");
        verify(dispatchWakeupPort, never()).requestTaskDispatch(any());
    }

    @Test
    void attemptClosedKeepsWorkerLockedWhenAnotherMessageIsStillProcessing() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);

        TaskWorkAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1");

        when(leaseMaintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(true);

        listener.onTaskWorkAttemptClosed(task, closedAttempt);

        verify(workerManager, never()).releaseWorkerExclusiveLease("worker-1");
        verify(dispatchWakeupPort, never()).requestTaskDispatch(any());
    }

    @Test
    void terminalTaskWithExclusiveWorkerLockEmitsWorkerReleaseTrace() {
        Task task = new Task();
        task.setTid("task-1");

        when(leaseMaintenancePort.getActiveLeases("task-1")).thenReturn(activeLeases("task-1", "msg-1", "worker-1"));

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            listener.onTaskTerminal(task);
            capture.assertHasEvent("RESOURCE_RELEASED", mdc ->
                    "task-1".equals(mdc.get("taskId"))
                            && "worker-1".equals(mdc.get("workerId"))
                            && !mdc.containsKey("workerContextId")
                            && "WORKER_LOCK".equals(mdc.get("resourceKind")));
        }

        verify(workerManager).releaseWorkerExclusiveLease("worker-1");
    }

    private TaskWorkAttemptClosedEvent closedAttempt(String taskId,
                                                        String messageId,
                                                        String attemptId,
                                                        String workerId) {
        return TaskWorkAttemptClosedEvent.from(
                taskId,
                messageId,
                attemptId,
                1,
                workerId,
                null,
                AttemptStatus.SUCCEEDED,
                AttemptFinalReason.SUCCESS
        );
    }

    private List<com.xa.mass.runtime.api.ActiveLeaseRecord> activeLeases(String taskId,
                                                                         String messageId,
                                                                         String workerId) {
        InMemoryTaskWorkRuntime runtime = new InMemoryTaskWorkRuntime();
        runtime.enqueue(new TaskWorkEnvelope(taskId, messageId, "demo.event",
                        Map.of("target", messageId), null, 0, 3, null, null, Instant.now()),
                WorkEnqueueOptions.DEFAULT);
        runtime.claimReady(taskId,
                List.of(WorkerClaimTarget.workerLevel(workerId, "batch-1", 1)),
                1,
                30);
        return runtime.activeLeases(taskId);
    }

    private static final class NonExclusiveResourcePolicy implements WorkerDispatchResourcePolicy {
        @Override
        public WorkerDispatchResourceUsage usageForTask(Task task) {
            return new WorkerDispatchResourceUsage(false);
        }

        @Override
        public WorkerDispatchResourceUsage usageForCandidate(Task task,
                                                            com.xa.mass.engine.model.WorkerSchedulingCandidate candidate) {
            return new WorkerDispatchResourceUsage(false);
        }

        @Override
        public WorkerDispatchResourceUsage usageForAttempt(Task task) {
            return new WorkerDispatchResourceUsage(false);
        }
    }
}


