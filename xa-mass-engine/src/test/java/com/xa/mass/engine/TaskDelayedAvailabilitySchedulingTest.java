package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;
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

        harness.addWorker("worker-delayed", "us");

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        List<ActiveLeaseRepairCandidate> activeLeases = harness.activeLeases(task.getTid());
        assertEquals(1, activeLeases.size());
        assertEquals("worker-delayed", activeLeases.getFirst().workerId());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(task.getTid()).getStatus());
        assertEquals(0, harness.stats(task.getTid()).readyCount());
        assertEquals(1, harness.stats(task.getTid()).activeCount());
    }

    @Test
    void workerLevelSchedulingDoesNotRequireContextRegistration() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-stateless", "us");
        Task task = harness.createReadyBatchTask("delayed-context-unblock", List.of(harness.item("delayed")));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        List<ActiveLeaseRepairCandidate> activeLeases = harness.activeLeases(task.getTid());
        assertEquals(1, activeLeases.size());
        assertEquals("worker-stateless", activeLeases.getFirst().workerId());
        assertTrue(harness.workerManager.hasWorkerExclusiveLease("worker-stateless"));
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(task.getTid()).getStatus());
        assertEquals(1, harness.successfulMessageAssignments(task.getTid(), "worker-stateless"));
    }
}
