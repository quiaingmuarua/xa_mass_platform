package com.xa.mass.runtime.contract;

import com.xa.mass.runtime.worker.slot.WorkerScoreBand;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandAcquireRequest;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandKind;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlot;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlotMetadata;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlotRuntime;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandTransitionCommand;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandTransitionResult;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandTransitionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared score-band worker slot contract for memory and Redis implementations.
 */
public abstract class WorkerScoreBandSlotRuntimeContractTest {

    protected static final long NOW = WorkerScoreBand.LOW_RECHECK_EPOCH_MILLIS + 10_000L;

    protected abstract WorkerScoreBandSlotRuntime createRuntime();

    @Test
    void acquireReturnsOnlyTimeDueSlotsOrderedByScore() {
        WorkerScoreBandSlotRuntime runtime = createRuntime();
        runtime.upsert(metadata("group-a", "worker-3"), WorkerScoreBand.eligibleScore(NOW + 3), "register", NOW);
        runtime.upsert(metadata("group-a", "worker-1"), WorkerScoreBand.eligibleScore(NOW + 1), "register", NOW);
        runtime.upsert(metadata("group-a", "worker-2"), WorkerScoreBand.futureScore(NOW + 10_000), "busy", NOW);
        runtime.upsert(metadata("group-b", "worker-4"), WorkerScoreBand.eligibleScore(NOW), "register", NOW);

        List<WorkerScoreBandSlot> selected = runtime.acquire(
                WorkerScoreBandAcquireRequest.inHomeBucket("group-a", 10, NOW + 5)
        );

        assertEquals(List.of("worker-1", "worker-3"), selected.stream().map(WorkerScoreBandSlot::workerId).toList());
    }

    @Test
    void parkedAndLowRecheckSlotsAreNotHotPathAcquireVisible() {
        WorkerScoreBandSlotRuntime runtime = createRuntime();
        runtime.upsert(metadata("group-a", "worker-parked"), WorkerScoreBand.PARKED_DISABLED, "disabled", NOW);
        runtime.upsert(metadata("group-a", "worker-recheck"), WorkerScoreBand.lowRecheckScore(NOW + 1_000), "block", NOW);
        runtime.upsert(metadata("group-a", "worker-eligible"), WorkerScoreBand.eligibleScore(NOW), "register", NOW);

        List<WorkerScoreBandSlot> selected = runtime.acquire(
                WorkerScoreBandAcquireRequest.inHomeBucket("group-a", 10, NOW)
        );

        assertEquals(List.of("worker-eligible"), selected.stream().map(WorkerScoreBandSlot::workerId).toList());
    }

    @Test
    void futureScoreBecomesVisibleByTimeWithoutWriter() {
        WorkerScoreBandSlotRuntime runtime = createRuntime();
        long dueAt = NOW + 1_000;
        runtime.upsert(metadata("group-a", "worker-1"), WorkerScoreBand.futureScore(dueAt), "attempt", NOW);

        assertTrue(runtime.acquire(WorkerScoreBandAcquireRequest.inHomeBucket("group-a", 10, NOW)).isEmpty());

        List<WorkerScoreBandSlot> due = runtime.acquire(
                WorkerScoreBandAcquireRequest.inHomeBucket("group-a", 10, dueAt)
        );

        assertEquals(List.of("worker-1"), due.stream().map(WorkerScoreBandSlot::workerId).toList());
        assertEquals(dueAt, runtime.slot("group-a", "worker-1").orElseThrow().score());
    }

    @Test
    void targetAcquireUsesWorkerIdInsideProvidedHomeBuckets() {
        WorkerScoreBandSlotRuntime runtime = createRuntime();
        runtime.upsert(metadata("group-a", "worker-1"), WorkerScoreBand.eligibleScore(NOW), "register", NOW);
        runtime.upsert(metadata("group-b", "worker-1"), WorkerScoreBand.eligibleScore(NOW + 1), "register", NOW);

        List<WorkerScoreBandSlot> selected = runtime.acquire(
                WorkerScoreBandAcquireRequest.targetInHomeBuckets(List.of("group-b"), "worker-1", NOW + 10)
        );

        assertEquals(1, selected.size());
        assertEquals("group-b", selected.getFirst().homeBucketId());
        assertEquals("worker-1", selected.getFirst().workerId());
    }

    @Test
    void removeClearsOldHomeBucketMembership() {
        WorkerScoreBandSlotRuntime runtime = createRuntime();
        runtime.upsert(metadata("group-a", "worker-1"), WorkerScoreBand.eligibleScore(NOW), "register", NOW);
        runtime.remove("group-a", "worker-1", "worker group changed", NOW + 1);

        assertTrue(runtime.slot("group-a", "worker-1").isEmpty());
        assertTrue(runtime.acquire(WorkerScoreBandAcquireRequest.inHomeBucket("group-a", 10, NOW + 10)).isEmpty());
    }

