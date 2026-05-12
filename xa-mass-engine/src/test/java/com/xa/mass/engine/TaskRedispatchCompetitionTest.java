package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.TaskWorkStats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRedispatchCompetitionTest {

    @Test
    void leaseExpiryReentersBatchTaskIntoCompetitionPoolAndRedispatchesSameWorkOnce() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorkerWithContext("worker-retry", "ctx-retry", "us");
        Task task = harness.createBatchTask("redispatch-after-expiry", List.of(harness.item("retry")), 1, 1);
        assertTrue(harness.taskManager.approveTask(task.getTid()));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));
        ActiveLeaseRecord firstLease = harness.activeLeases(task.getTid()).getFirst();
        assertEquals("worker-retry", firstLease.workerId());
        assertEquals(0, firstLease.retryCount());

        assertTrue(harness.taskManager.expireLeasedWork(task.getTid(), firstLease.messageId()));

        TaskWorkStats afterExpiryStats = harness.stats(task.getTid());
        assertEquals(1, afterExpiryStats.readyCount());
        assertEquals(0, afterExpiryStats.inflightCount());
        assertEquals(0, afterExpiryStats.finalCount());
        assertTrue(harness.activeLeases(task.getTid()).isEmpty());
        assertEquals(WorkerContextStatus.IDLE,
                harness.workerManager.getWorkerContextById("ctx-retry").getStatus());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(task.getTid()).getStatus());

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));
        List<ActiveLeaseRecord> secondLeases = harness.activeLeases(task.getTid());
        assertEquals(1, secondLeases.size());
        assertEquals(firstLease.messageId(), secondLeases.getFirst().messageId());
        assertEquals("worker-retry", secondLeases.getFirst().workerId());
        assertEquals(1, secondLeases.getFirst().retryCount());

        TaskWorkStats afterRedispatchStats = harness.stats(task.getTid());
        assertEquals(0, afterRedispatchStats.readyCount());
        assertEquals(1, afterRedispatchStats.inflightCount());
        assertEquals(0, afterRedispatchStats.finalCount());
        assertEquals(2, harness.successfulMessageAssignments(task.getTid(), "worker-retry"));
    }
}
