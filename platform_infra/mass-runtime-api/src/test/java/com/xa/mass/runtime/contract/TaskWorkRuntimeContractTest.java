package com.xa.mass.runtime.contract;

import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.ResultApplyOutcome;
import com.xa.mass.runtime.api.ResultApplyStatus;
import com.xa.mass.runtime.api.RuntimeResultApplyContext;
import com.xa.mass.runtime.api.TaskWorkEnvelope;
import com.xa.mass.runtime.api.TaskWorkResult;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.runtime.api.WorkEnqueueOutcome;
import com.xa.mass.runtime.api.WorkEnqueueOptions;
import com.xa.mass.runtime.api.WorkEnqueueStatus;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural contract for {@link TaskWorkRuntime} implementations.
 *
 * <p>Key cross-impl invariants:
 * <ul>
 *   <li>Enqueue is idempotent per messageId within a task (DUPLICATE not ENQUEUED on repeat)</li>
 *   <li>Claim is exclusive: a claimed item is not returned to a second claimer</li>
 *   <li>applyResult is idempotent: the same token used twice returns NO_ACTIVE_LEASE</li>
 *   <li>A stale/wrong token returns STALE_LEASE, not an exception</li>
 *   <li>Retryable failure returns work to the ready queue</li>
 *   <li>pollExpiredLeases uses the provided {@code now} instant, not wall clock</li>
 *   <li>discardTask removes all work and does not affect other tasks</li>
 * </ul>
 */
public abstract class TaskWorkRuntimeContractTest {

    protected AtomicReference<Instant> clock;
    protected TaskWorkRuntime runtime;

    protected abstract TaskWorkRuntime createRuntime(AtomicReference<Instant> clock);

    protected void destroyRuntime(TaskWorkRuntime runtime) {
        runtime.shutdown();
    }

