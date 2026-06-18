package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.TaskDispatchWakeupPort;
import com.xa.mass.engine.TaskLeaseMaintenancePort;
import com.xa.mass.engine.TaskWorkAttemptClosedEvent;
import com.xa.mass.engine.TaskWorkLifecycleState.AttemptFinalReason;
import com.xa.mass.engine.TaskWorkLifecycleState.AttemptStatus;
import com.xa.mass.engine.TraceEventLogger;
import com.xa.mass.engine.assignment.AssignmentRefillDecision;
import com.xa.mass.engine.resource.WorkerDispatchResourcePolicy;
import com.xa.mass.engine.resource.WorkerDispatchResourceUsage;
import com.xa.mass.engine.util.TraceEventLogCapture;
import com.xa.mass.runtime.api.TaskWorkEnvelope;
import com.xa.mass.runtime.api.WorkEnqueueOptions;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import com.xa.mass.runtime.memory.InMemoryTaskWorkRuntime;
import com.xa.mass.worker.runtime.selection.SelectedWorkerEvidence;
import com.xa.mass.worker.runtime.selection.WorkerSelectionRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TaskResourceReleaseListenerTest {

    private TaskLeaseMaintenancePort leaseMaintenancePort;
    private TaskDispatchWakeupPort dispatchWakeupPort;
    private WorkerSelectionRuntime workerSelectionRuntime;
    private TaskResourceReleaseListener listener;

    @BeforeEach
    void setUp() {
        leaseMaintenancePort = mock(TaskLeaseMaintenancePort.class);
        dispatchWakeupPort = mock(TaskDispatchWakeupPort.class);
        workerSelectionRuntime = mock(WorkerSelectionRuntime.class);
        listener = new TaskResourceReleaseListener(leaseMaintenancePort, dispatchWakeupPort, workerSelectionRuntime);
    }

    @Test
    void terminalTaskRecordsSelectedFinalAndReleasesExclusiveLock() {
        Task task = task("task-1", TaskStatus.TERMINAL, true);
        when(leaseMaintenancePort.getActiveLeases("task-1"))
                .thenReturn(activeLeases("task-1", "msg-1", "worker-1"));

        listener.onTaskTerminal(task);

        verify(workerSelectionRuntime).recordSelectedFinal(
                SelectedWorkerEvidence.of("worker-1", "group-a", "task-1", false));
        verify(workerSelectionRuntime).releaseSelectedLock(
                SelectedWorkerEvidence.of("worker-1", "group-a", "task-1", true));
    }

    @Test
    void terminalBackgroundTaskRecordsFinalWithoutReleasingExclusiveLock() {
        Task task = task("task-1", TaskStatus.TERMINAL, false);
        when(leaseMaintenancePort.getActiveLeases("task-1"))
                .thenReturn(activeLeases("task-1", "msg-1", "worker-1"));

        listener.onTaskTerminal(task);

        verify(workerSelectionRuntime).recordSelectedFinal(
                SelectedWorkerEvidence.of("worker-1", "group-a", "task-1", false));
        verify(workerSelectionRuntime, never()).releaseSelectedLock(any(SelectedWorkerEvidence.class));
    }

    @Test
    void attemptClosedReleasesIdleWorkerAndRequestsReplenishment() {
        Task task = task("task-1", TaskStatus.RUNNING, true);
        TaskWorkAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1");
        when(leaseMaintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(false);
        when(dispatchWakeupPort.hasDispatchReadyWork("task-1")).thenReturn(true);

        listener.onTaskWorkAttemptClosed(task, closedAttempt);

        verify(workerSelectionRuntime).recordSelectedFinal(
                SelectedWorkerEvidence.of("worker-1", "group-a", "task-1", false));
        verify(workerSelectionRuntime).releaseSelectedLock(
                SelectedWorkerEvidence.of("worker-1", "group-a", "task-1", true));
        verify(dispatchWakeupPort).requestTaskDispatch(same(task));
    }

    @Test
    void attemptClosedKeepsWorkerLockedWhenAnotherMessageIsStillProcessing() {
        Task task = task("task-1", TaskStatus.RUNNING, true);
        TaskWorkAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1");
        when(leaseMaintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(true);

        listener.onTaskWorkAttemptClosed(task, closedAttempt);

        verify(workerSelectionRuntime).recordSelectedFinal(
                SelectedWorkerEvidence.of("worker-1", "group-a", "task-1", false));
        verify(workerSelectionRuntime, never()).releaseSelectedLock(any(SelectedWorkerEvidence.class));
        verify(dispatchWakeupPort, never()).requestTaskDispatch(any());
    }

    @Test
    void injectedResourcePolicyOwnsReleaseUnlockDecision() {
        Task task = task("task-1", TaskStatus.RUNNING, true);
        TaskWorkAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1");
        listener = new TaskResourceReleaseListener(
                leaseMaintenancePort,
                dispatchWakeupPort,
                workerSelectionRuntime,
                TraceEventLogger.noop(),
                request -> AssignmentRefillDecision.skip("refill suppressed by test policy"),
                new NonExclusiveResourcePolicy()
        );
        when(leaseMaintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(false);

        listener.onTaskWorkAttemptClosed(task, closedAttempt);

        verify(workerSelectionRuntime).recordSelectedFinal(
                SelectedWorkerEvidence.of("worker-1", "group-a", "task-1", false));
        verify(workerSelectionRuntime, never()).releaseSelectedLock(any(SelectedWorkerEvidence.class));
    }

    @Test
    void terminalTaskWithExclusiveWorkerLockEmitsWorkerReleaseTrace() {
        Task task = task("task-1", TaskStatus.TERMINAL, true);
        listener = new TaskResourceReleaseListener(
                leaseMaintenancePort,
                dispatchWakeupPort,
                workerSelectionRuntime,
                new TraceEventLogger(new com.xa.mass.trace.sink.NoopExecutionEventSink())
        );
        when(leaseMaintenancePort.getActiveLeases("task-1"))
                .thenReturn(activeLeases("task-1", "msg-1", "worker-1"));

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            listener.onTaskTerminal(task);
            capture.assertHasEvent("RESOURCE_RELEASED", mdc ->
                    "task-1".equals(mdc.get("taskId"))
                            && "worker-1".equals(mdc.get("workerId"))
                            && !mdc.containsKey("workerContextId")
                            && "WORKER_LOCK".equals(mdc.get("resourceKind")));
        }
    }

    private static Task task(String taskId, TaskStatus status, boolean foreground) {
        Task task = new Task();
        task.setTid(taskId);
        task.setStatus(status);
        task.getExecutionSpec().setForeground(foreground);
        return task;
    }

    private static TaskWorkAttemptClosedEvent closedAttempt(String taskId,
                                                            String messageId,
                                                            String attemptId,
                                                            String workerId) {
        return TaskWorkAttemptClosedEvent.from(
                taskId,
                messageId,
                attemptId,
                1,
                workerId,
                "group-a",
                null,
                AttemptStatus.SUCCEEDED,
                AttemptFinalReason.SUCCESS
        );
    }

    private static List<com.xa.mass.runtime.api.ActiveLeaseRecord> activeLeases(String taskId,
                                                                                String messageId,
                                                                                String workerId) {
        InMemoryTaskWorkRuntime runtime = new InMemoryTaskWorkRuntime();
        runtime.enqueue(new TaskWorkEnvelope(taskId, messageId, "demo.event",
                        Map.of("target", messageId), null, 0, 3, null, null, Instant.now()),
                WorkEnqueueOptions.DEFAULT);
        runtime.claimReady(taskId,
                List.of(WorkerClaimTarget.groupScoped("group-a", workerId, "batch-1", 1, java.util.Set.of())),
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
        public WorkerDispatchResourceUsage usageForAttempt(Task task) {
            return new WorkerDispatchResourceUsage(false);
        }
    }
}
