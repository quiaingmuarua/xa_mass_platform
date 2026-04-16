package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.util.TraceEventLogCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.same;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskResourceReleaseListenerTest {

    private TaskManager taskManager;
    private WorkerManager workerManager;
    private TaskResourceReleaseListener listener;
    private Consumer<Task> dispatchRequester;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        taskManager = mock(TaskManager.class);
        workerManager = mock(WorkerManager.class);
        dispatchRequester = mock(Consumer.class);
        listener = new TaskResourceReleaseListener(taskManager, workerManager, dispatchRequester);
    }

    @Test
    void terminalTaskReleasesWorkerContextAndUnlocksWorker() {
        Task task = new Task();
        task.setTid("task-1");

        TaskMsg msg = new TaskMsg("msg-1", "task-1", "target-a");
        msg.setWorkerId("worker-1");
        msg.setWorkerContextId("wctx-1");

        WorkerContext wctx = new WorkerContext("wctx-1", "worker-1", "us");
        wctx.bindToTask("task-1");
        wctx.startOccupying();

        when(taskManager.getTaskMessages("task-1")).thenReturn(List.of(msg));
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

        TaskMsg msg = new TaskMsg("msg-1", "task-1", "target-a");
        msg.setWorkerId("worker-1");
        msg.setWorkerContextId("wctx-1");

        WorkerContext wctx = new WorkerContext("wctx-1", "worker-1", "us");
        wctx.bindToTask("task-1");
        wctx.startOccupying();

        when(taskManager.getTaskMessages("task-1")).thenReturn(List.of(msg));
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

        TaskMsg msg = new TaskMsg("msg-1", "task-1", "target-a");
        msg.setWorkerId("worker-1");
        msg.setWorkerContextId("wctx-1");

        WorkerContext wctx = new WorkerContext("wctx-1", "worker-1", "us");
        wctx.bindToTask("other-task");
        wctx.startOccupying();

        when(taskManager.getTaskMessages("task-1")).thenReturn(List.of(msg));
        when(workerManager.getWorkerContextById("wctx-1")).thenReturn(wctx);

        listener.onTaskTerminal(task);

        verify(workerManager, never()).updateWorkerContextById("wctx-1", wctx);
        verify(workerManager).unlockWorker("worker-1");
    }

    @Test
    void finalMessageReleasesIdleWorkerAndRequestsReplenishment() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg finalMsg = new TaskMsg("msg-1", "task-1", "target-a");
        finalMsg.setWorkerId("worker-1");
        finalMsg.setWorkerContextId("wctx-1");
        finalMsg.setStatus(TaskMsgStatus.SUCCESS);

        WorkerContext wctx = new WorkerContext("wctx-1", "worker-1", "us");
        wctx.bindToTask("task-1");
        wctx.startOccupying();

        when(taskManager.getTaskMessages("task-1")).thenReturn(List.of(finalMsg));
        when(taskManager.hasPendingDispatchableMessages("task-1")).thenReturn(true);
        when(workerManager.getWorkerContextById("wctx-1")).thenReturn(wctx);
        when(workerManager.updateWorkerContextById("wctx-1", wctx)).thenReturn(true);

        listener.onTaskMessageFinal(task, finalMsg);

        verify(workerManager).updateWorkerContextById("wctx-1", wctx);
        verify(workerManager).unlockWorker("worker-1");
        verify(dispatchRequester).accept(same(task));
    }

    @Test
    void finalMessageReleasesWorkerWhenRemainingMessagesAreNotProcessing() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg finalMsg = new TaskMsg("msg-1", "task-1", "target-a");
        finalMsg.setWorkerId("worker-1");
        finalMsg.setWorkerContextId("wctx-1");
        finalMsg.setStatus(TaskMsgStatus.SUCCESS);

        TaskMsg retriedMsg = new TaskMsg("msg-2", "task-1", "target-b");
        retriedMsg.setWorkerId("worker-1");
        retriedMsg.setWorkerContextId("wctx-1");
        retriedMsg.setStatus(TaskMsgStatus.INIT);

        WorkerContext wctx = new WorkerContext("wctx-1", "worker-1", "us");
        wctx.bindToTask("task-1");
        wctx.startOccupying();

        when(taskManager.getTaskMessages("task-1")).thenReturn(List.of(finalMsg, retriedMsg));
        when(taskManager.hasPendingDispatchableMessages("task-1")).thenReturn(true);
        when(workerManager.getWorkerContextById("wctx-1")).thenReturn(wctx);
        when(workerManager.updateWorkerContextById("wctx-1", wctx)).thenReturn(true);

        listener.onTaskMessageFinal(task, finalMsg);

        verify(workerManager).updateWorkerContextById("wctx-1", wctx);
        verify(workerManager).unlockWorker("worker-1");
        verify(dispatchRequester).accept(same(task));
    }

    @Test
    void finalMessageKeepsWorkerLockedWhenAnotherMessageIsStillProcessing() {
        Task task = new Task();
        task.setTid("task-1");
        task.setStatus(TaskStatus.RUNNING);

        TaskMsg finalMsg = new TaskMsg("msg-1", "task-1", "target-a");
        finalMsg.setWorkerId("worker-1");
        finalMsg.setWorkerContextId("wctx-1");
        finalMsg.setStatus(TaskMsgStatus.SUCCESS);

        TaskMsg runningMsg = new TaskMsg("msg-2", "task-1", "target-b");
        runningMsg.setWorkerId("worker-1");
        runningMsg.setWorkerContextId("wctx-1");
        runningMsg.setStatus(TaskMsgStatus.RUNNING);

        when(taskManager.getTaskMessages("task-1")).thenReturn(List.of(finalMsg, runningMsg));

        listener.onTaskMessageFinal(task, finalMsg);

        verify(workerManager, never()).unlockWorker("worker-1");
        verify(dispatchRequester, never()).accept(same(task));
    }
}