    @Test
    void claimCloseRequiresObservedFutureClaimAndCannotReopenParkedOrLowRecheck() {
        WorkerScoreBandSlotRuntime runtime = createRuntime();
        long claimScore = WorkerScoreBand.futureScore(NOW + 5_000);
        runtime.upsert(metadata("group-a", "worker-1"), claimScore, "claim", NOW);

        WorkerScoreBandTransitionResult closed = runtime.transition(
                WorkerScoreBandTransitionCommand.claimClose(
                        "group-a",
                        "worker-1",
                        claimScore,
                        WorkerScoreBand.eligibleScore(NOW),
                        "final",
                        NOW + 10
                )
        );
        assertTrue(closed.accepted());
        assertEquals(WorkerScoreBandKind.TIME_DUE, closed.after().band(NOW));

        WorkerScoreBandTransitionResult stale = runtime.transition(
                WorkerScoreBandTransitionCommand.claimClose(
                        "group-a",
                        "worker-1",
                        claimScore,
                        WorkerScoreBand.eligibleScore(NOW),
                        "duplicate-final",
                        NOW + 20
                )
        );
        assertEquals(WorkerScoreBandTransitionStatus.STALE_OBSERVATION, stale.status());

        runtime.transition(WorkerScoreBandTransitionCommand.recoverableNegative(
                "group-a", "worker-1", NOW + 1_000, "disconnect", NOW + 30));
        WorkerScoreBandTransitionResult lowRecheckClose = runtime.transition(
                WorkerScoreBandTransitionCommand.claimClose(
                        "group-a",
                        "worker-1",
                        runtime.slot("group-a", "worker-1").orElseThrow().score(),
                        WorkerScoreBand.eligibleScore(NOW),
                        "late-final",
                        NOW + 40
                )
        );
        assertEquals(WorkerScoreBandTransitionStatus.INVALID_TRANSITION, lowRecheckClose.status());
    }

    @Test
    void attemptTimeoutDoesNotWriteScore() {
        WorkerScoreBandSlotRuntime runtime = createRuntime();
        long claimScore = WorkerScoreBand.futureScore(NOW + 5_000);
        runtime.upsert(metadata("group-a", "worker-1"), claimScore, "claim", NOW);

        WorkerScoreBandTransitionResult eligibleWrite = runtime.transition(
                WorkerScoreBandTransitionCommand.attemptTimeout(
                        "group-a",
                        "worker-1",
                        WorkerScoreBand.eligibleScore(NOW),
                        "timeout",
                        NOW + 10
                )
        );
        assertEquals(WorkerScoreBandTransitionStatus.INVALID_TRANSITION, eligibleWrite.status());

        WorkerScoreBandTransitionResult lowRecheckWrite = runtime.transition(
                WorkerScoreBandTransitionCommand.attemptTimeout(
                        "group-a",
                        "worker-1",
                        WorkerScoreBand.lowRecheckScore(NOW + 1_000),
                        "timeout",
                        NOW + 15
                )
        );
        assertEquals(WorkerScoreBandTransitionStatus.INVALID_TRANSITION, lowRecheckWrite.status());

        WorkerScoreBandTransitionResult evidenceOnly = runtime.transition(
                WorkerScoreBandTransitionCommand.attemptTimeout(
                        "group-a",
                        "worker-1",
                        null,
                        "timeout",
                        NOW + 20
                )
        );
        assertTrue(evidenceOnly.accepted());
        assertEquals(claimScore, runtime.slot("group-a", "worker-1").orElseThrow().score());
    }

    @Test
    void ownerValidatedRecoveryCanRejectStaleObservation() {
        WorkerScoreBandSlotRuntime runtime = createRuntime();
        long lowRecheckScore = WorkerScoreBand.lowRecheckScore(NOW + 1_000);
        runtime.upsert(metadata("group-a", "worker-1"), lowRecheckScore, "disconnect", NOW);

        WorkerScoreBandTransitionResult recovered = runtime.transition(
                WorkerScoreBandTransitionCommand.ownerValidatedRecovery(
                        "group-a",
                        "worker-1",
                        lowRecheckScore,
                        WorkerScoreBand.eligibleScore(NOW),
                        "explicit-ready",
                        NOW + 10
                )
        );
        assertTrue(recovered.accepted());

        WorkerScoreBandTransitionResult stale = runtime.transition(
                WorkerScoreBandTransitionCommand.ownerValidatedRecovery(
                        "group-a",
                        "worker-1",
                        lowRecheckScore,
                        WorkerScoreBand.eligibleScore(NOW),
                        "duplicate-ready",
                        NOW + 20
                )
        );
        assertEquals(WorkerScoreBandTransitionStatus.STALE_OBSERVATION, stale.status());
    }

    @Test
    void metadataDefaultsRecoveryModeToExplicitOnly() {
        WorkerScoreBandSlotMetadata defaultMode = metadata("group-a", "worker-1");
        WorkerScoreBandSlotMetadata freshness = WorkerScoreBandSlotMetadata.worker(
                "group-a",
                "worker-2",
                null,
                Map.of("dispatchRecoveryMode", WorkerScoreBandSlotMetadata.RECOVERY_FRESHNESS_EVIDENCE),
                1
        );

        assertEquals(WorkerScoreBandSlotMetadata.RECOVERY_EXPLICIT_ONLY, defaultMode.dispatchRecoveryMode());
        assertFalse(defaultMode.freshnessEvidenceRecoveryAllowed());
        assertEquals(WorkerScoreBandSlotMetadata.RECOVERY_FRESHNESS_EVIDENCE, freshness.dispatchRecoveryMode());
        assertTrue(freshness.freshnessEvidenceRecoveryAllowed());
    }

    @Test
    void lowRecheckOverflowCannotSilentlyBecomeTimeScore() {
        assertThrows(IllegalArgumentException.class,
                () -> WorkerScoreBand.lowRecheckScore(WorkerScoreBand.LOW_RECHECK_EPOCH_MILLIS - 1));
        assertThrows(IllegalArgumentException.class,
                () -> WorkerScoreBand.lowRecheckScore(
                        WorkerScoreBand.LOW_RECHECK_EPOCH_MILLIS + WorkerScoreBand.TIME_SCORE_FLOOR
                ));
    }

    protected static WorkerScoreBandSlotMetadata metadata(String groupId, String workerId) {
        return WorkerScoreBandSlotMetadata.worker(groupId, workerId, null, Map.of(), 1);
    }
}
