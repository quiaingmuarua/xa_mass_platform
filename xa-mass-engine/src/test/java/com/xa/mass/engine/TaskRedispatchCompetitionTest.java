package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.ResultApplyOutcome;
import com.xa.mass.runtime.api.ResultApplyStatus;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.runtime.api.TaskWorkResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRedispatchCompetitionTest {

    @Test
    void leaseExpiryReentersBatchTaskIntoCompetitionPoolAndRedispatchesSameWorkOnce() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-retry", "us");
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
        assertFalse(harness.workerManager.hasWorkerExclusiveLease("worker-retry"));
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

    @Test
    void staleResultFromExpiredLeaseDoesNotStealRedispatchedWork() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-retry", "us");
        Task task = harness.createBatchTask("stale-result-after-redispatch", List.of(harness.item("retry")), 1, 1);
        assertTrue(harness.taskManager.approveTask(task.getTid()));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));
        ActiveLeaseRecord firstLease = harness.activeLeases(task.getTid()).getFirst();
        assertTrue(harness.taskManager.expireLeasedWork(task.getTid(), firstLease.messageId()));
        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));
        ActiveLeaseRecord secondLease = harness.activeLeases(task.getTid()).getFirst();

        ResultApplyOutcome staleOutcome = harness.taskManager.applyTaskWorkResult(TaskWorkResult.success(
                task.getTid(),
                firstLease.messageId(),
                firstLease.leaseToken(),
                "late old result",
                java.util.Map.of("source", "old-lease")
        ));

        assertEquals(ResultApplyStatus.STALE_LEASE, staleOutcome.status());
        List<ActiveLeaseRecord> activeLeasesAfterStaleResult = harness.activeLeases(task.getTid());
        assertEquals(1, activeLeasesAfterStaleResult.size());
        assertEquals(secondLease.leaseToken(), activeLeasesAfterStaleResult.getFirst().leaseToken());
        assertEquals(0, harness.stats(task.getTid()).finalCount());
        assertEquals(1, harness.stats(task.getTid()).inflightCount());

        assertTrue(harness.taskManager.ingestTaskResult(
                task.getTid(),
                secondLease.messageId(),
                true,
                "current lease done",
                null,
                java.util.Map.of("source", "current-lease")
        ));

        Task completedTask = harness.taskManager.getTask(task.getTid());
        TaskWorkStats completedStats = harness.stats(task.getTid());
        assertEquals(TaskStatus.TERMINAL, completedTask.getStatus());
        assertEquals(1, completedStats.successCount());
        assertEquals(1, completedStats.finalCount());
    }

    @Test
    void leaseExpiryReleasesWorkerForWaitingTaskCompetition() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-shared", "us");
        Task firstTask = harness.createBatchTask(
                "competition-expiry-first",
                List.of(harness.item("first")),
                1,
                1
        );
        Task secondTask = harness.createBatchTask(
                "competition-expiry-second",
                List.of(harness.item("second")),
                1,
                1
        );
        assertTrue(harness.taskManager.approveTask(firstTask.getTid()));
        assertTrue(harness.taskManager.approveTask(secondTask.getTid()));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(firstTask.getTid())));
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(secondTask.getTid())));
        ActiveLeaseRecord firstLease = harness.activeLeases(firstTask.getTid()).getFirst();

        assertTrue(harness.taskManager.expireLeasedWork(firstTask.getTid(), firstLease.messageId()));
        assertFalse(harness.workerManager.hasWorkerExclusiveLease("worker-shared"));
        assertEquals(1, harness.stats(firstTask.getTid()).readyCount());
        assertTrue(harness.activeLeases(firstTask.getTid()).isEmpty());

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(secondTask.getTid())));

        List<ActiveLeaseRecord> secondTaskLeases = harness.activeLeases(secondTask.getTid());
        assertEquals(1, secondTaskLeases.size());
        assertEquals("worker-shared", secondTaskLeases.getFirst().workerId());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(secondTask.getTid()).getStatus());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(firstTask.getTid()).getStatus());
        assertEquals(1, harness.stats(firstTask.getTid()).readyCount());
        assertEquals(0, harness.stats(secondTask.getTid()).readyCount());
    }

    @Test
    void successfulResultReleasesWorkerAndLaterRefillClaimsRemainingReadyWork() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-refill", "us");
        Task task = harness.createBatchTask(
                "result-release-refill",
                List.of(harness.item("first"), harness.item("second"), harness.item("third")),
                0,
                1
        );
        assertTrue(harness.taskManager.approveTask(task.getTid()));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));
        ActiveLeaseRecord firstLease = harness.activeLeases(task.getTid()).getFirst();
        assertEquals("worker-refill", firstLease.workerId());
        assertEquals(2, harness.stats(task.getTid()).readyCount());
        assertEquals(1, harness.stats(task.getTid()).inflightCount());

        assertTrue(harness.taskManager.ingestTaskResult(
                task.getTid(),
                firstLease.messageId(),
                true,
                "first done",
                null,
                java.util.Map.of("source", "result-release-refill")
        ));

        assertFalse(harness.workerManager.hasWorkerExclusiveLease("worker-refill"));
        assertTrue(harness.activeLeases(task.getTid()).isEmpty());
        assertEquals(2, harness.stats(task.getTid()).readyCount());
        assertEquals(1, harness.stats(task.getTid()).finalCount());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(task.getTid()).getStatus());

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));
        List<ActiveLeaseRecord> refillLeases = harness.activeLeases(task.getTid());
        assertEquals(1, refillLeases.size());
        assertEquals("worker-refill", refillLeases.getFirst().workerId());
        assertFalse(firstLease.messageId().equals(refillLeases.getFirst().messageId()));
        assertEquals(1, harness.stats(task.getTid()).readyCount());
        assertEquals(1, harness.stats(task.getTid()).inflightCount());
        assertEquals(1, harness.stats(task.getTid()).finalCount());
    }

    @Test
    void expiredWorkWaitsUnderCompetitionAndRedispatchesAfterWorkerRelease() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-shared", "us");
        Task retryingTask = harness.createBatchTask(
                "competition-expiry-retry",
                List.of(harness.item("retry")),
                1,
                1
        );
        Task competingTask = harness.createBatchTask(
                "competition-expiry-competing",
                List.of(harness.item("competing")),
                0,
                1
        );
        assertTrue(harness.taskManager.approveTask(retryingTask.getTid()));
        assertTrue(harness.taskManager.approveTask(competingTask.getTid()));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(retryingTask.getTid())));
        ActiveLeaseRecord firstLease = harness.activeLeases(retryingTask.getTid()).getFirst();
        assertTrue(harness.taskManager.expireLeasedWork(retryingTask.getTid(), firstLease.messageId()));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(competingTask.getTid())));
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(retryingTask.getTid())));

        ActiveLeaseRecord competingLease = harness.activeLeases(competingTask.getTid()).getFirst();
        TaskWorkStats retryingStatsWhileBlocked = harness.stats(retryingTask.getTid());
        assertEquals(1, retryingStatsWhileBlocked.readyCount());
        assertEquals(0, retryingStatsWhileBlocked.inflightCount());
        assertTrue(harness.activeLeases(retryingTask.getTid()).isEmpty());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(retryingTask.getTid()).getStatus());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(competingTask.getTid()).getStatus());
        assertEquals("worker-shared", competingLease.workerId());

        assertTrue(harness.taskManager.ingestTaskResult(
                competingTask.getTid(),
                competingLease.messageId(),
                true,
                "competing task done",
                null,
                java.util.Map.of("source", "redispatch-competition")
        ));

        assertEquals(TaskStatus.TERMINAL, harness.taskManager.getTask(competingTask.getTid()).getStatus());
        assertFalse(harness.workerManager.hasWorkerExclusiveLease("worker-shared"));
        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(retryingTask.getTid())));

        List<ActiveLeaseRecord> retryingLeases = harness.activeLeases(retryingTask.getTid());
        assertEquals(1, retryingLeases.size());
        assertEquals(firstLease.messageId(), retryingLeases.getFirst().messageId());
        assertEquals("worker-shared", retryingLeases.getFirst().workerId());
        assertEquals(1, retryingLeases.getFirst().retryCount());
        assertEquals(0, harness.stats(retryingTask.getTid()).readyCount());
        assertEquals(1, harness.stats(retryingTask.getTid()).inflightCount());
    }

    @Test
    void retryExhaustedBatchFailureReleasesWorkerForWaitingTaskCompetition() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-shared", "us");
        Task exhaustedTask = harness.createBatchTask(
                "retry-exhausted-first",
                List.of(harness.item("first")),
                0,
                1
        );
        Task waitingTask = harness.createBatchTask(
                "retry-exhausted-waiting",
                List.of(harness.item("second")),
                0,
                1
        );
        assertTrue(harness.taskManager.approveTask(exhaustedTask.getTid()));
        assertTrue(harness.taskManager.approveTask(waitingTask.getTid()));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(exhaustedTask.getTid())));
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(waitingTask.getTid())));
        ActiveLeaseRecord exhaustedLease = harness.activeLeases(exhaustedTask.getTid()).getFirst();

        assertTrue(harness.taskManager.expireLeasedWork(exhaustedTask.getTid(), exhaustedLease.messageId()));

        Task finalizedTask = harness.taskManager.getTask(exhaustedTask.getTid());
        TaskWorkStats finalizedStats = harness.stats(exhaustedTask.getTid());
        assertEquals(TaskStatus.TERMINAL, finalizedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_FAILED, finalizedTask.getTerminalReason());
        assertEquals(1, finalizedStats.expiredCount());
        assertEquals(1, finalizedStats.finalCount());
        assertTrue(harness.activeLeases(exhaustedTask.getTid()).isEmpty());
        assertFalse(harness.workerManager.hasWorkerExclusiveLease("worker-shared"));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(waitingTask.getTid())));

        List<ActiveLeaseRecord> waitingLeases = harness.activeLeases(waitingTask.getTid());
        assertEquals(1, waitingLeases.size());
        assertEquals("worker-shared", waitingLeases.getFirst().workerId());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(waitingTask.getTid()).getStatus());
        assertEquals(0, harness.stats(waitingTask.getTid()).readyCount());
        assertEquals(1, harness.stats(waitingTask.getTid()).inflightCount());
    }
}
