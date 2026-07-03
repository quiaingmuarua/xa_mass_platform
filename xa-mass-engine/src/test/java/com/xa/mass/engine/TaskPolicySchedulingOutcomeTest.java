package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;
import com.xa.mass.task.runtime.TaskRuntimeProgressSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskPolicySchedulingOutcomeTest {

    @Test
    void batchTaskTerminalsWhenSealedWorkDrainsThroughSchedulingPath() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-batch", "us");
        Task task = harness.createBatchTask(
                "batch-drain-terminal",
                List.of(harness.item("batch")),
                0,
                1
        );
        assertTrue(harness.taskManager.approveTask(task.getTid()).accepted());

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));
        ActiveLeaseRepairCandidate lease = harness.activeLeases(task.getTid()).getFirst();
        assertTrue(harness.taskRuntimeServingLane.ingestTaskResult(
                task.getTid(),
                lease.messageId(),
                true,
                "batch done",
                null,
                java.util.Map.of("source", "batch-worker")
        ));

        Task completedTask = harness.taskManager.getTask(task.getTid());
        TaskRuntimeProgressSnapshot stats = harness.stats(task.getTid());
        assertEquals(TaskStatus.TERMINAL, completedTask.getStatus());
        assertEquals(TaskIntakeStatus.SEALED, completedTask.getIntakeStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_SUCCEEDED, completedTask.getTerminalReason());
        assertEquals(1, stats.successCount());
        assertEquals(1, stats.finalCount());
        assertEquals(0, stats.readyCount());
        assertEquals(0, stats.activeCount());
    }

    @Test
    void sessionTaskDoesNotAutoTerminalWhenQueueDrainsThroughSchedulingPath() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-session", "us");
        Task task = harness.createSessionTask(
                "session-drain-open",
                List.of(harness.item("first")),
                0,
                1
        );
        assertTrue(harness.taskManager.approveTask(task.getTid()).accepted());

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));
        ActiveLeaseRepairCandidate firstLease = harness.activeLeases(task.getTid()).getFirst();
        assertTrue(harness.taskRuntimeServingLane.ingestTaskResult(
                task.getTid(),
                firstLease.messageId(),
                true,
                "session first done",
                null,
                java.util.Map.of("source", "session-worker")
        ));

        Task drainedTask = harness.taskManager.getTask(task.getTid());
        TaskRuntimeProgressSnapshot drainedStats = harness.stats(task.getTid());
        assertEquals(TaskStatus.RUNNING, drainedTask.getStatus());
        assertEquals(TaskIntakeStatus.OPEN, drainedTask.getIntakeStatus());
        assertNull(drainedTask.getTerminalReason());
        assertEquals(1, drainedStats.successCount());
        assertEquals(1, drainedStats.finalCount());
        assertEquals(0, drainedStats.readyCount());
        assertEquals(0, drainedStats.activeCount());

        assertEquals(1, harness.taskManager.appendTaskItems(task.getTid(), List.of(harness.item("second"))).acceptedCount());
        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));

        List<ActiveLeaseRepairCandidate> activeLeases = harness.activeLeases(task.getTid());
        assertEquals(1, activeLeases.size());
        assertEquals("worker-session", activeLeases.getFirst().workerId());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(task.getTid()).getStatus());
        assertNull(harness.taskManager.getTask(task.getTid()).getTerminalReason());
    }
}
