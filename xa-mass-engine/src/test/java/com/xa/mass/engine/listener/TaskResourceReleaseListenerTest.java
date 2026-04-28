package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgAttemptStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.TaskRuntimeMaintenancePort;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.util.TraceEventLogCapture;
import com.xa.mass.engine.work.InMemoryTaskWorkRuntime;
import com.xa.mass.engine.work.TaskWorkEnvelope;
import com.xa.mass.engine.work.WorkEnqueueOptions;
import com.xa.mass.engine.work.WorkerClaimTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
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

        TaskMsg msg = new TaskMsg("msg-1", "task-1", java.util.Map.of("target", "target-a"));
        msg.setLatestAttemptWorkerId("worker-1");
        msg.setLatestAttemptWorkerContextId("wctx-1");

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

        TaskMsg msg = new TaskMsg("msg-1", "task-1", java.util.Map.of("target", "target-a"));
        msg.setLatestAttemptWorkerId("worker-1");
        msg.setLatestAttemptWorkerContextId("wctx-1");

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

        TaskMsg msg = new TaskMsg("msg-1", "task-1", java.util.Map.of("target", "target-a"));
        msg.setLatestAttemptWorkerId("worker-1");
        msg.setLatestAttemptWorkerContextId("wctx-1");

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

        TaskMsg finalMsg = new TaskMsg("msg-1", "task-1", java.util.Map.of("target", "target-a"));
        finalMsg.setLatestAttemptWorkerId("worker-1");
        finalMsg.setLatestAttemptWorkerContextId("wctx-1");
        finalMsg.setStatus(TaskMsgStatus.SUCCESS);
        TaskMsgAttempt closedAttempt = closedAttempt("task-1", "msg-1", "attempt-1", "worker-1", "wctx-1");

        WorkerContext wctx = new WorkerContext("wctx-1", "worker-1", java.util.Set.of("us"));
        wctx.bindToTask("task-1");
        wctx.startOccupying();

        when(maintenancePort.hasProcessingMessagesForWorker("task-1", "worker-1")).thenReturn(false);
        when(maintenancePort.hasPendingDispatchableMessages("task-1")).thenReturn(true);
        when(workerManager.getWorkerContextById("wctx-1")).thenReturn(wctx);
        when(workerManager.updateWorkerContextById("wctx-1", wctx)).thenReturn(true);

        listener.onTaskMessageAttemptClosed(task, finalMsg, closedAttempt);

        verify(workerManager).updateWorkerContextById("wctx-1", wctx);
        verify(workerManager).unlockWorker("worker-1");
        verify(maintenancePort).requestTaskDispatch(same(task));
    }

    @Test
    void attemptClosedReleasesWorkerWhenRemainingMessagesAreNotProcessing() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg finalMsg = new TaskMsg("msg-1", "task-1", java.util.Map.of("target", "target-a"));
        finalMsg.setLatestAttemptWorkerId("worker-1");
        finalMsg.setLatestAttemptWorkerContextId("wctx-1");
        finalMsg.setStatus(TaskMsgStatus.SUCCESS);
        TaskMsgAttempt closedAttempt = closedAttempt("task-1", "msg-1", "attempt-1", "worker-1", "wctx-1");

        TaskMsg retriedMsg = new TaskMsg("msg-2", "task-1", java.util.Map.of("target", "target-b"));
        retriedMsg.setLatestAttemptWorkerId("worker-1");
        retriedMsg.setLatestAttemptWorkerContextId("wctx-1");
        retriedMsg.setStatus(TaskMsgStatus.INIT);

        WorkerContext wctx = new WorkerContext("wctx-1", "worker-1", java.util.Set.of("us"));
        wctx.bindToTask("task-1");
        wctx.startOccupying();

        when(maintenancePort.hasProcessingMessagesForWorker("task-1", "worker-1")).thenReturn(false);
        when(maintenancePort.hasPendingDispatchableMessages("task-1")).thenReturn(true);
        when(workerManager.getWorkerContextById("wctx-1")).thenReturn(wctx);
        when(workerManager.updateWorkerContextById("wctx-1", wctx)).thenReturn(true);

        listener.onTaskMessageAttemptClosed(task, finalMsg, closedAttempt);

        verify(workerManager).updateWorkerContextById("wctx-1", wctx);
        verify(workerManager).unlockWorker("worker-1");
        verify(maintenancePort).requestTaskDispatch(same(task));
    }

    @Test
    void attemptClosedKeepsWorkerLockedWhenAnotherMessageIsStillProcessing() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg finalMsg = new TaskMsg("msg-1", "task-1", java.util.Map.of("target", "target-a"));
        finalMsg.setLatestAttemptWorkerId("worker-1");
        finalMsg.setLatestAttemptWorkerContextId("wctx-1");
        finalMsg.setStatus(TaskMsgStatus.SUCCESS);
        TaskMsgAttempt closedAttempt = closedAttempt("task-1", "msg-1", "attempt-1", "worker-1", "wctx-1");

        TaskMsg runningMsg = new TaskMsg("msg-2", "task-1", java.util.Map.of("target", "target-b"));
        runningMsg.setLatestAttemptWorkerId("worker-1");
        runningMsg.setLatestAttemptWorkerContextId("wctx-1");
        runningMsg.setStatus(TaskMsgStatus.RUNNING);
        TaskMsgAttempt activeAttempt = activeAttempt("task-1", "msg-2", "attempt-2", "worker-1", "wctx-1");

        when(maintenancePort.hasProcessingMessagesForWorker("task-1", "worker-1")).thenReturn(true);

        listener.onTaskMessageAttemptClosed(task, finalMsg, closedAttempt);

        verify(workerManager, never()).unlockWorker("worker-1");
        verify(maintenancePort, never()).requestTaskDispatch(any());
    }

    @Test
    void terminalTaskEmitsReleaseFailureTraceWhenWorkerContextCannotReturnToIdle() {
        Task task = new Task();
        task.setTid("task-1");

        TaskMsg msg = new TaskMsg("msg-1", "task-1", java.util.Map.of("target", "target-a"));
        msg.setLatestAttemptWorkerId("worker-1");
        msg.setLatestAttemptWorkerContextId("wctx-1");

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

    private TaskMsgAttempt closedAttempt(String taskId,
                                         String messageId,
                                         String attemptId,
                                         String workerId,
                                         String workerContextId) {
        TaskMsgAttempt attempt = new TaskMsgAttempt(attemptId, taskId, messageId, 1);
        attempt.setWorkerId(workerId);
        attempt.setWorkerContextId(workerContextId);
        assertTrue(attempt.markLeased(LocalDateTime.now().plusMinutes(1)));
        assertTrue(attempt.markDispatched());
        assertTrue(attempt.markRunning());
        assertTrue(attempt.markSucceeded());
        return attempt;
    }

    private TaskMsgAttempt activeAttempt(String taskId,
                                         String messageId,
                                         String attemptId,
                                         String workerId,
                                         String workerContextId) {
        TaskMsgAttempt attempt = new TaskMsgAttempt(attemptId, taskId, messageId, 1);
        attempt.setWorkerId(workerId);
        attempt.setWorkerContextId(workerContextId);
        assertTrue(attempt.markLeased(LocalDateTime.now().plusMinutes(1)));
        assertTrue(attempt.markDispatched());
        assertTrue(attempt.markRunning());
        assertEquals(TaskMsgAttemptStatus.RUNNING, attempt.getStatus());
        return attempt;
    }

    private List<com.xa.mass.engine.work.ActiveLeaseRecord> activeLeases(String taskId,
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
