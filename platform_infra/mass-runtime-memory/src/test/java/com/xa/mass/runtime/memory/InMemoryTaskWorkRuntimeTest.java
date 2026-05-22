package com.xa.mass.runtime.memory;

import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.ResultApplyOutcome;
import com.xa.mass.runtime.api.ResultApplyStatus;
import com.xa.mass.runtime.api.TaskWorkEnvelope;
import com.xa.mass.runtime.api.TaskWorkResult;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.runtime.api.WorkEnqueueOptions;
import com.xa.mass.runtime.api.WorkEnqueueOutcome;
import com.xa.mass.runtime.api.WorkEnqueueStatus;
import com.xa.mass.runtime.api.WorkerClaimTarget;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryTaskWorkRuntimeTest {

    @Test
    void enqueueAndClaimMovesReadyWorkToActiveLease() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-04-27T00:00:00Z"));
        InMemoryTaskWorkRuntime runtime = new InMemoryTaskWorkRuntime(10, now::get);

        WorkEnqueueOutcome outcome = runtime.enqueue(item("task-1", "msg-1"), WorkEnqueueOptions.DEFAULT);

        assertEquals(WorkEnqueueStatus.ENQUEUED, outcome.status());
        assertEquals(1, runtime.stats("task-1").readyCount());
        assertEquals(1, runtime.stats("task-1").totalCount());

        List<ClaimedTaskWork> claimed = runtime.claimReady("task-1",
                List.of(WorkerClaimTarget.workerLevel("worker-1", "batch-1", 1)), 1, 30);

        assertEquals(1, claimed.size());
        ClaimedTaskWork work = claimed.get(0);
        assertEquals("msg-1", work.messageId());
        assertEquals("worker-1", work.workerId());
        assertNotNull(work.leaseToken());
        assertEquals(now.get().plusSeconds(30), work.leaseExpireAt());
        assertEquals(0, runtime.stats("task-1").readyCount());
        assertEquals(1, runtime.stats("task-1").inflightCount());
        assertTrue(runtime.hasActiveLeaseForWorker("task-1", "worker-1"));
    }

    @Test
    void duplicateAndBackpressureAreRejected() {
        InMemoryTaskWorkRuntime runtime = new InMemoryTaskWorkRuntime(1);
        TaskWorkEnvelope item = item("task-1", "msg-1");

        assertEquals(WorkEnqueueStatus.ENQUEUED,
                runtime.enqueue(item, WorkEnqueueOptions.DEFAULT).status());
        assertEquals(WorkEnqueueStatus.DUPLICATE,
                runtime.enqueue(item, WorkEnqueueOptions.DEFAULT).status());
        assertEquals(WorkEnqueueStatus.BACKPRESSURE_REJECTED,
                runtime.enqueue(item("task-1", "msg-2"), WorkEnqueueOptions.DEFAULT).status());
        assertEquals(1, runtime.stats().backpressureRejectedItems());
    }

    @Test
    void applySuccessRemovesLeaseAndCountsOnce() {
        InMemoryTaskWorkRuntime runtime = new InMemoryTaskWorkRuntime();
        runtime.enqueue(item("task-1", "msg-1"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork work = runtime.claimReady("task-1",
                List.of(WorkerClaimTarget.workerLevel("worker-1", "batch-1", 1)), 1, 30).get(0);

        ResultApplyOutcome applied = runtime.applyResult(TaskWorkResult.success(
                "task-1", "msg-1", work.leaseToken(), "done", Map.of("ok", true)));
        ResultApplyOutcome duplicate = runtime.applyResult(TaskWorkResult.success(
                "task-1", "msg-1", work.leaseToken(), "done-again", Map.of()));

        assertEquals(ResultApplyStatus.SUCCESS_APPLIED, applied.status());
        assertEquals(ResultApplyStatus.NO_ACTIVE_LEASE, duplicate.status());
        assertEquals(1, runtime.stats("task-1").successCount());
        assertEquals(1, runtime.stats("task-1").finalCount());
        assertEquals(0, runtime.stats("task-1").inflightCount());
        assertFalse(runtime.hasActiveLeaseForWorker("task-1", "worker-1"));
        assertEquals(com.xa.mass.runtime.api.TaskWorkFinalStatus.SUCCESS,
                runtime.getRecentFinalReceipt("task-1", "msg-1").orElseThrow().status());
    }

    @Test
    void staleLeaseDoesNotAdvanceCounters() {
        InMemoryTaskWorkRuntime runtime = new InMemoryTaskWorkRuntime();
        runtime.enqueue(item("task-1", "msg-1"), WorkEnqueueOptions.DEFAULT);
        runtime.claimReady("task-1",
                List.of(WorkerClaimTarget.workerLevel("worker-1", "batch-1", 1)), 1, 30);

        ResultApplyOutcome outcome = runtime.applyResult(TaskWorkResult.success(
                "task-1", "msg-1", "stale-token", "done", Map.of()));

        assertEquals(ResultApplyStatus.STALE_LEASE, outcome.status());
        assertEquals(0, runtime.stats("task-1").successCount());
        assertEquals(1, runtime.stats("task-1").inflightCount());
    }

    @Test
    void retryableFailureReturnsWorkToReadyQueue() {
        InMemoryTaskWorkRuntime runtime = new InMemoryTaskWorkRuntime();
        runtime.enqueue(item("task-1", "msg-1"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork work = runtime.claimReady("task-1",
                List.of(WorkerClaimTarget.workerLevel("worker-1", "batch-1", 1)), 1, 30).get(0);

        ResultApplyOutcome outcome = runtime.applyResult(TaskWorkResult.failure(
                "task-1", "msg-1", work.leaseToken(), "BOOM", "boom", Map.of(), true));

        assertEquals(ResultApplyStatus.RETRY_SCHEDULED, outcome.status());
        assertEquals(1, runtime.stats("task-1").totalCount());
        assertEquals(1, runtime.stats("task-1").readyCount());
        assertEquals(0, runtime.stats("task-1").inflightCount());

        ClaimedTaskWork retry = runtime.claimReady("task-1",
                List.of(WorkerClaimTarget.workerLevel("worker-2", "batch-2", 1)), 1, 30).get(0);
        assertEquals(1, retry.retryCount());
    }

    @Test
    void retryableFailureCanRemainDelayedUntilRetryVisibleAt() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-04-27T00:00:00Z"));
        InMemoryTaskWorkRuntime runtime = new InMemoryTaskWorkRuntime(10, now::get);
        runtime.enqueue(item("task-1", "msg-1"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork work = runtime.claimReady("task-1",
                List.of(WorkerClaimTarget.workerLevel("worker-1", "batch-1", 1)), 1, 30).get(0);

        ResultApplyOutcome outcome = runtime.applyResult(TaskWorkResult.failure(
                "task-1", "msg-1", work.leaseToken(), "BOOM", "boom", Map.of(), true)
                .withRetryVisibleAt(now.get().plusSeconds(5)));

        assertEquals(ResultApplyStatus.RETRY_SCHEDULED, outcome.status());
        assertEquals(0, runtime.stats("task-1").readyCount());
        assertEquals(1, runtime.stats("task-1").delayedCount());
        assertFalse(runtime.hasReadyWork("task-1"));

        now.set(now.get().plusSeconds(6));
        assertTrue(runtime.hasReadyWork("task-1"));
        assertEquals(1, runtime.stats("task-1").readyCount());
        assertEquals(0, runtime.stats("task-1").delayedCount());
    }

    @Test
    void expiredLeasesArePolledFromLeaseIndex() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-04-27T00:00:00Z"));
        InMemoryTaskWorkRuntime runtime = new InMemoryTaskWorkRuntime(10, now::get);
        runtime.enqueue(item("task-1", "msg-1"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork work = runtime.claimReady("task-1",
                List.of(WorkerClaimTarget.workerLevel("worker-1", "batch-1", 1)), 1, 10).get(0);

        assertTrue(runtime.pollExpiredLeases(10, now.get().plusSeconds(9)).isEmpty());
        List<ActiveLeaseRecord> expired = runtime.pollExpiredLeases(10, now.get().plusSeconds(11));

        assertEquals(1, expired.size());
        assertEquals(work.leaseToken(), expired.get(0).leaseToken());
    }

    @Test
    void expiredResultFinalizesExpiredCounter() {
        InMemoryTaskWorkRuntime runtime = new InMemoryTaskWorkRuntime();
        runtime.enqueue(item("task-1", "msg-1"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork work = runtime.claimReady("task-1",
                List.of(WorkerClaimTarget.workerLevel("worker-1", "batch-1", 1)), 1, 30).get(0);

        ResultApplyOutcome outcome = runtime.applyResult(TaskWorkResult.expired(
                "task-1", "msg-1", work.leaseToken(), "lease expired", false));

        assertEquals(ResultApplyStatus.FAILURE_FINALIZED, outcome.status());
        assertEquals(1, runtime.stats("task-1").expiredCount());
        assertEquals(1, runtime.stats("task-1").finalCount());
        assertEquals(0, runtime.stats("task-1").failedCount());
        assertEquals(0, runtime.stats("task-1").inflightCount());
    }

    @Test
    void discardTaskClearsReadyDelayedAndActiveWork() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-04-27T00:00:00Z"));
        InMemoryTaskWorkRuntime runtime = new InMemoryTaskWorkRuntime(10, now::get);
        runtime.enqueue(item("task-1", "ready"), WorkEnqueueOptions.DEFAULT);
        runtime.enqueue(item("task-1", "active"), WorkEnqueueOptions.DEFAULT);
        runtime.enqueue(new TaskWorkEnvelope("task-1", "delayed", "demo.event",
                        Map.of("target", "delayed"), null, 0, 3, null,
                        now.get().plusSeconds(60), now.get()),
                WorkEnqueueOptions.DEFAULT);
        runtime.claimReady("task-1",
                List.of(WorkerClaimTarget.workerLevel("worker-1", "batch-1", 1)), 1, 30);

        List<ActiveLeaseRecord> activeLeases = runtime.activeLeases("task-1");
        assertEquals(1, activeLeases.size());
        assertEquals("worker-1", activeLeases.get(0).workerId());

        assertEquals(3L, runtime.discardTask("task-1"));

        assertEquals(TaskWorkStats.EMPTY, runtime.stats("task-1"));
        assertFalse(runtime.hasReadyWork("task-1"));
        assertFalse(runtime.hasActiveLeaseForWorker("task-1", "worker-1"));
        assertTrue(runtime.pollExpiredLeases(10, now.get().plusSeconds(31)).isEmpty());
        assertEquals(3L, runtime.stats().discardedItems());
        assertEquals(0L, runtime.stats().readyItems());
        assertEquals(0L, runtime.stats().inflightItems());
        assertEquals(0L, runtime.stats().delayedItems());
    }

    @Test
    void discardTaskDoesNotRemoveOtherTaskIndexes() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-04-27T00:00:00Z"));
        InMemoryTaskWorkRuntime runtime = new InMemoryTaskWorkRuntime(10, now::get);
        runtime.enqueue(item("discarded-task", "ready"), WorkEnqueueOptions.DEFAULT);
        runtime.enqueue(item("kept-task", "active"), WorkEnqueueOptions.DEFAULT);
        runtime.enqueue(new TaskWorkEnvelope("kept-task", "delayed", "demo.event",
                        Map.of("target", "delayed"), null, 0, 3, null,
                        now.get().plusSeconds(60), now.get()),
                WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork keptActive = runtime.claimReady("kept-task",
                List.of(WorkerClaimTarget.workerLevel("worker-keep", "batch-1", 1)), 1, 30).get(0);

        assertEquals(1L, runtime.discardTask("discarded-task"));

        assertEquals(2, runtime.stats("kept-task").totalCount());
        assertEquals(1, runtime.activeLeases("kept-task").size());
        assertTrue(runtime.hasActiveLeaseForWorker("kept-task", "worker-keep"));
        assertTrue(runtime.pollExpiredLeases(10, now.get().plusSeconds(31)).stream()
                .anyMatch(lease -> keptActive.leaseToken().equals(lease.leaseToken())));

        ResultApplyOutcome outcome = runtime.applyResult(TaskWorkResult.success(
                "kept-task", "active", keptActive.leaseToken(), "done", Map.of()));
        assertEquals(ResultApplyStatus.SUCCESS_APPLIED, outcome.status());
        now.set(now.get().plusSeconds(61));
        assertTrue(runtime.hasReadyWork("kept-task"));
        assertEquals(1, runtime.stats("kept-task").readyCount());
    }

    private TaskWorkEnvelope item(String taskId, String messageId) {
        return new TaskWorkEnvelope(taskId, messageId, "demo.event",
                Map.of("target", messageId), null, 0, 3, null, null,
                Instant.parse("2026-04-27T00:00:00Z"));
    }
}


