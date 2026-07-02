package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;
import com.xa.mass.task.runtime.TaskRuntimeProgressSnapshot;
import com.xa.mass.task.runtime.MessageFinalityStatus;
import com.xa.mass.task.runtime.MessageFinalityOutcome;
import com.xa.mass.task.runtime.ResultApplySource;
import com.xa.mass.task.runtime.RuntimeEpoch;
import com.xa.mass.task.runtime.RuntimeResultFact;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskRedispatchCompetitionTest {

    @Test
    void leaseExpiryReentersBatchTaskIntoCompetitionPoolAndRedispatchesSameWorkOnce() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-retry", "us");
        Task task = harness.createBatchTask("redispatch-after-expiry", List.of(harness.item("retry")), 1, 1);
        assertTrue(harness.taskManager.approveTask(task.getTid()).accepted());

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));
        ActiveLeaseRepairCandidate firstLease = harness.activeLeases(task.getTid()).getFirst();
        assertEquals("worker-retry", firstLease.workerId());
        assertEquals(1, firstLease.attemptNo());

        assertTrue(harness.taskRuntimeServingLane.expireLeasedWork(task.getTid(), firstLease.messageId()));

        TaskRuntimeProgressSnapshot afterExpiryStats = harness.stats(task.getTid());
        assertEquals(1, afterExpiryStats.readyCount());
        assertEquals(0, afterExpiryStats.activeCount());
        assertEquals(0, afterExpiryStats.finalCount());
        assertTrue(harness.activeLeases(task.getTid()).isEmpty());
        assertFalse(harness.workerManager.hasWorkerExclusiveLease("worker-retry"));
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(task.getTid()).getStatus());

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));
        List<ActiveLeaseRepairCandidate> secondLeases = harness.activeLeases(task.getTid());
        assertEquals(1, secondLeases.size());
        assertEquals(firstLease.messageId(), secondLeases.getFirst().messageId());
        assertEquals("worker-retry", secondLeases.getFirst().workerId());
        assertEquals(2, secondLeases.getFirst().attemptNo());

        TaskRuntimeProgressSnapshot afterRedispatchStats = harness.stats(task.getTid());
        assertEquals(0, afterRedispatchStats.readyCount());
        assertEquals(1, afterRedispatchStats.activeCount());
        assertEquals(0, afterRedispatchStats.finalCount());
        assertEquals(2, harness.successfulMessageAssignments(task.getTid(), "worker-retry"));
    }

    @Test
    void staleResultFromExpiredLeaseDoesNotStealRedispatchedWork() {
        TaskSchedulingTestHarness harness = new TaskSchedulingTestHarness();
        harness.addWorker("worker-retry", "us");
        Task task = harness.createBatchTask("stale-result-after-redispatch", List.of(harness.item("retry")), 1, 1);
        assertTrue(harness.taskManager.approveTask(task.getTid()).accepted());

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));
        ActiveLeaseRepairCandidate firstLease = harness.activeLeases(task.getTid()).getFirst();
        assertTrue(harness.taskRuntimeServingLane.expireLeasedWork(task.getTid(), firstLease.messageId()));
        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));
        ActiveLeaseRepairCandidate secondLease = harness.activeLeases(task.getTid()).getFirst();

        MessageFinalityOutcome staleOutcome = harness.taskRuntime.applyResult(new RuntimeResultFact(
                task.getTid(),
                firstLease.messageId(),
                firstLease.leaseToken(),
                firstLease.workerId(),
                firstLease.attemptNo(),
                ResultApplySource.WORKER_RESULT,
                true,
                Map.of("source", "old-lease"),
                "late old result",
                RuntimeEpoch.of(task.getTid(), 1L),
                System.currentTimeMillis()
        ));

        assertEquals(MessageFinalityStatus.REJECTED, staleOutcome.status());
        assertTrue(staleOutcome.reason().contains("correlation mismatch"));
        List<ActiveLeaseRepairCandidate> activeLeasesAfterStaleResult = harness.activeLeases(task.getTid());
        assertEquals(1, activeLeasesAfterStaleResult.size());
        assertEquals(secondLease.leaseToken(), activeLeasesAfterStaleResult.getFirst().leaseToken());
        assertEquals(0, harness.stats(task.getTid()).finalCount());
        assertEquals(1, harness.stats(task.getTid()).activeCount());

        assertTrue(harness.taskRuntimeServingLane.ingestTaskResult(
                task.getTid(),
                secondLease.messageId(),
                true,
                "current lease done",
                null,
                java.util.Map.of("source", "current-lease")
        ));

        Task completedTask = harness.taskManager.getTask(task.getTid());
        TaskRuntimeProgressSnapshot completedStats = harness.stats(task.getTid());
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
        assertTrue(harness.taskManager.approveTask(firstTask.getTid()).accepted());
        assertTrue(harness.taskManager.approveTask(secondTask.getTid()).accepted());

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(firstTask.getTid())));
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(secondTask.getTid())));
        ActiveLeaseRepairCandidate firstLease = harness.activeLeases(firstTask.getTid()).getFirst();

        assertTrue(harness.taskRuntimeServingLane.expireLeasedWork(firstTask.getTid(), firstLease.messageId()));
        assertFalse(harness.workerManager.hasWorkerExclusiveLease("worker-shared"));
        assertEquals(1, harness.stats(firstTask.getTid()).readyCount());
        assertTrue(harness.activeLeases(firstTask.getTid()).isEmpty());

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(secondTask.getTid())));

        List<ActiveLeaseRepairCandidate> secondTaskLeases = harness.activeLeases(secondTask.getTid());
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
        assertTrue(harness.taskManager.approveTask(task.getTid()).accepted());

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(task.getTid())));
        ActiveLeaseRepairCandidate firstLease = harness.activeLeases(task.getTid()).getFirst();
        assertEquals("worker-refill", firstLease.workerId());
        assertEquals(2, harness.stats(task.getTid()).readyCount());
        assertEquals(1, harness.stats(task.getTid()).activeCount());

        assertTrue(harness.taskRuntimeServingLane.ingestTaskResult(
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
        List<ActiveLeaseRepairCandidate> refillLeases = harness.activeLeases(task.getTid());
        assertEquals(1, refillLeases.size());
        assertEquals("worker-refill", refillLeases.getFirst().workerId());
        assertFalse(firstLease.messageId().equals(refillLeases.getFirst().messageId()));
        assertEquals(1, harness.stats(task.getTid()).readyCount());
        assertEquals(1, harness.stats(task.getTid()).activeCount());
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
        assertTrue(harness.taskManager.approveTask(retryingTask.getTid()).accepted());
        assertTrue(harness.taskManager.approveTask(competingTask.getTid()).accepted());

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(retryingTask.getTid())));
        ActiveLeaseRepairCandidate firstLease = harness.activeLeases(retryingTask.getTid()).getFirst();
        assertTrue(harness.taskRuntimeServingLane.expireLeasedWork(retryingTask.getTid(), firstLease.messageId()));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(competingTask.getTid())));
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(retryingTask.getTid())));

        ActiveLeaseRepairCandidate competingLease = harness.activeLeases(competingTask.getTid()).getFirst();
        TaskRuntimeProgressSnapshot retryingStatsWhileBlocked = harness.stats(retryingTask.getTid());
        assertEquals(1, retryingStatsWhileBlocked.readyCount());
        assertEquals(0, retryingStatsWhileBlocked.activeCount());
        assertTrue(harness.activeLeases(retryingTask.getTid()).isEmpty());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(retryingTask.getTid()).getStatus());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(competingTask.getTid()).getStatus());
        assertEquals("worker-shared", competingLease.workerId());

        assertTrue(harness.taskRuntimeServingLane.ingestTaskResult(
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

        List<ActiveLeaseRepairCandidate> retryingLeases = harness.activeLeases(retryingTask.getTid());
        assertEquals(1, retryingLeases.size());
        assertEquals(firstLease.messageId(), retryingLeases.getFirst().messageId());
        assertEquals("worker-shared", retryingLeases.getFirst().workerId());
        assertEquals(2, retryingLeases.getFirst().attemptNo());
        assertEquals(0, harness.stats(retryingTask.getTid()).readyCount());
        assertEquals(1, harness.stats(retryingTask.getTid()).activeCount());
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
        assertTrue(harness.taskManager.approveTask(exhaustedTask.getTid()).accepted());
        assertTrue(harness.taskManager.approveTask(waitingTask.getTid()).accepted());

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(exhaustedTask.getTid())));
        assertFalse(harness.assignListener.onTaskAssign(harness.taskManager.getTask(waitingTask.getTid())));
        ActiveLeaseRepairCandidate exhaustedLease = harness.activeLeases(exhaustedTask.getTid()).getFirst();

        assertTrue(harness.taskRuntimeServingLane.expireLeasedWork(exhaustedTask.getTid(), exhaustedLease.messageId()));

        Task finalizedTask = harness.taskManager.getTask(exhaustedTask.getTid());
        TaskRuntimeProgressSnapshot finalizedStats = harness.stats(exhaustedTask.getTid());
        assertEquals(TaskStatus.TERMINAL, finalizedTask.getStatus());
        assertEquals(TaskTerminalReason.ALL_MESSAGES_FAILED, finalizedTask.getTerminalReason());
        assertEquals(1, finalizedStats.expiredCount());
        assertEquals(1, finalizedStats.finalCount());
        assertTrue(harness.activeLeases(exhaustedTask.getTid()).isEmpty());
        assertFalse(harness.workerManager.hasWorkerExclusiveLease("worker-shared"));

        assertTrue(harness.assignListener.onTaskAssign(harness.taskManager.getTask(waitingTask.getTid())));

        List<ActiveLeaseRepairCandidate> waitingLeases = harness.activeLeases(waitingTask.getTid());
        assertEquals(1, waitingLeases.size());
        assertEquals("worker-shared", waitingLeases.getFirst().workerId());
        assertEquals(TaskStatus.RUNNING, harness.taskManager.getTask(waitingTask.getTid()).getStatus());
        assertEquals(0, harness.stats(waitingTask.getTid()).readyCount());
        assertEquals(1, harness.stats(waitingTask.getTid()).activeCount());
    }
}