    @BeforeEach
    void setUp() {
        clock = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        runtime = createRuntime(clock);
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            destroyRuntime(runtime);
        }
    }

    // ── enqueue ───────────────────────────────────────────────────────────────

    @Test
    void enqueue_returnsEnqueued_onFirstCall() {
        assertThat(runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT).status())
                .isEqualTo(WorkEnqueueStatus.ENQUEUED);
    }

    @Test
    void enqueue_returnsDuplicate_onRepeatForSameMessageId() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        assertThat(runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT).status())
                .isEqualTo(WorkEnqueueStatus.DUPLICATE);
    }

    @Test
    void concurrentEnqueue_sameMessageIsIdempotentAndCountersStayStable() throws Exception {
        int contenders = 16;
        List<WorkEnqueueOutcome> outcomes = runConcurrently(contenders,
                index -> runtime.enqueue(item("enqueue-race", "m1"), WorkEnqueueOptions.DEFAULT));

        assertThat(outcomes).filteredOn(outcome -> outcome.status() == WorkEnqueueStatus.ENQUEUED)
                .hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome.status() == WorkEnqueueStatus.DUPLICATE)
                .hasSize(contenders - 1);
        TaskWorkStats stats = runtime.stats("enqueue-race");
        assertThat(stats.totalCount()).isEqualTo(1);
        assertThat(stats.readyCount()).isEqualTo(1);
        assertThat(runtime.stats().readyItems()).isEqualTo(1);
        assertThat(runtime.readyTaskIds(10)).containsExactly("enqueue-race");
    }

    @Test
    void hasReadyWork_isTrueAfterEnqueue() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        assertThat(runtime.hasReadyWork("t1")).isTrue();
    }

    @Test
    void hasReadyWork_isFalse_whenNoWorkEnqueued() {
        assertThat(runtime.hasReadyWork("t1")).isFalse();
    }

    @Test
    void enqueue_rejectsWhenPerTaskBackpressureLimitIsExceeded() {
        runtime.enqueue(item("t1", "m1"), new WorkEnqueueOptions(1));
        assertThat(runtime.enqueue(item("t1", "m2"), new WorkEnqueueOptions(1)).status())
                .isEqualTo(WorkEnqueueStatus.BACKPRESSURE_REJECTED);
    }

    // ── claim ─────────────────────────────────────────────────────────────────

    @Test
    void claimReady_returnsWork_andCreatesActiveLease() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        List<ClaimedTaskWork> claimed = runtime.claimReady("t1", targets("w1"), 1, 30);
        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).messageId()).isEqualTo("m1");
        assertThat(claimed.get(0).workerId()).isEqualTo("w1");
        assertThat(runtime.hasActiveLeaseForWorker("t1", "w1")).isTrue();
    }

    @Test
    void claimReady_returnsEmpty_whenNoReadyWork() {
        assertThat(runtime.claimReady("t1", targets("w1"), 1, 30)).isEmpty();
    }

    @Test
    void claimReady_isExclusive_claimedItemNotReturnedToSecondClaimer() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        runtime.claimReady("t1", targets("w1"), 1, 30);
        assertThat(runtime.claimReady("t1", targets("w2"), 1, 30)).isEmpty();
    }

    @Test
    void claimReady_onlyClaimsMessagesSupportedByWorkerEventScope() {
        runtime.enqueue(new TaskWorkEnvelope("t1", "m1", "crawler.fetch-page",
                Map.of("key", "m1"), null, 0, 3, null, null, clock.get()), WorkEnqueueOptions.DEFAULT);
        runtime.enqueue(new TaskWorkEnvelope("t1", "m2", "stock.quote.fetch",
                Map.of("key", "m2"), null, 0, 3, null, null, clock.get()), WorkEnqueueOptions.DEFAULT);

        List<ClaimedTaskWork> claimed = runtime.claimReady(
                "t1",
                List.of(
                        WorkerClaimTarget.workerLevel("crawler-worker", "batch-crawler", 1,
                                Set.of("crawler.fetch-page")),
                        WorkerClaimTarget.workerLevel("stock-worker", "batch-stock", 1,
                                Set.of("stock.quote.fetch"))
                ),
                2,
                30
        );

        assertThat(claimed).hasSize(2);
        assertThat(claimed)
                .extracting(ClaimedTaskWork::workerId, ClaimedTaskWork::eventCode)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("crawler-worker", "crawler.fetch-page"),
                        org.assertj.core.groups.Tuple.tuple("stock-worker", "stock.quote.fetch")
                );
    }

    @Test
    void hasReadyWork_isFalse_afterClaim() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        runtime.claimReady("t1", targets("w1"), 1, 30);
        assertThat(runtime.hasReadyWork("t1")).isFalse();
    }

    @Test
    void claimReady_distributesAcrossWorkersWithinCapacity() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        runtime.enqueue(item("t1", "m2"), WorkEnqueueOptions.DEFAULT);

        List<ClaimedTaskWork> claimed = runtime.claimReady(
                "t1",
                List.of(
                        WorkerClaimTarget.workerLevel("w1", "batch-1", 1),
                        WorkerClaimTarget.workerLevel("w2", "batch-2", 1)
                ),
                2,
                30
        );

        assertThat(claimed).hasSize(2);
        assertThat(claimed).extracting(ClaimedTaskWork::workerId)
                .containsExactlyInAnyOrder("w1", "w2");
    }

    @Test
    void concurrentClaimReady_claimsOneMessageOnce() throws Exception {
        runtime.enqueue(item("claim-race", "m1"), WorkEnqueueOptions.DEFAULT);

        List<List<ClaimedTaskWork>> attempts = runConcurrently(16,
                index -> runtime.claimReady("claim-race", targets("w" + index), 1, 30));
        List<ClaimedTaskWork> claimed = attempts.stream()
                .flatMap(List::stream)
                .toList();

        assertThat(claimed).hasSize(1);
        assertThat(claimed.get(0).messageId()).isEqualTo("m1");
        assertThat(runtime.stats("claim-race").readyCount()).isZero();
        assertThat(runtime.stats("claim-race").inflightCount()).isEqualTo(1);
    }

    @Test
    void concurrentClaimReady_manyMessagesRemainUniqueAndCountersStable() throws Exception {
        int totalMessages = 40;
        int contenders = 8;
        for (int i = 0; i < totalMessages; i++) {
            runtime.enqueue(item("claim-bulk", "m" + i), WorkEnqueueOptions.DEFAULT);
        }

        List<List<ClaimedTaskWork>> attempts = runConcurrently(contenders,
                index -> runtime.claimReady(
                        "claim-bulk",
                        List.of(WorkerClaimTarget.workerLevel("w" + index, "batch-" + index, 10)),
                        10,
                        30));
        List<ClaimedTaskWork> claimed = attempts.stream()
                .flatMap(List::stream)
                .toList();

        assertThat(claimed).hasSize(totalMessages);
        assertThat(new HashSet<>(claimed.stream().map(ClaimedTaskWork::messageId).toList()))
                .hasSize(totalMessages);
        TaskWorkStats stats = runtime.stats("claim-bulk");
        assertThat(stats.readyCount()).isZero();
        assertThat(stats.inflightCount()).isEqualTo(totalMessages);
        assertThat(stats.totalCount()).isEqualTo(totalMessages);
    }

    @Test
    void readyTaskIds_returnsMultipleReadyTasks_withoutScanningOneTaskPerItem() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        runtime.enqueue(item("t2", "m2"), WorkEnqueueOptions.DEFAULT);
        runtime.enqueue(item("t2", "m3"), WorkEnqueueOptions.DEFAULT);

        assertThat(runtime.readyTaskIds(10))
                .containsExactlyInAnyOrder("t1", "t2");
        assertThat(runtime.readyTaskIds(1)).hasSize(1);
    }

    // ── apply result ──────────────────────────────────────────────────────────

    @Test
    void applySuccess_finalizesWork_andRemovesLease() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork work = runtime.claimReady("t1", targets("w1"), 1, 30).get(0);
        ResultApplyOutcome outcome = runtime.applyResult(
                TaskWorkResult.success("t1", "m1", work.leaseToken(), "done", Map.of()));
        assertThat(outcome.status()).isEqualTo(ResultApplyStatus.SUCCESS_APPLIED);
        assertThat(runtime.hasActiveLeaseForWorker("t1", "w1")).isFalse();
    }

    @Test
    void applySuccess_isIdempotent_secondCallReturnsNoActiveLease() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork work = runtime.claimReady("t1", targets("w1"), 1, 30).get(0);
        runtime.applyResult(TaskWorkResult.success("t1", "m1", work.leaseToken(), "done", Map.of()));
        assertThat(runtime.applyResult(
                TaskWorkResult.success("t1", "m1", work.leaseToken(), "done2", Map.of())).status())
                .isEqualTo(ResultApplyStatus.NO_ACTIVE_LEASE);
    }

    @Test
    void concurrentApplyResult_sameLeaseAppliesOnce() throws Exception {
        runtime.enqueue(item("apply-race", "m1"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork work = runtime.claimReady("apply-race", targets("w1"), 1, 30).get(0);

        List<ResultApplyOutcome> outcomes = runConcurrently(12,
                index -> runtime.applyResult(TaskWorkResult.success(
                        "apply-race",
                        "m1",
                        work.leaseToken(),
                        "done-" + index,
                        Map.of())));

        assertThat(outcomes).filteredOn(outcome -> outcome.status() == ResultApplyStatus.SUCCESS_APPLIED)
                .hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome.status() == ResultApplyStatus.NO_ACTIVE_LEASE)
                .hasSize(11);
        assertThat(runtime.stats("apply-race").successCount()).isEqualTo(1);
        assertThat(runtime.stats("apply-race").inflightCount()).isZero();
    }

    @Test
    void getWork_returnsRuntimeEnvelopeWhileMessageIsManagedByRuntime() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);

        assertThat(runtime.getWork("t1", "m1"))
                .get()
                .extracting(TaskWorkEnvelope::messageId, TaskWorkEnvelope::maxRetryCount)
                .containsExactly("m1", 3);

        ClaimedTaskWork claimed = runtime.claimReady("t1", targets("w1"), 1, 30).get(0);
        assertThat(runtime.getWork("t1", "m1"))
                .get()
                .extracting(TaskWorkEnvelope::messageId, TaskWorkEnvelope::retryCount)
                .containsExactly("m1", 0);

        runtime.applyResult(TaskWorkResult.success("t1", "m1", claimed.leaseToken(), "done", Map.of()));
        assertThat(runtime.getWork("t1", "m1")).isEmpty();
    }

    @Test
    void getRecentFinalReceipt_returnsBoundedRuntimeFinalReceiptAfterFinalization() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork claimed = runtime.claimReady("t1", targets("w1"), 1, 30).get(0);

        runtime.applyResult(TaskWorkResult.success("t1", "m1", claimed.leaseToken(), "done", Map.of()));

        assertThat(runtime.getRecentFinalReceipt("t1", "m1"))
                .get()
                .extracting("taskId", "messageId", "status", "retryCount")
                .containsExactly("t1", "m1", com.xa.mass.runtime.api.TaskWorkFinalStatus.SUCCESS, 0);
    }

    @Test
    void applyResult_withStaleToken_returnsStaleLeaseNotException() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        runtime.claimReady("t1", targets("w1"), 1, 30);
        assertThat(runtime.applyResult(
                TaskWorkResult.success("t1", "m1", "stale-token", "done", Map.of())).status())
                .isEqualTo(ResultApplyStatus.STALE_LEASE);
    }

    // ── applyResultWithContext ────────────────────────────────────────────────

    @Test
    void applyResultWithContext_success_returnsWorkerLevelSnapshotMatchingLeaseAndWork() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        List<ClaimedTaskWork> claimed = runtime.claimReady("t1", targets("w1"), 1, 30);
        ClaimedTaskWork work = claimed.get(0);

        RuntimeResultApplyContext ctx = runtime.applyResultWithContext(
                TaskWorkResult.success("t1", "m1", work.leaseToken(), "done", Map.of()));

        assertThat(ctx.outcome().status()).isEqualTo(ResultApplyStatus.SUCCESS_APPLIED);
        assertThat(ctx.hasLeaseSnapshot()).isTrue();
        assertThat(ctx.workerId()).isEqualTo("w1");
        assertThat(ctx.batchId()).isEqualTo("batch-1");
        assertThat(ctx.activeLeaseToken()).isEqualTo(work.leaseToken());
        assertThat(ctx.retryCount()).isEqualTo(0);
        assertThat(ctx.maxRetryCount()).isEqualTo(3);
        assertThat(ctx.leasedAt()).isNotNull();
    }

    @Test
    void applyResultWithContext_success_producesIdenticalRuntimeSideEffectsAsApplyResult() {
        // Enqueue two identical items in two separate runtimes to verify side effects.
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork work = runtime.claimReady("t1", targets("w1"), 1, 30).get(0);

        RuntimeResultApplyContext ctx = runtime.applyResultWithContext(
                TaskWorkResult.success("t1", "m1", work.leaseToken(), "done", Map.of()));

        // Same post-apply state as applyResult: no active lease, no ready work, success counted.
        assertThat(ctx.outcome().status()).isEqualTo(ResultApplyStatus.SUCCESS_APPLIED);
        assertThat(runtime.hasActiveLeaseForWorker("t1", "w1")).isFalse();
        assertThat(runtime.hasReadyWork("t1")).isFalse();
        assertThat(runtime.stats("t1").successCount()).isEqualTo(1);
    }

    @Test
    void applyResultWithContext_noActiveLease_returnsNoLeaseContext() {
        // No enqueue, no claim — no active lease.
        RuntimeResultApplyContext ctx = runtime.applyResultWithContext(
                TaskWorkResult.success("t1", "m99", null, "done", Map.of()));

        assertThat(ctx.outcome().status()).isEqualTo(ResultApplyStatus.NO_ACTIVE_LEASE);
        assertThat(ctx.hasLeaseSnapshot()).isFalse();
        assertThat(ctx.workerId()).isNull();
        assertThat(ctx.activeLeaseToken()).isNull();
    }

    @Test
    void applyResultWithContext_staleLease_returnsSnapshotWithActualLeaseHolder() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        runtime.claimReady("t1", targets("w1"), 1, 30);

        RuntimeResultApplyContext ctx = runtime.applyResultWithContext(
                TaskWorkResult.success("t1", "m1", "stale-token-xyz", "done", Map.of()));

        assertThat(ctx.outcome().status()).isEqualTo(ResultApplyStatus.STALE_LEASE);
        // Snapshot must still contain the actual lease-holder for diagnostic purposes.
        assertThat(ctx.hasLeaseSnapshot()).isTrue();
        assertThat(ctx.workerId()).isEqualTo("w1");
        assertThat(ctx.activeLeaseToken()).isNotNull().isNotEqualTo("stale-token-xyz");
    }

    @Test
    void applyResultWithContext_retryScheduled_returnsSnapshotAndPreRetryRetryCount() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork work = runtime.claimReady("t1", targets("w1"), 1, 30).get(0);

        RuntimeResultApplyContext ctx = runtime.applyResultWithContext(
                TaskWorkResult.failure("t1", "m1", work.leaseToken(), "ERR", "boom", Map.of(), true));

        assertThat(ctx.outcome().status()).isEqualTo(ResultApplyStatus.RETRY_SCHEDULED);
        assertThat(ctx.hasLeaseSnapshot()).isTrue();
        assertThat(ctx.workerId()).isEqualTo("w1");
        // retryCount in context is the count at time of apply (0 for first attempt).
        assertThat(ctx.retryCount()).isEqualTo(0);
        assertThat(ctx.maxRetryCount()).isEqualTo(3);
        // Work is back in the ready queue for the next attempt.
        assertThat(runtime.hasReadyWork("t1")).isTrue();
    }

    @Test
    void applyResultWithContext_failureFinalized_returnsSnapshotAndFinalOutcome() {
        // Item with maxRetryCount=0 so first failure exhausts budget immediately.
        runtime.enqueue(
                new TaskWorkEnvelope("t1", "m1", "demo.event", Map.of(), null, 0, 0, null, null, clock.get()),
                WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork work = runtime.claimReady("t1", targets("w1"), 1, 30).get(0);

        RuntimeResultApplyContext ctx = runtime.applyResultWithContext(
                TaskWorkResult.failure("t1", "m1", work.leaseToken(), "ERR", "boom", Map.of(), true));

        assertThat(ctx.outcome().status()).isEqualTo(ResultApplyStatus.FAILURE_FINALIZED);
        assertThat(ctx.hasLeaseSnapshot()).isTrue();
        assertThat(ctx.workerId()).isEqualTo("w1");
        assertThat(ctx.maxRetryCount()).isEqualTo(0);
        assertThat(runtime.stats("t1").failedCount()).isEqualTo(1);
    }

    @Test
    void applyResultWithContext_isIdempotentOnSecondCall_returnsNoLeaseContext() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork work = runtime.claimReady("t1", targets("w1"), 1, 30).get(0);
        // First apply — succeeds.
        runtime.applyResultWithContext(
                TaskWorkResult.success("t1", "m1", work.leaseToken(), "done", Map.of()));
        // Second apply — duplicate, no lease.
        RuntimeResultApplyContext ctx = runtime.applyResultWithContext(
                TaskWorkResult.success("t1", "m1", work.leaseToken(), "done2", Map.of()));

        assertThat(ctx.outcome().status()).isEqualTo(ResultApplyStatus.NO_ACTIVE_LEASE);
        assertThat(ctx.hasLeaseSnapshot()).isFalse();
    }

    @Test
    void applyResultWithContext_onRetry_retryCountIncrements_acrossAttempts() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork work1 = runtime.claimReady("t1", targets("w1"), 1, 30).get(0);

        // First attempt fails → retry
        RuntimeResultApplyContext ctx1 = runtime.applyResultWithContext(
                TaskWorkResult.failure("t1", "m1", work1.leaseToken(), "ERR", "boom", Map.of(), true));
        assertThat(ctx1.retryCount()).isEqualTo(0);
        assertThat(ctx1.outcome().status()).isEqualTo(ResultApplyStatus.RETRY_SCHEDULED);

        // Second attempt
        ClaimedTaskWork work2 = runtime.claimReady("t1", targets("w2"), 1, 30).get(0);
        RuntimeResultApplyContext ctx2 = runtime.applyResultWithContext(
                TaskWorkResult.success("t1", "m1", work2.leaseToken(), "done", Map.of()));
        assertThat(ctx2.retryCount()).isEqualTo(1);   // second attempt
        assertThat(ctx2.workerId()).isEqualTo("w2");
        assertThat(ctx2.outcome().status()).isEqualTo(ResultApplyStatus.SUCCESS_APPLIED);
    }

    // ── retry ─────────────────────────────────────────────────────────────────

    @Test
    void retryableFailure_returnsWorkToReadyQueue() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork work = runtime.claimReady("t1", targets("w1"), 1, 30).get(0);
        ResultApplyOutcome outcome = runtime.applyResult(
                TaskWorkResult.failure("t1", "m1", work.leaseToken(), "ERR", "boom",
                        Map.of(), true));
        assertThat(outcome.status()).isEqualTo(ResultApplyStatus.RETRY_SCHEDULED);
        assertThat(runtime.hasReadyWork("t1")).isTrue();
        List<ClaimedTaskWork> retried = runtime.claimReady("t1", targets("w2"), 1, 30);
        assertThat(retried).hasSize(1);
        assertThat(retried.get(0).retryCount()).isEqualTo(1);
    }

    @Test
    void retryableFailure_respectsDelayedRetryVisibility() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork work = runtime.claimReady("t1", targets("w1"), 1, 30).get(0);

        ResultApplyOutcome outcome = runtime.applyResult(
                TaskWorkResult.failure("t1", "m1", work.leaseToken(), "ERR", "boom", Map.of(), true)
                        .withRetryVisibleAt(clock.get().plusSeconds(5))
        );

        assertThat(outcome.status()).isEqualTo(ResultApplyStatus.RETRY_SCHEDULED);
        assertThat(runtime.hasReadyWork("t1")).isFalse();
        assertThat(runtime.stats("t1").delayedCount()).isEqualTo(1);

        clock.set(clock.get().plusSeconds(6));
        assertThat(runtime.hasReadyWork("t1")).isTrue();
        assertThat(runtime.stats("t1").readyCount()).isEqualTo(1);
        assertThat(runtime.stats("t1").delayedCount()).isZero();
    }

    @Test
    void retryableFailure_doesNotCreateRecentFinalReceipt_beforeLogicalFinality() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork work = runtime.claimReady("t1", targets("w1"), 1, 30).get(0);

        runtime.applyResult(
                TaskWorkResult.failure("t1", "m1", work.leaseToken(), "ERR", "boom", Map.of(), true)
        );

        assertThat(runtime.getRecentFinalReceipt("t1", "m1")).isEmpty();
    }

    // ── lease expiry ──────────────────────────────────────────────────────────

    @Test
    void pollExpiredLeases_returnsNothing_beforeDeadline() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        runtime.claimReady("t1", targets("w1"), 1, 10);  // 10-second lease
        assertThat(runtime.pollExpiredLeases(10, clock.get().plusSeconds(9))).isEmpty();
    }

    @Test
    void pollExpiredLeases_returnsExpiredLease_afterDeadline() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork work = runtime.claimReady("t1", targets("w1"), 1, 10).get(0);
        assertThat(runtime.pollExpiredLeases(10, clock.get().plusSeconds(11)))
                .extracting(l -> l.leaseToken()).containsExactly(work.leaseToken());
    }

    @Test
    void concurrentPollExpiredLeases_reportsEachLeaseOnce() throws Exception {
        int totalMessages = 12;
        for (int i = 0; i < totalMessages; i++) {
            runtime.enqueue(item("expiry-race", "m" + i), WorkEnqueueOptions.DEFAULT);
        }
        List<WorkerClaimTarget> workers = new ArrayList<>();
        for (int i = 0; i < totalMessages; i++) {
            workers.add(WorkerClaimTarget.workerLevel("w" + i, "batch-" + i, 1));
        }
        List<ClaimedTaskWork> claimed = runtime.claimReady("expiry-race", workers, totalMessages, 10);
        assertThat(claimed).hasSize(totalMessages);

        List<List<ActiveLeaseRecord>> attempts = runConcurrently(4,
                index -> runtime.pollExpiredLeases(totalMessages, clock.get().plusSeconds(11)));
        List<ActiveLeaseRecord> expired = attempts.stream()
                .flatMap(List::stream)
                .toList();

        assertThat(expired).hasSize(totalMessages);
        assertThat(new HashSet<>(expired.stream().map(ActiveLeaseRecord::leaseToken).toList()))
                .hasSize(totalMessages);
    }

    // ── stats ─────────────────────────────────────────────────────────────────

    @Test
    void stats_readyCountReflectsEnqueueAndClaim() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        runtime.enqueue(item("t1", "m2"), WorkEnqueueOptions.DEFAULT);
        assertThat(runtime.stats("t1").readyCount()).isEqualTo(2);
        runtime.claimReady("t1", targets("w1"), 1, 30);
        assertThat(runtime.stats("t1").readyCount()).isEqualTo(1);
        assertThat(runtime.stats("t1").inflightCount()).isEqualTo(1);
    }

    @Test
    void stats_reflectRetryAndFinalizationInvariants() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork work = runtime.claimReady("t1", targets("w1"), 1, 30).get(0);

        runtime.applyResult(TaskWorkResult.failure("t1", "m1", work.leaseToken(), "ERR", "boom", Map.of(), true));
        TaskWorkStats afterRetry = runtime.stats("t1");
        assertThat(afterRetry.totalCount()).isEqualTo(1);
        assertThat(afterRetry.readyCount()).isEqualTo(1);
        assertThat(afterRetry.inflightCount()).isZero();
        assertThat(afterRetry.finalCount()).isZero();
        assertThat(afterRetry.processingCount()).isEqualTo(1);

        ClaimedTaskWork retried = runtime.claimReady("t1", targets("w2"), 1, 30).get(0);
        runtime.applyResult(TaskWorkResult.success("t1", "m1", retried.leaseToken(), "done", Map.of()));

        TaskWorkStats finalized = runtime.stats("t1");
        assertThat(finalized.successCount()).isEqualTo(1);
        assertThat(finalized.finalCount()).isEqualTo(1);
        assertThat(finalized.processingCount()).isZero();
        assertThat(finalized.pendingCount()).isZero();
    }

    // ── discard ───────────────────────────────────────────────────────────────

    @Test
    void discardTask_removesAllWorkForTask() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        runtime.enqueue(item("t1", "m2"), WorkEnqueueOptions.DEFAULT);
        runtime.claimReady("t1", targets("w1"), 1, 30);
        runtime.discardTask("t1");
        assertThat(runtime.hasReadyWork("t1")).isFalse();
        assertThat(runtime.hasActiveLeaseForWorker("t1", "w1")).isFalse();
        assertThat(runtime.pollExpiredLeases(10, clock.get().plusSeconds(31))).isEmpty();
    }

    @Test
    void discardTask_doesNotAffectOtherTasks() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        runtime.enqueue(item("t2", "m2"), WorkEnqueueOptions.DEFAULT);
        runtime.discardTask("t1");
        assertThat(runtime.hasReadyWork("t2")).isTrue();
    }

    @Test
    void discardTask_clearsRecentFinalReceiptsForThatTask() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork claimed = runtime.claimReady("t1", targets("w1"), 1, 30).get(0);
        runtime.applyResult(TaskWorkResult.success("t1", "m1", claimed.leaseToken(), "done", Map.of()));

        assertThat(runtime.getRecentFinalReceipt("t1", "m1")).isPresent();

        runtime.discardTask("t1");

        assertThat(runtime.getRecentFinalReceipt("t1", "m1")).isEmpty();
    }

    @Test
    void stats_remainConsistent_forHighVolumeSingleTask() {
        for (int i = 0; i < 200; i++) {
            runtime.enqueue(item("bulk", "m" + i), WorkEnqueueOptions.DEFAULT);
        }

        assertThat(runtime.readyTaskIds(10)).containsExactly("bulk");
        assertThat(runtime.stats("bulk").readyCount()).isEqualTo(200);
        assertThat(runtime.stats().readyItems()).isEqualTo(200);
    }

    @Test
    void readyTaskIds_areRuntimeOwnedAndPromoteDueDelayedItems() {
        runtime.enqueue(item("t-ready", "m1"), WorkEnqueueOptions.DEFAULT);
        runtime.enqueue(new TaskWorkEnvelope(
                        "t-delayed",
                        "m2",
                        "demo.event",
                        Map.of("key", "m2"),
                        null,
                        0,
                        3,
                        null,
                        clock.get().plusSeconds(5),
                        clock.get()),
                WorkEnqueueOptions.DEFAULT);

        assertThat(runtime.readyTaskIds(10)).containsExactly("t-ready");

        clock.set(clock.get().plusSeconds(6));
        assertThat(runtime.readyTaskIds(10)).contains("t-ready", "t-delayed");
    }

    @Test
    void shutdown_clearsRuntimeResidueAndRejectsFurtherEnqueue() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        runtime.enqueue(new TaskWorkEnvelope(
                        "t1",
                        "m2",
                        "demo.event",
                        Map.of("key", "m2"),
                        null,
                        0,
                        3,
                        null,
                        clock.get().plusSeconds(5),
                        clock.get()),
                WorkEnqueueOptions.DEFAULT);
        runtime.claimReady("t1", targets("w1"), 1, 30);

        runtime.shutdown();

        assertThat(runtime.stats("t1")).isEqualTo(TaskWorkStats.EMPTY);
        assertThat(runtime.stats().readyItems()).isZero();
        assertThat(runtime.stats().inflightItems()).isZero();
        assertThat(runtime.stats().delayedItems()).isZero();
        assertThat(runtime.enqueue(item("t1", "m3"), WorkEnqueueOptions.DEFAULT).status())
                .isEqualTo(WorkEnqueueStatus.STORE_UNAVAILABLE);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    protected TaskWorkEnvelope item(String taskId, String messageId) {
        return new TaskWorkEnvelope(taskId, messageId, "demo.event",
                Map.of("key", messageId), null, 0, 3, null, null, clock.get());
    }

    protected List<WorkerClaimTarget> targets(String workerId) {
        return List.of(WorkerClaimTarget.workerLevel(workerId, "batch-1", 1));
    }

    private <T> List<T> runConcurrently(int contenders, ConcurrentAction<T> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(contenders);
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>(contenders);
        try {
            for (int i = 0; i < contenders; i++) {
                final int index = i;
                futures.add(executor.submit(new Callable<>() {
                    @Override
                    public T call() throws Exception {
                        ready.countDown();
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("concurrent runtime action start latch timed out");
                        }
                        return action.apply(index);
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>(contenders);
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ConcurrentAction<T> {
        T apply(int index) throws Exception;
    }
}
