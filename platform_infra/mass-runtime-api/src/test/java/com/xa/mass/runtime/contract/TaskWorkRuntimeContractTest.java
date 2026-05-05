package com.xa.mass.runtime.contract;

import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.ResultApplyOutcome;
import com.xa.mass.runtime.api.ResultApplyStatus;
import com.xa.mass.runtime.api.TaskWorkEnvelope;
import com.xa.mass.runtime.api.TaskWorkResult;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.runtime.api.WorkEnqueueOptions;
import com.xa.mass.runtime.api.WorkEnqueueStatus;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        destroyRuntime(runtime);
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
    void hasReadyWork_isTrueAfterEnqueue() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        assertThat(runtime.hasReadyWork("t1")).isTrue();
    }

    @Test
    void hasReadyWork_isFalse_whenNoWorkEnqueued() {
        assertThat(runtime.hasReadyWork("t1")).isFalse();
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
    void hasReadyWork_isFalse_afterClaim() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        runtime.claimReady("t1", targets("w1"), 1, 30);
        assertThat(runtime.hasReadyWork("t1")).isFalse();
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
    void applyResult_withStaleToken_returnsStaleLeaseNotException() {
        runtime.enqueue(item("t1", "m1"), WorkEnqueueOptions.DEFAULT);
        runtime.claimReady("t1", targets("w1"), 1, 30);
        assertThat(runtime.applyResult(
                TaskWorkResult.success("t1", "m1", "stale-token", "done", Map.of())).status())
                .isEqualTo(ResultApplyStatus.STALE_LEASE);
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

    // ── helpers ───────────────────────────────────────────────────────────────

    protected TaskWorkEnvelope item(String taskId, String messageId) {
        return new TaskWorkEnvelope(taskId, messageId, "demo.event",
                Map.of("key", messageId), null, 0, 3, null, null, clock.get());
    }

    protected List<WorkerClaimTarget> targets(String workerId) {
        return List.of(new WorkerClaimTarget(workerId, "ctx-1", "batch-1", 1));
    }
}
