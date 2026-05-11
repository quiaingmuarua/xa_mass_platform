package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.TaskMessageCompatibilityState.AttemptFinalReason;
import com.xa.mass.engine.TaskMessageCompatibilityState.AttemptStatus;
import com.xa.mass.engine.TaskMessageAttemptClosedEvent;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.TaskRuntimeMaintenancePort;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.util.TraceEventLogCapture;
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

class TaskResourceReleaseListenerTest {

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
    void terminalTaskReleasesWorkerContextAndUnlocksWorker() {
        Task task = new Task();
        task.setTid("task-1");

        WorkerContext wctx = new WorkerContext("wctx-1", "worker-1", java.util.Set.of("us"));
        wctx.bindToTask("task-1");
        wctx.startOccupying();

        when(maintenancePort.getActiveLeases("task-1")).thenReturn(activeLeases("task-1", "msg-1", "worker-1", "wctx-1"));
        when(workerManager.getWorkerContextById("wctx-1")).thenReturn(wctx);
        when(workerManager.updateWorkerContextById("wctx-1", wctx)).thenReturn(true);

        listener.onTaskTerminal(task);

        verify(workerManager).updateWorkerContextById("wctx-1", wctx);
        verify(workerManager).unlockWorker("worker-1");
    }

    @Test
    void terminalTaskEmitsReleaseTrace() {
        Task task = new Task();
        task.setTid("task-1");

        WorkerContext wctx = new WorkerContext("wctx-1", "worker-1", java.util.Set.of("us"));
        wctx.bindToTask("task-1");
        wctx.startOccupying();

        when(maintenancePort.getActiveLeases("task-1")).thenReturn(activeLeases("task-1", "msg-1", "worker-1", "wctx-1"));
        when(workerManager.getWorkerContextById("wctx-1")).thenReturn(wctx);
        when(workerManager.updateWorkerContextById("wctx-1", wctx)).thenReturn(true);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            listener.onTaskTerminal(task);
            capture.assertHasEvent("WORKER_CONTEXT_STATUS_TRANSITION", mdc ->
                    "task-1".equals(mdc.get("taskId"))
                            && "wctx-1".equals(mdc.get("workerContextId"))
                            && "OCCUPIED".equals(mdc.get("fromStatus"))
                            && "IDLE".equals(mdc.get("toStatus")));
            capture.assertHasEvent("RESOURCE_RELEASED", mdc ->
                    "task-1".equals(mdc.get("taskId"))
                            && "worker-1".equals(mdc.get("workerId"))
                            && "wctx-1".equals(mdc.get("workerContextId")));
        }
    }

    @Test
    void listenerDoesNotReleaseWorkerContextOwnedByAnotherTask() {
        Task task = new Task();
        task.setTid("task-1");

        WorkerContext wctx = new WorkerContext("wctx-1", "worker-1", java.util.Set.of("us"));
        wctx.bindToTask("other-task");
        wctx.startOccupying();

        when(maintenancePort.getActiveLeases("task-1")).thenReturn(activeLeases("task-1", "msg-1", "worker-1", "wctx-1"));
        when(workerManager.getWorkerContextById("wctx-1")).thenReturn(wctx);

        listener.onTaskTerminal(task);

        verify(workerManager, never()).updateWorkerContextById("wctx-1", wctx);
        verify(workerManager).unlockWorker("worker-1");
    }

    @Test
    void attemptClosedReleasesIdleWorkerAndRequestsReplenishment() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);

        TaskMessageAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1", "wctx-1");

        WorkerContext wctx = new WorkerContext("wctx-1", "worker-1", java.util.Set.of("us"));
        wctx.bindToTask("task-1");
        wctx.startOccupying();

        when(maintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(false);
        when(maintenancePort.hasDispatchReadyWork("task-1")).thenReturn(true);
        when(workerManager.getWorkerContextById("wctx-1")).thenReturn(wctx);
        when(workerManager.updateWorkerContextById("wctx-1", wctx)).thenReturn(true);

        listener.onTaskMessageAttemptClosed(task, closedAttempt);

        verify(workerManager).updateWorkerContextById("wctx-1", wctx);
        verify(workerManager).unlockWorker("worker-1");
        verify(maintenancePort).requestTaskDispatch(same(task));
    }

    @Test
    void attemptClosedReleasesWorkerWhenRemainingMessagesAreNotProcessing() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);

        TaskMessageAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1", "wctx-1");

        WorkerContext wctx = new WorkerContext("wctx-1", "worker-1", java.util.Set.of("us"));
        wctx.bindToTask("task-1");
        wctx.startOccupying();

        when(maintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(false);
        when(maintenancePort.hasDispatchReadyWork("task-1")).thenReturn(true);
        when(workerManager.getWorkerContextById("wctx-1")).thenReturn(wctx);
        when(workerManager.updateWorkerContextById("wctx-1", wctx)).thenReturn(true);

        listener.onTaskMessageAttemptClosed(task, closedAttempt);

        verify(workerManager).updateWorkerContextById("wctx-1", wctx);
        verify(workerManager).unlockWorker("worker-1");
        verify(maintenancePort).requestTaskDispatch(same(task));
    }

    @Test
    void attemptClosedKeepsWorkerLockedWhenAnotherMessageIsStillProcessing() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);

        TaskMessageAttemptClosedEvent closedAttempt =
                closedAttempt("task-1", "msg-1", "attempt-1", "worker-1", "wctx-1");

        when(maintenancePort.hasActiveWorkForWorker("task-1", "worker-1")).thenReturn(true);

        listener.onTaskMessageAttemptClosed(task, closedAttempt);

        verify(workerManager, never()).unlockWorker("worker-1");
        verify(maintenancePort, never()).requestTaskDispatch(any());
    }

    @Test
    void terminalTaskEmitsReleaseFailureTraceWhenWorkerContextCannotReturnToIdle() {
        Task task = new Task();
        task.setTid("task-1");

        WorkerContext wctx = new WorkerContext("wctx-1", "worker-1", java.util.Set.of("us"));
        wctx.bindToTask("task-1");
        wctx.startOccupying();
        wctx.block();

        when(maintenancePort.getActiveLeases("task-1")).thenReturn(activeLeases("task-1", "msg-1", "worker-1", "wctx-1"));
        when(workerManager.getWorkerContextById("wctx-1")).thenReturn(wctx);

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            listener.onTaskTerminal(task);
            capture.assertHasEvent("RESOURCE_RELEASE_FAILED", mdc ->
                    "task-1".equals(mdc.get("taskId"))
                            && "worker-1".equals(mdc.get("workerId"))
                            && "wctx-1".equals(mdc.get("workerContextId")));
        }

        verify(workerManager, never()).updateWorkerContextById("wctx-1", wctx);
        verify(workerManager).unlockWorker("worker-1");
    }

    private TaskMessageAttemptClosedEvent closedAttempt(String taskId,
                                                        String messageId,
                                                        String attemptId,
                                                        String workerId,
                                                        String workerContextId) {
        return TaskMessageAttemptClosedEvent.from(
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
}


