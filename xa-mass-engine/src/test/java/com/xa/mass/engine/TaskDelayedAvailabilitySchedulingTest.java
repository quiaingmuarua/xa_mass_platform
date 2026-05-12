package com.xa.mass.engine;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskDelayedAvailabilitySchedulingTest {

    @Test
    void readyTaskDispatchesWhenEligibleWorkerRegistersAfterInitialEmptyCompetition() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        Task task = harness.createReadyBatchTask("delayed-worker-registration", List.of(harness.item("delayed")));

        assertTrue(harness.activeLeases(task.getTid()).isEmpty());
        assertEquals(TaskStatus.READY, harness.taskManager.getTask(task.getTid()).getStatus());
        assertEquals(1, harness.stats(task.getTid()).readyCount());
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        harness.addWorkerWithContext("worker-delayed", "ctx-delayed", "us");

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        List<ActiveLeaseRecord> activeLeases = harness.activeLeases(task.getTid());
        assertEquals(1, activeLeases.size());
        assertEquals("worker-delayed", activeLeases.getFirst().workerId());
        assertEquals("ctx-delayed", activeLeases.getFirst().workerContextId());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(task.getTid()).getStatus());
        assertEquals(0, harness.stats(task.getTid()).readyCount());
        assertEquals(1, harness.stats(task.getTid()).inflightCount());
    }

    @Test
    void blockedWorkerContextCanBecomeEligibleAndDispatchWaitingReadyTask() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorkerWithContext(
                "worker-blocked-context",
                "ctx-blocked-context",
                "us",
                WorkerContextStatus.BLOCKED
        );
        Task task = harness.createReadyBatchTask("delayed-context-unblock", List.of(harness.item("delayed")));

        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        assertEquals(TaskStatus.READY, harness.taskManager.getTask(task.getTid()).getStatus());
        assertEquals(1, harness.stats(task.getTid()).readyCount());
        assertTrue(harness.activeLeases(task.getTid()).isEmpty());
        AssignmentRecord blockedRecord = harness.record(task.getTid(), "worker-blocked-context");
        assertEquals(AssignmentResult.RESOURCE_UNAVAILABLE, blockedRecord.getResult());
        assertEquals("workerContext not allocatable", blockedRecord.getReason());

        WorkerContext workerContext = harness.workerManager.getWorkerContextById("ctx-blocked-context");
        assertTrue(workerContext.unblock());
        assertTrue(harness.workerManager.updateWorkerContextById("ctx-blocked-context", workerContext));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        List<ActiveLeaseRecord> activeLeases = harness.activeLeases(task.getTid());
        assertEquals(1, activeLeases.size());
        assertEquals("worker-blocked-context", activeLeases.getFirst().workerId());
        assertEquals("ctx-blocked-context", activeLeases.getFirst().workerContextId());
        assertEquals(WorkerContextStatus.OCCUPIED,
                harness.workerManager.getWorkerContextById("ctx-blocked-context").getStatus());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(task.getTid()).getStatus());
    }
}
