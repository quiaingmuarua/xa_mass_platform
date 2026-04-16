package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.model.MatchedWorkerContext;
import com.xa.mass.engine.strategy.TaskWorkerMatchingStrategy;
import com.xa.mass.engine.util.TraceEventLogCapture;
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
        MatchedWorkerContext matchedWorker = matched(worker, "ctx-1");

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(10);
        when(matchingStrategy.matchWorkers(same(task), eq(2))).thenReturn(List.of(matchedWorker));
        when(msgAssignListener.onMsgAssign(same(task), eq(List.of(matchedWorker)))).thenReturn(List.of(msg("m1", "worker-1")));

        assertTrue(listener.onTaskAssign(task));

        assertEquals(TaskStatus.RUNNING, task.getStatus());
        assertEquals(1, task.getPeakAssignedWorkerCount());
        verify(matchingStrategy).matchWorkers(same(task), eq(2));
        verify(taskManager).updateTask(same(task));
        verify(msgAssignListener).onMsgAssign(same(task), eq(List.of(matchedWorker)));
    }

    @Test
    void onTaskAssignEmitsReadyToRunningTrace() {
        Task task = createTask(2, 2, 1, TaskStatus.READY);
        Worker worker = createWorker("worker-1");
        MatchedWorkerContext matchedWorker = matched(worker, "ctx-1");

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(2);
        when(matchingStrategy.matchWorkers(same(task), eq(1))).thenReturn(List.of(matchedWorker));
        when(msgAssignListener.onMsgAssign(same(task), eq(List.of(matchedWorker)))).thenReturn(List.of(msg("m1", "worker-1")));

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(listener.onTaskAssign(task));
            capture.assertHasEvent("TASK_STATUS_TRANSITION", mdc ->
                    "task-1".equals(mdc.get("taskId"))
                            && "READY".equals(mdc.get("fromStatus"))
                            && "RUNNING".equals(mdc.get("toStatus"))
                            && "ASSIGNMENT_SUCCEEDED".equals(mdc.get("trigger")));
        }
    }

    @Test
    void onTaskAssignEmitsDispatchRequestedTrace() {
        Task task = createTask(2, 2, 1, TaskStatus.READY);
        Worker worker = createWorker("worker-1");
        MatchedWorkerContext matchedWorker = matched(worker, "ctx-1");

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(2);
        when(matchingStrategy.matchWorkers(same(task), eq(1))).thenReturn(List.of(matchedWorker));
        when(msgAssignListener.onMsgAssign(same(task), eq(List.of(matchedWorker)))).thenReturn(List.of(msg("m1", "worker-1")));

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(listener.onTaskAssign(task));
            capture.assertHasEvent("DISPATCH_REQUESTED", mdc ->
                    "task-1".equals(mdc.get("taskId"))
                            && "ON_TASK_ASSIGN".equals(mdc.get("trigger"))
                            && "TaskWorkerAssignListener".equals(mdc.get("source")));
        }
    }

    @Test
    void onTaskAssignEmitsAssignmentSummary() {
        Task task = createTask(2, 2, 1, TaskStatus.READY);
        Worker worker = createWorker("worker-1");
        MatchedWorkerContext matchedWorker = matched(worker, "ctx-1");

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(2);
        when(matchingStrategy.matchWorkers(same(task), eq(1))).thenReturn(List.of(matchedWorker));
        when(msgAssignListener.onMsgAssign(same(task), eq(List.of(matchedWorker)))).thenReturn(List.of(msg("m1", "worker-1")));

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertTrue(listener.onTaskAssign(task));
            capture.assertHasEvent("ASSIGNMENT_SUMMARY", mdc ->
                    "task-1".equals(mdc.get("taskId"))
                            && "READY".equals(mdc.get("initialStatus"))
                            && "RUNNING".equals(mdc.get("currentStatus"))
                            && "2".equals(mdc.get("pendingDispatchCount"))
                            && "1".equals(mdc.get("matchedWorkerCount"))
                            && "1".equals(mdc.get("dispatchCandidateCount"))
                            && "1".equals(mdc.get("dispatchedMessageCount"))
                            && "1".equals(mdc.get("usedWorkerCount"))
                            && "SUCCESS".equals(mdc.get("result")));
        }
    }

    @Test
    void onTaskAssignUsesMinRequiredWorkerCountWhenItExceedsCalculatedNeed() {
        Task task = createTask(3, 10, 4, TaskStatus.READY);
        Worker worker1 = createWorker("worker-1");
        Worker worker2 = createWorker("worker-2");
        Worker worker3 = createWorker("worker-3");
        Worker worker4 = createWorker("worker-4");
        MatchedWorkerContext matched1 = matched(worker1, "ctx-1");
        MatchedWorkerContext matched2 = matched(worker2, "ctx-2");
        MatchedWorkerContext matched3 = matched(worker3, "ctx-3");
        MatchedWorkerContext matched4 = matched(worker4, "ctx-4");

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(3);
        when(matchingStrategy.matchWorkers(same(task), eq(4))).thenReturn(List.of(matched1, matched2, matched3, matched4));
        when(msgAssignListener.onMsgAssign(same(task), eq(List.of(matched1)))).thenReturn(List.of(msg("m1", "worker-1")));

        assertTrue(listener.onTaskAssign(task));

        verify(matchingStrategy).matchWorkers(same(task), eq(4));
        assertEquals(TaskStatus.RUNNING, task.getStatus());
        assertEquals(1, task.getPeakAssignedWorkerCount());
        verify(taskManager).updateTask(same(task));
        verify(msgAssignListener).onMsgAssign(same(task), eq(List.of(matched1)));
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
        assertEquals(0, task.getPeakAssignedWorkerCount());
        verify(matchingStrategy).matchWorkers(same(task), eq(2));
        verify(taskManager).countPendingDispatchableMessages(task.getTid());
        verifyNoInteractions(msgAssignListener);
    }

    @Test
    void onTaskAssignSkipsDispatchIfTaskLeavesReadyDuringMatching() {
        Task task = createTask(10, 5, 1, TaskStatus.READY);
        Worker worker = createWorker("worker-1");
        MatchedWorkerContext matchedWorker = matched(worker, "ctx-1");

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(10);
        when(matchingStrategy.matchWorkers(same(task), eq(2))).thenAnswer(invocation -> {
            task.setStatus(TaskStatus.PAUSED);
            return List.of(matchedWorker);
        });

        assertFalse(listener.onTaskAssign(task));

        assertEquals(TaskStatus.PAUSED, task.getStatus());
        assertEquals(0, task.getPeakAssignedWorkerCount());
        verify(matchingStrategy).matchWorkers(same(task), eq(2));
        verify(workerManager).unlockWorker("worker-1");
        verify(taskManager, never()).updateTask(task);
        verifyNoInteractions(msgAssignListener);
    }

    @Test
    void onTaskAssignEmitsDispatchSkippedTraceWhenTaskLeavesReadyDuringMatching() {
        Task task = createTask(10, 5, 1, TaskStatus.READY);
        Worker worker = createWorker("worker-1");
        MatchedWorkerContext matchedWorker = matched(worker, "ctx-1");

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(10);
        when(matchingStrategy.matchWorkers(same(task), eq(2))).thenAnswer(invocation -> {
            task.setStatus(TaskStatus.PAUSED);
            return List.of(matchedWorker);
        });

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertFalse(listener.onTaskAssign(task));
            capture.assertHasEvent("DISPATCH_SKIPPED", mdc ->
                    "task-1".equals(mdc.get("taskId"))
                            && "ON_TASK_ASSIGN".equals(mdc.get("trigger"))
                            && "SKIPPED".equals(mdc.get("result"))
                            && mdc.get("reason").contains("status changed during matching"));
        }
    }

    @Test
    void onTaskAssignKeepsTaskReadyUntilMinimumWorkerCountIsMet() {
        Task task = createTask(1, 1, 2, TaskStatus.READY);
        Worker worker = createWorker("worker-1");
        MatchedWorkerContext matchedWorker = matched(worker, "ctx-1");

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(1);
        when(matchingStrategy.matchWorkers(same(task), eq(2))).thenReturn(List.of(matchedWorker));

        assertFalse(listener.onTaskAssign(task));

        assertEquals(TaskStatus.READY, task.getStatus());
        assertEquals(0, task.getPeakAssignedWorkerCount());
        verify(matchingStrategy).matchWorkers(same(task), eq(2));
        verify(workerManager).unlockWorker("worker-1");
        verify(taskManager, never()).updateTask(task);
        verifyNoInteractions(msgAssignListener);
    }

    @Test
    void onTaskAssignEmitsDispatchSkippedTraceWhenBelowMinimumWorkerCount() {
        Task task = createTask(1, 1, 2, TaskStatus.READY);
        Worker worker = createWorker("worker-1");
        MatchedWorkerContext matchedWorker = matched(worker, "ctx-1");

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(1);
        when(matchingStrategy.matchWorkers(same(task), eq(2))).thenReturn(List.of(matchedWorker));

        try (TraceEventLogCapture capture = new TraceEventLogCapture()) {
            assertFalse(listener.onTaskAssign(task));
            capture.assertHasEvent("DISPATCH_SKIPPED", mdc ->
                    "task-1".equals(mdc.get("taskId"))
                            && "2".equals(mdc.get("requiredMinWorkerCount"))
                            && mdc.get("reason").contains("below minimum start gate"));
        }
    }

    @Test
    void matchWorkersWithRulesDelegatesToInjectedStrategy() {
        Task task = createTask(10, 5, 1, TaskStatus.READY);
        Worker worker = createWorker("worker-1");
        MatchedWorkerContext matchedWorker = matched(worker, "ctx-1");

        when(matchingStrategy.matchWorkers(same(task), eq(3))).thenReturn(List.of(matchedWorker));

        List<MatchedWorkerContext> matched = listener.matchWorkersWithRules(task, 3);

        assertEquals(List.of(matchedWorker), matched);
        verify(matchingStrategy).matchWorkers(same(task), eq(3));
    }

    @Test
    void runningTaskCanBeReplenishedWithoutLeavingRunning() {
        Task task = createTask(5, 2, 1, TaskStatus.RUNNING);
        Worker worker = createWorker("worker-1");
        MatchedWorkerContext matchedWorker = matched(worker, "ctx-1");

        when(taskManager.countPendingDispatchableMessages(task.getTid())).thenReturn(2);
        when(matchingStrategy.matchWorkers(same(task), eq(1))).thenReturn(List.of(matchedWorker));
        when(msgAssignListener.onMsgAssign(same(task), eq(List.of(matchedWorker)))).thenReturn(List.of(msg("m1", "worker-1")));

        assertTrue(listener.onTaskAssign(task));

        assertEquals(TaskStatus.RUNNING, task.getStatus());
        assertEquals(1, task.getPeakAssignedWorkerCount());
        verify(taskManager).updateTask(same(task));
        verify(msgAssignListener).onMsgAssign(same(task), eq(List.of(matchedWorker)));
    }

    private Task createTask(int targetNumber, int batchSize, int minWorkerCount, TaskStatus status) {
        Task task = new Task();
        task.setTid("task-1");
        task.setTaskRoutingCode("us");
        task.setTaskTargetNumber(targetNumber);
        task.setBatchSize(batchSize);
        task.setMinRequiredWorkerCount(minWorkerCount);
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

    private MatchedWorkerContext matched(Worker worker, String workerContextId) {
        WorkerContext workerContext = new WorkerContext();
        workerContext.setWorkerId(worker.getWorkerId());
        workerContext.setWorkerContextId(workerContextId);
        return new MatchedWorkerContext(worker, workerContext);
    }
}
