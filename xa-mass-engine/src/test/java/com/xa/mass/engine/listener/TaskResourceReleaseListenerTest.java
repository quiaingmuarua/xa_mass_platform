package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.TaskWorkProjectionState.AttemptFinalReason;
import com.xa.mass.engine.TaskWorkProjectionState.AttemptStatus;
import com.xa.mass.engine.TaskWorkAttemptClosedEvent;
import com.xa.mass.engine.TaskRuntimeMaintenancePort;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.assignment.AssignmentRefillDecision;
import com.xa.mass.engine.resource.DefaultWorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourceReleaser;
import com.xa.mass.engine.resource.WorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourceUsage;
import com.xa.mass.engine.util.TraceEventLogCapture;
import com.xa.mass.engine.util.TraceEventLogger;
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

    private TaskRuntimeMaintenancePort maintenancePort;
    private WorkerManager workerManager;
    private TaskResourceReleaseListener listener;

    @BeforeEach
    void setUp() {
        maintenancePort = mock(TaskRuntimeMaintenancePort.class);
        workerManager = mock(WorkerManager.class);
        listener = new TaskResourceReleaseListener(maintenancePort, workerManager);
    }

    @Test
    void terminalTaskReleasesWorkerLoadAndUnlocksWorker() {
        Task task = new Task();
        task.setTid("task-1");

        when(maintenancePort.getActiveLeases("task-1")).thenReturn(activeLeases("task-1", "msg-1", "worker-1", "wctx-1"));

        listener.onTaskTerminal(task);

        verify(workerManager).recordWorkFinal("worker-1", "task-1");
        verify(workerManager).unlockWorker("worker-1");
    }

    @Test
    void terminalBackgroundTaskReleasesLoadButDoesNotUnlockWorker() {
        Task task = new Task();
        task.setTid("task-1");
        task.getExecutionSpec().setForeground(false);

        when(maintenancePort.getActiveLeases("task-1")).thenReturn(activeLeases("task-1", "msg-1", "worker-1", null));

        listener.onTaskTerminal(task);

        verify(workerManager).recordWorkFinal("worker-1", "task-1");
        verify(workerManager, never()).unlockWorker("worker-1");
    }

    @Test
    void terminalBackgroundTaskWithWorkerContextDoesNotUnlockFromContextIdentity() {
        Task task = new Task();
        task.setTid("task-1");
        task.getExecutionSpec().setForeground(false);

        when(maintenancePort.getActiveLeases("task-1")).thenReturn(activeLeases("task-1", "msg-1", "worker-1", "wctx-1"));

        listener.onTaskTerminal(task);

        verify(workerManager).recordWorkFinal("worker-1", "task-1");
        verify(workerManager, never()).unlockWorker("worker-1");
    }

    @Test
    void terminalTaskEmitsReleaseTrace() {
        Task task = new Task();
        task.setTid("task-1");

        when(maintenancePort.getActiveLeases("task-1")).thenReturn(activeLeases("task-1", "msg-1", "worker-1", "wctx-1"));

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
    void listenerDoesNotReleaseWorkerContextOwnedByAnotherTask() {
        Task task = new Task();
        task.setTid("task-1");

        when(maintenancePort.getActiveLeases("task-1")).thenReturn(activeLeases("task-1", "msg-1", "worker-1", "wctx-1"));

        listener.onTaskTerminal(task);

        verify(workerManager).unlockWorker("worker-1");
    }

    @Test
    void attemptClosedReleasesIdleWorkerAndRequestsReplenishment() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);

        TaskWorkAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1", "wctx-1");

        when(maintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(false);
        when(maintenancePort.hasDispatchReadyWork("task-1")).thenReturn(true);

        listener.onTaskWorkAttemptClosed(task, closedAttempt);

        verify(workerManager).recordWorkFinal("worker-1", "task-1");
        verify(workerManager).unlockWorker("worker-1");
        verify(maintenancePort).requestTaskDispatch(same(task));
    }

    @Test
    void attemptClosedReleasesWorkerWhenRemainingMessagesAreNotProcessing() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);

        TaskWorkAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1", "wctx-1");

        when(maintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(false);
        when(maintenancePort.hasDispatchReadyWork("task-1")).thenReturn(true);

        listener.onTaskWorkAttemptClosed(task, closedAttempt);

        verify(workerManager).unlockWorker("worker-1");
        verify(maintenancePort).requestTaskDispatch(same(task));
    }

    @Test
    void backgroundAttemptClosedReleasesLoadAndRequestsReplenishmentWithoutUnlock() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);
        task.getExecutionSpec().setForeground(false);

        TaskWorkAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1", null);

        when(maintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(false);
        when(maintenancePort.hasDispatchReadyWork("task-1")).thenReturn(true);

        listener.onTaskWorkAttemptClosed(task, closedAttempt);

        verify(workerManager).recordWorkFinal("worker-1", "task-1");
        verify(workerManager, never()).unlockWorker("worker-1");
        verify(maintenancePort).requestTaskDispatch(same(task));
    }

    @Test
    void injectedRefillPolicyCanSuppressRefillWithoutBlockingRelease() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);

        TaskWorkAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1", "wctx-1");

        listener = new TaskResourceReleaseListener(
                maintenancePort,
                workerManager,
                TraceEventLogger.noop(),
                request -> AssignmentRefillDecision.skip("refill suppressed by test policy")
        );

        when(maintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(false);

        listener.onTaskWorkAttemptClosed(task, closedAttempt);

        verify(workerManager).recordWorkFinal("worker-1", "task-1");
        verify(workerManager).unlockWorker("worker-1");
        verify(maintenancePort, never()).hasDispatchReadyWork("task-1");
        verify(maintenancePort, never()).requestTaskDispatch(any());
    }

    @Test
    void injectedResourcePolicyOwnsReleaseUnlockDecision() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);

        TaskWorkAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1", "wctx-1");

        listener = new TaskResourceReleaseListener(
                maintenancePort,
                workerManager,
                TraceEventLogger.noop(),
                request -> AssignmentRefillDecision.skip("refill suppressed by test policy"),
                new NonExclusiveResourcePolicy()
        );

        when(maintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(false);

        listener.onTaskWorkAttemptClosed(task, closedAttempt);

        verify(workerManager).recordWorkFinal("worker-1", "task-1");
        verify(workerManager, never()).unlockWorker("worker-1");
    }

    @Test
    void injectedResourceReleaserOwnsAttemptUnlock() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);
        TaskWorkAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1", "wctx-1");
        WorkerDispatchResourceReleaser resourceReleaser = mock(WorkerDispatchResourceReleaser.class);
        listener = new TaskResourceReleaseListener(
                maintenancePort,
                workerManager,
                TraceEventLogger.noop(),
                request -> AssignmentRefillDecision.skip("refill suppressed by test policy"),
                new DefaultWorkerDispatchResourcePolicy(),
                resourceReleaser
        );

        when(maintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(false);

        listener.onTaskWorkAttemptClosed(task, closedAttempt);

        verify(resourceReleaser).releaseAttemptLockIfExclusive(
                task,
                "worker-1",
                "ON_TASK_MESSAGE_ATTEMPT_CLOSED",
                "TaskResourceReleaseListener",
                "worker has no in-flight messages"
        );
        verify(workerManager, never()).unlockWorker("worker-1");
    }

    @Test
    void repairedAttemptClosedStillReleasesWorkerForTerminalTask() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.TERMINAL);

        TaskWorkAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1", "wctx-1");

        when(maintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(false);

        listener.onTaskWorkAttemptClosed(task, closedAttempt);

        verify(workerManager).unlockWorker("worker-1");
        verify(maintenancePort, never()).hasDispatchReadyWork("task-1");
        verify(maintenancePort, never()).requestTaskDispatch(any());
    }

    @Test
    void attemptClosedKeepsWorkerLockedWhenAnotherMessageIsStillProcessing() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);

        TaskWorkAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1", "wctx-1");

        when(maintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(true);

        listener.onTaskWorkAttemptClosed(task, closedAttempt);

        verify(workerManager, never()).unlockWorker("worker-1");
        verify(maintenancePort, never()).requestTaskDispatch(any());
    }

    @Test
    void terminalTaskWithBlockedWorkerContextStillEmitsWorkerReleaseTrace() {
        Task task = new Task();
        task.setTid("task-1");

        when(maintenancePort.getActiveLeases("task-1")).thenReturn(activeLeases("task-1", "msg-1", "worker-1", "wctx-1"));

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            listener.onTaskTerminal(task);
            capture.assertHasEvent("RESOURCE_RELEASED", mdc ->
                    "task-1".equals(mdc.get("taskId"))
                            && "worker-1".equals(mdc.get("workerId"))
                            && !mdc.containsKey("workerContextId")
                            && "WORKER_LOCK".equals(mdc.get("resourceKind")));
        }

        verify(workerManager).unlockWorker("worker-1");
    }

    private TaskWorkAttemptClosedEvent closedAttempt(String taskId,
                                                        String messageId,
                                                        String attemptId,
                                                        String workerId,
                                                        String workerContextId) {
        return TaskWorkAttemptClosedEvent.from(
                taskId,
                messageId,
                attemptId,
                1,
                workerId,
                workerContextId,
                null,
                AttemptStatus.SUCCEEDED,
                AttemptFinalReason.SUCCESS
        );
    }

    private List<com.xa.mass.runtime.api.ActiveLeaseRecord> activeLeases(String taskId,
                                                                         String messageId,
                                                                         String workerId,
                                                                         String workerContextId) {
        InMemoryTaskWorkRuntime runtime = new InMemoryTaskWorkRuntime();
        runtime.enqueue(new TaskWorkEnvelope(taskId, messageId, "demo.event",
                        Map.of("target", messageId), null, 0, 3, null, null, Instant.now()),
                WorkEnqueueOptions.DEFAULT);
        runtime.claimReady(taskId,
                List.of(new WorkerClaimTarget(workerId, workerContextId, "batch-1", 1)),
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


