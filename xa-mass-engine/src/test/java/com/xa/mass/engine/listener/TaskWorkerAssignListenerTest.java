package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.strategy.TaskWorkerMatchingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TaskWorkerAssignListenerTest {

    private TaskWorkerMatchingStrategy matchingStrategy;
    private WorkerManager workerManager;
    private TaskMsgAssignListener msgAssignListener;
    private TaskManager taskManager;
    private TaskWorkerAssignListener listener;

    @BeforeEach
    void setUp() {
        matchingStrategy = mock(TaskWorkerMatchingStrategy.class);
        workerManager = mock(WorkerManager.class);
        msgAssignListener = mock(TaskMsgAssignListener.class);
        taskManager = mock(TaskManager.class);
        listener = new TaskWorkerAssignListener(matchingStrategy, workerManager, msgAssignListener, taskManager);
    }

    @Test
    void onTaskAssignTransitionsReadyTaskToRunningAndDispatches() {
        Task task = createTask(10, 5, 1, TaskStatus.READY);
        Worker worker = createWorker("worker-1");

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(10);
        when(matchingStrategy.matchWorkers(same(task), eq(2))).thenReturn(List.of(worker));
        when(msgAssignListener.onMsgAssign(same(task), eq(List.of(worker)))).thenReturn(List.of(msg("m1", "worker-1")));

        assertTrue(listener.onTaskAssign(task));

        assertEquals(TaskStatus.RUNNING, task.getStatus());
        assertEquals(1, task.getScheduleDeviceCnt());
        verify(matchingStrategy).matchWorkers(same(task), eq(2));
        verify(taskManager).updateTask(same(task));
        verify(msgAssignListener).onMsgAssign(same(task), eq(List.of(worker)));
    }

    @Test
    void onTaskAssignUsesRunTaskMinWorkerCountWhenItExceedsCalculatedNeed() {
        Task task = createTask(3, 10, 4, TaskStatus.READY);
        Worker worker1 = createWorker("worker-1");
        Worker worker2 = createWorker("worker-2");
        Worker worker3 = createWorker("worker-3");
        Worker worker4 = createWorker("worker-4");

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(3);
        when(matchingStrategy.matchWorkers(same(task), eq(4))).thenReturn(List.of(worker1, worker2, worker3, worker4));
        when(msgAssignListener.onMsgAssign(same(task), eq(List.of(worker1)))).thenReturn(List.of(msg("m1", "worker-1")));

        assertTrue(listener.onTaskAssign(task));

        verify(matchingStrategy).matchWorkers(same(task), eq(4));
        assertEquals(TaskStatus.RUNNING, task.getStatus());
        assertEquals(1, task.getScheduleDeviceCnt());
        verify(taskManager).updateTask(same(task));
        verify(msgAssignListener).onMsgAssign(same(task), eq(List.of(worker1)));
        verify(workerManager).unlockWorker("worker-2");
        verify(workerManager).unlockWorker("worker-3");
        verify(workerManager).unlockWorker("worker-4");
    }

    @Test
    void onTaskAssignReturnsWhenNoWorkerMatches() {
        Task task = createTask(10, 5, 1, TaskStatus.READY);

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(10);
        when(matchingStrategy.matchWorkers(same(task), eq(2))).thenReturn(List.of());

        assertFalse(listener.onTaskAssign(task));

        assertEquals(TaskStatus.READY, task.getStatus());
        assertEquals(0, task.getScheduleDeviceCnt());
        verify(matchingStrategy).matchWorkers(same(task), eq(2));
        verify(taskManager).countPendingDispatchableMessages(task.getTid());
        verifyNoInteractions(msgAssignListener);
    }

    @Test
    void onTaskAssignSkipsDispatchIfTaskLeavesReadyDuringMatching() {
        Task task = createTask(10, 5, 1, TaskStatus.READY);
        Worker worker = createWorker("worker-1");

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(10);
        when(matchingStrategy.matchWorkers(same(task), eq(2))).thenAnswer(invocation -> {
            task.setStatus(TaskStatus.PAUSED);
            return List.of(worker);
        });

        assertFalse(listener.onTaskAssign(task));

        assertEquals(TaskStatus.PAUSED, task.getStatus());
        assertEquals(0, task.getScheduleDeviceCnt());
        verify(matchingStrategy).matchWorkers(same(task), eq(2));
        verify(workerManager).unlockWorker("worker-1");
        verify(taskManager, never()).updateTask(task);
        verifyNoInteractions(msgAssignListener);
    }

    @Test
    void onTaskAssignKeepsTaskReadyUntilMinimumWorkerCountIsMet() {
        Task task = createTask(1, 1, 2, TaskStatus.READY);
        Worker worker = createWorker("worker-1");

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(1);
        when(matchingStrategy.matchWorkers(same(task), eq(2))).thenReturn(List.of(worker));

        assertFalse(listener.onTaskAssign(task));

        assertEquals(TaskStatus.READY, task.getStatus());
        assertEquals(0, task.getScheduleDeviceCnt());
        verify(matchingStrategy).matchWorkers(same(task), eq(2));
        verify(workerManager).unlockWorker("worker-1");
        verify(taskManager, never()).updateTask(task);
        verifyNoInteractions(msgAssignListener);
    }

    @Test
    void matchWorkersWithRulesDelegatesToInjectedStrategy() {
        Task task = createTask(10, 5, 1, TaskStatus.READY);
        Worker worker = createWorker("worker-1");

        when(matchingStrategy.matchWorkers(same(task), eq(3))).thenReturn(List.of(worker));

        List<Worker> matched = listener.matchWorkersWithRules(task, 3);

        assertEquals(List.of(worker), matched);
        verify(matchingStrategy).matchWorkers(same(task), eq(3));
    }

    @Test
    void runningTaskCanBeReplenishedWithoutLeavingRunning() {
        Task task = createTask(5, 2, 1, TaskStatus.RUNNING);
        Worker worker = createWorker("worker-1");

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(2);
        when(matchingStrategy.matchWorkers(same(task), eq(1))).thenReturn(List.of(worker));
        when(msgAssignListener.onMsgAssign(same(task), eq(List.of(worker)))).thenReturn(List.of(msg("m1", "worker-1")));

        assertTrue(listener.onTaskAssign(task));

        assertEquals(TaskStatus.RUNNING, task.getStatus());
        assertEquals(1, task.getScheduleDeviceCnt());
        verify(taskManager).updateTask(same(task));
        verify(msgAssignListener).onMsgAssign(same(task), eq(List.of(worker)));
    }

    private Task createTask(int targetNumber, int batchSize, int minWorkerCount, TaskStatus status) {
        Task task = new Task();
        task.setTid("task-1");
        task.setTaskRoutingCountryCode("us");
        task.setTaskTargetNumber(targetNumber);
        task.setBatchSize(batchSize);
        task.setRunTaskMinDeviceCnt(minWorkerCount);
        task.setStatus(status);
        return task;
    }

    private Worker createWorker(String workerId) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setWorkerGroupId("pool-a");
        worker.setSupportedProjects(List.of("demoApp"));
        return worker;
    }

    private TaskMsg msg(String msgId, String workerId) {
        TaskMsg taskMsg = new TaskMsg(msgId, "task-1", "target");
        taskMsg.setWorkerId(workerId);
        return taskMsg;
    }
}
