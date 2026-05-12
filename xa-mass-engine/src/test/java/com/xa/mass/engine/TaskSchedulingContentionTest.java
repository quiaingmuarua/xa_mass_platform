package com.xa.mass.engine;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.TaskWorkStats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskSchedulingContentionTest {

    @Test
    void multipleReadyBatchTasksCompeteForSingleContextWithoutDoubleAssignment() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorkerWithContext("worker-single", "ctx-single", "us");
        Task firstTask = harness.createReadyBatchTask("contention-first", List.of(harness.item("first")));
        Task secondTask = harness.createReadyBatchTask("contention-second", List.of(harness.item("second")));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(firstTask.getTid())));
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(secondTask.getTid())));

        Task updatedFirstTask = harness.taskManager.getTask(firstTask.getTid());
        Task updatedSecondTask = harness.taskManager.getTask(secondTask.getTid());
        TaskWorkStats firstStats = harness.stats(firstTask.getTid());
        TaskWorkStats secondStats = harness.stats(secondTask.getTid());
        List<ActiveLeaseRecord> firstLeases = harness.activeLeases(firstTask.getTid());

        assertEquals(TaskStatus.RUNNING, updatedFirstTask.getStatus());
        assertEquals(TaskStatus.READY, updatedSecondTask.getStatus());
        assertEquals(0, firstStats.readyCount());
        assertEquals(1, firstStats.inflightCount());
        assertEquals(1, secondStats.readyCount());
        assertEquals(0, secondStats.inflightCount());
        assertEquals(1, firstLeases.size());
        assertEquals("worker-single", firstLeases.getFirst().workerId());
        assertEquals("ctx-single", firstLeases.getFirst().workerContextId());

        AssignmentRecord rejectedRecord = harness.record(secondTask.getTid(), "worker-single");
        assertEquals(AssignmentResult.CONFLICT, rejectedRecord.getResult());
        assertEquals("worker locked", rejectedRecord.getReason());
        assertEquals(1, harness.successfulMessageAssignments(firstTask.getTid(), "worker-single"));
        assertEquals(0, harness.successfulMessageAssignments(secondTask.getTid(), "worker-single"));
        assertEquals(WorkerContextStatus.OCCUPIED,
                harness.workerManager.getWorkerContextById("ctx-single").getStatus());
    }

    @Test
    void pausedBlockedAndTerminatedTasksDoNotDispatchEvenWhenReadyWorkExists() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorkerWithContext("worker-gate", "ctx-gate", "us");
        Task pausedTask = harness.createReadyBatchTask("paused-gate", List.of(harness.item("paused")));
        Task blockedTask = harness.createReadyBatchTask("blocked-gate", List.of(harness.item("blocked")));
        Task terminalTask = harness.createReadyBatchTask("terminal-gate", List.of(harness.item("terminal")));

        assertTrue(harness.taskManager.pauseTask(pausedTask.getTid()));
        assertTrue(harness.taskManager.blockTask(blockedTask.getTid()));
        assertTrue(harness.taskManager.cancelTask(terminalTask.getTid()));

        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(pausedTask.getTid())));
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(blockedTask.getTid())));
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(terminalTask.getTid())));

        assertEquals(1, harness.stats(pausedTask.getTid()).readyCount());
        assertEquals(1, harness.stats(blockedTask.getTid()).readyCount());
        assertEquals(0, harness.stats(terminalTask.getTid()).totalCount());
        assertTrue(harness.activeLeases(pausedTask.getTid()).isEmpty());
        assertTrue(harness.activeLeases(blockedTask.getTid()).isEmpty());
        assertTrue(harness.activeLeases(terminalTask.getTid()).isEmpty());
        assertEquals(TaskStatus.PAUSED, harness.taskManager.getTask(pausedTask.getTid()).getStatus());
        assertEquals(TaskStatus.BLOCKED, harness.taskManager.getTask(blockedTask.getTid()).getStatus());
        assertEquals(TaskStatus.TERMINAL, harness.taskManager.getTask(terminalTask.getTid()).getStatus());
    }
}
