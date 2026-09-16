package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreDelayTarget;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreObservation;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime.ProbeRequestOfferStatus;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerDescriptor;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class WorkerServiceabilityDispatchPolicyTest {

    @Test
    void hotCandidateIsExactRecheckedHeldAndOffered() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        WorkerServiceabilityRuntime runtime = mock(
                WorkerServiceabilityRuntime.class
        );
        long opaqueScore = 777_777_777L;
        when(scores.acquireHotCandidatesBefore(
                "group-1", 10_000L, 100
        )).thenReturn(List.of(new WorkerScoreObservation(
                "worker-1", opaqueScore
        )));
        when(scores.getScoreStates("group-1", List.of("worker-1")))
                .thenReturn(Map.of("worker-1", new WorkerScoreState(
                        "worker-1",
                        opaqueScore,
                        WorkerScorePolarity.HOT_ACQUIRE,
                        9_000L,
                        0,
                        0
                )));
        when(catalog.getWorkerDescriptors(List.of("worker-1"))).thenReturn(Map.of("worker-1", worker("worker-1", "adapter-1")));
        when(scores.deferObservedToRecovery(
                "group-1", Map.of("worker-1", new WorkerScoreDelayTarget(opaqueScore, 1_000L, 0))
        )).thenReturn(Map.of("worker-1", transitioned(-123L)));
        when(runtime.offerProbeRequests(
                "adapter-1", List.of("worker-1")
        )).thenReturn(Map.of("worker-1", ProbeRequestOfferStatus.OFFERED));

        int offered = policy(scores, catalog, runtime).dispatchProbes(
                List.of("group-1"),
                config()
        );

        assertEquals(1, offered);
        verify(scores).deferObservedToRecovery(
                "group-1", Map.of("worker-1", new WorkerScoreDelayTarget(opaqueScore, 1_000L, 0))
        );
        verify(scores, never()).acquireRecoveryRecheckCandidates(
                "group-1", 100
        );
        var order = inOrder(scores, runtime);
        order.verify(scores).deferObservedToRecovery(
                "group-1", Map.of("worker-1", new WorkerScoreDelayTarget(opaqueScore, 1_000L, 0))
        );
        order.verify(runtime).offerProbeRequests("adapter-1", List.of("worker-1"));
    }

    @Test
    void emptyHotPageFallsThroughToDueRecoveryAndAdvancesExactScore() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        WorkerServiceabilityRuntime runtime = mock(
                WorkerServiceabilityRuntime.class
        );
        long opaqueScore = -888_888_888L;
        when(scores.acquireHotCandidatesBefore(
                "group-1", 10_000L, 100
        )).thenReturn(List.of());
        when(scores.acquireRecoveryRecheckCandidates(
                "group-1", 100
        )).thenReturn(List.of(new WorkerScoreObservation(
                "worker-1", opaqueScore
        )));
        when(scores.getScoreStates("group-1", List.of("worker-1")))
                .thenReturn(Map.of("worker-1", new WorkerScoreState(
                        "worker-1",
                        opaqueScore,
                        WorkerScorePolarity.RECOVERY_RECHECK,
                        9_900L,
                        1,
                        0
                )));
        when(catalog.getWorkerDescriptors(List.of("worker-1"))).thenReturn(Map.of("worker-1", worker("worker-1", "adapter-1")));
        when(scores.deferObservedToRecovery(
                "group-1", Map.of("worker-1", new WorkerScoreDelayTarget(opaqueScore, 3_000L, 2))
        )).thenReturn(Map.of("worker-1", transitioned(-321L)));
        when(runtime.offerProbeRequests(
                "adapter-1", List.of("worker-1")
        )).thenReturn(Map.of("worker-1", ProbeRequestOfferStatus.OFFERED));

        assertEquals(1, policy(scores, catalog, runtime).dispatchProbes(
                List.of("group-1"),
                config()
        ));
        verify(scores).deferObservedToRecovery(
                "group-1", Map.of("worker-1", new WorkerScoreDelayTarget(opaqueScore, 3_000L, 2))
        );
    }

    @Test
    void recoveryAppearingAfterAnEmptyScanIsObservedOnTheNextRound() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        WorkerServiceabilityRuntime runtime = mock(WorkerServiceabilityRuntime.class);
        AtomicLong now = new AtomicLong(10_000L);
        long opaqueScore = -888_888_888L;
        when(scores.acquireRecoveryRecheckCandidates("group-1", 100))
                .thenReturn(List.of(), List.of(new WorkerScoreObservation("worker-1", opaqueScore)));
        when(scores.getScoreStates("group-1", List.of("worker-1")))
                .thenReturn(Map.of("worker-1", new WorkerScoreState(
                        "worker-1", opaqueScore, WorkerScorePolarity.RECOVERY_RECHECK,
                        10_000L, 0, 0)));
        when(catalog.getWorkerDescriptors(List.of("worker-1")))
                .thenReturn(Map.of("worker-1", worker("worker-1", "adapter-1")));
        when(scores.deferObservedToRecovery("group-1", Map.of("worker-1", new WorkerScoreDelayTarget(opaqueScore, 2_000L, 1))))
                .thenReturn(Map.of("worker-1", transitioned(-321L)));
        when(runtime.offerProbeRequests("adapter-1", List.of("worker-1")))
                .thenReturn(Map.of("worker-1", ProbeRequestOfferStatus.OFFERED));
        WorkerServiceabilityDispatchPolicy policy = new WorkerServiceabilityDispatchPolicy(
                scores, catalog, runtime, now::get);

        assertEquals(0, policy.dispatchProbes(List.of("group-1"), config()));
        now.set(11_000L);
        assertEquals(1, policy.dispatchProbes(List.of("group-1"), config()));
        verify(scores, times(2)).acquireRecoveryRecheckCandidates("group-1", 100);
        verify(scores).deferObservedToRecovery("group-1", Map.of("worker-1", new WorkerScoreDelayTarget(opaqueScore, 2_000L, 1)));
        verify(runtime).offerProbeRequests("adapter-1", List.of("worker-1"));
    }

    @Test
    void excludedEndpointIsColdParkedWithoutProbeOffer() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        WorkerServiceabilityRuntime runtime = mock(
                WorkerServiceabilityRuntime.class
        );
        long opaqueScore = 999_999_999L;
        when(scores.acquireHotCandidatesBefore(
                "group-1", 10_000L, 100
        )).thenReturn(List.of(new WorkerScoreObservation(
                "worker-1", opaqueScore
        )));
        when(scores.getScoreStates("group-1", List.of("worker-1")))
                .thenReturn(Map.of("worker-1", new WorkerScoreState(
                        "worker-1",
                        opaqueScore,
                        WorkerScorePolarity.HOT_ACQUIRE,
                        9_000L,
                        0,
                        0
                )));
        when(catalog.getWorkerDescriptors(List.of("worker-1"))).thenReturn(Map.of(
                "worker-1",
                worker("worker-1", "system-polling")
        ));
        when(scores.toggleCurrentPolarity(
                "group-1", "worker-1", opaqueScore
        )).thenReturn(transitioned(-opaqueScore));
        when(scores.parkObservedRecoveryScore(
                "group-1", "worker-1", -opaqueScore, 5
        )).thenReturn(transitioned(-1L));

        assertEquals(0, policy(scores, catalog, runtime).dispatchProbes(
                List.of("group-1"),
                config()
        ));
        verify(scores).toggleCurrentPolarity(
                "group-1", "worker-1", opaqueScore
        );
        verify(scores).parkObservedRecoveryScore(
                "group-1", "worker-1", -opaqueScore, 5
        );
        verify(runtime, never()).offerProbeRequests(
                "system-polling", List.of("worker-1")
        );
    }

    @Test
    void unchangedHotHeadIsReadAgainWithoutFallingThroughToRecovery() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        WorkerServiceabilityRuntime runtime = mock(
                WorkerServiceabilityRuntime.class
        );
        when(scores.acquireHotCandidatesBefore(
                "group-1", 10_000L, 100
        )).thenReturn(List.of(new WorkerScoreObservation(
                "worker-1", 123_456_789L
        )));
        when(scores.getScoreStates("group-1", List.of("worker-1")))
                .thenReturn(Map.of());
        WorkerServiceabilityDispatchPolicy policy = policy(
                scores, catalog, runtime
        );

        policy.dispatchProbes(List.of("group-1"), config());
        policy.dispatchProbes(List.of("group-1"), config());

        verify(scores, times(2)).acquireHotCandidatesBefore(
                "group-1", 10_000L, 100
        );
        verify(scores, never()).acquireRecoveryRecheckCandidates("group-1", 100);
    }

    @Test
    void stalePostEpochHotCandidateEntersProbeCompensation() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        WorkerServiceabilityRuntime runtime = mock(
                WorkerServiceabilityRuntime.class
        );
        long opaqueScore = 444_444_444L;
        when(scores.acquireHotCandidatesBefore(
                "group-1", 19_000L, 100
        )).thenReturn(List.of(new WorkerScoreObservation(
                "worker-1", opaqueScore
        )));
        when(scores.getScoreStates("group-1", List.of("worker-1")))
                .thenReturn(Map.of("worker-1", new WorkerScoreState(
                        "worker-1",
                        opaqueScore,
                        WorkerScorePolarity.HOT_ACQUIRE,
                        18_000L,
                        0,
                        0
                )));
        when(catalog.getWorkerDescriptors(List.of("worker-1"))).thenReturn(Map.of("worker-1", worker("worker-1", "adapter-1")));
        when(scores.deferObservedToRecovery(
                "group-1", Map.of("worker-1", new WorkerScoreDelayTarget(opaqueScore, 1_000L, 0))
        )).thenReturn(Map.of("worker-1", transitioned(-123L)));
        when(runtime.offerProbeRequests(
                "adapter-1", List.of("worker-1")
        )).thenReturn(Map.of("worker-1", ProbeRequestOfferStatus.OFFERED));

        int offered = policy(
                scores,
                catalog,
                runtime,
                20_000L
        ).dispatchProbes(List.of("group-1"), config());

        assertEquals(1, offered);
    }

    @ParameterizedTest
    @CsvSource({"0,2000", "4,6000"})
    void dueRecoveryRankOnlyDeterminesTheNextDelay(int rank, long expectedDelay) {
        var scores = mock(WorkerScoreCore.class);
        var catalog = mock(WorkerResourceCatalog.class);
        var runtime = mock(WorkerServiceabilityRuntime.class);
        long fence = -7654321L;
        when(scores.acquireRecoveryRecheckCandidates("group-1", 100))
                .thenReturn(List.of(new WorkerScoreObservation("worker-1", fence)));
        // Already due according to the Owner, even though the old rank-based wait has not elapsed.
        when(scores.getScoreStates("group-1", List.of("worker-1")))
                .thenReturn(Map.of("worker-1", new WorkerScoreState(
                        "worker-1", fence, WorkerScorePolarity.RECOVERY_RECHECK, 9_900, rank, 1)));
        when(catalog.getWorkerDescriptors(List.of("worker-1")))
                .thenReturn(Map.of("worker-1", worker("worker-1", "adapter-1")));
        var targets = Map.of("worker-1", new WorkerScoreDelayTarget(fence, expectedDelay, rank + 1));
        when(scores.deferObservedToRecovery("group-1", targets))
                .thenReturn(Map.of("worker-1", transitioned(-123L)));
        when(runtime.offerProbeRequests("adapter-1", List.of("worker-1")))
                .thenReturn(Map.of("worker-1", ProbeRequestOfferStatus.OFFERED));

        assertEquals(1, policy(scores, catalog, runtime).dispatchProbes(List.of("group-1"), config()));
        verify(scores).deferObservedToRecovery("group-1", targets);
    }

    @Test
    void exhaustedDueRecoveryIsColdParkedWithoutAnotherProbe() {
        var scores = mock(WorkerScoreCore.class);
        var catalog = mock(WorkerResourceCatalog.class);
        var runtime = mock(WorkerServiceabilityRuntime.class);
        long fence = -7654321L;
        when(scores.acquireRecoveryRecheckCandidates("group-1", 100))
                .thenReturn(List.of(new WorkerScoreObservation("worker-1", fence)));
        when(scores.getScoreStates("group-1", List.of("worker-1")))
                .thenReturn(Map.of("worker-1", new WorkerScoreState(
                        "worker-1", fence, WorkerScorePolarity.RECOVERY_RECHECK, 9_900, 5, 1)));
        when(catalog.getWorkerDescriptors(List.of("worker-1")))
                .thenReturn(Map.of("worker-1", worker("worker-1", "adapter-1")));

        assertEquals(0, policy(scores, catalog, runtime).dispatchProbes(List.of("group-1"), config()));
        verify(scores).parkObservedRecoveryScore("group-1", "worker-1", fence, 5);
        verify(scores).deferObservedToRecovery("group-1", Map.of());
        org.mockito.Mockito.verifyNoInteractions(runtime);
    }

    @ParameterizedTest
    @EnumSource(ProbeRequestOfferStatus.class)
    void successfulHoldsConsumeTheRoundBudgetRegardlessOfOfferStatus(ProbeRequestOfferStatus status) {
        var scores = mock(WorkerScoreCore.class);
        var catalog = mock(WorkerResourceCatalog.class);
        var runtime = mock(WorkerServiceabilityRuntime.class);
        var ids = java.util.stream.IntStream.range(0, 100).mapToObj(i -> "worker-" + i).toList();
        long fence = 1234567L;
        when(scores.acquireHotCandidatesBefore("group-1", 10_000L, 100))
                .thenReturn(ids.stream().map(id -> new WorkerScoreObservation(id, fence)).toList());
        var states = new LinkedHashMap<String, WorkerScoreState>();
        var descriptors = new LinkedHashMap<String, WorkerDescriptor>();
        var targets = new LinkedHashMap<String, WorkerScoreDelayTarget>();
        var held = new LinkedHashMap<String, WorkerScoreTransitionResult>();
        var offers = new LinkedHashMap<String, ProbeRequestOfferStatus>();
        ids.forEach(id -> {
            states.put(id, new WorkerScoreState(id, fence, WorkerScorePolarity.HOT_ACQUIRE, 9_000, 0, 0));
            descriptors.put(id, worker(id, "adapter-1"));
            targets.put(id, new WorkerScoreDelayTarget(fence, 1_000, 0));
            held.put(id, transitioned(-987654L));
            offers.put(id, status);
        });
        when(scores.getScoreStates("group-1", ids)).thenReturn(states);
        when(catalog.getWorkerDescriptors(ids)).thenReturn(descriptors);
        when(scores.deferObservedToRecovery("group-1", targets)).thenReturn(held);
        when(runtime.offerProbeRequests("adapter-1", ids)).thenReturn(offers);

        assertEquals(status == ProbeRequestOfferStatus.OFFERED ? 100 : 0,
                policy(scores, catalog, runtime).dispatchProbes(List.of("group-1", "group-2"), config()));
        verify(scores, never()).acquireHotCandidatesBefore("group-2", 10_000L, 100);
        verify(scores, never()).releaseScoreHolds(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void retryDelayOverflowIsRejectedAtConfigurationTime() {
        assertThrows(IllegalArgumentException.class, () -> new WorkerServiceabilityDispatchConfig(
                1_000, 10_000, Long.MAX_VALUE / 6 + 1, 5, List.of()));
        assertEquals(Long.MAX_VALUE / 6, new WorkerServiceabilityDispatchConfig(
                1_000, 10_000, Long.MAX_VALUE / 6, 5, List.of()).probeRetryIntervalMillis());
    }

    private static WorkerServiceabilityDispatchPolicy policy(
            WorkerScoreCore scores,
            WorkerResourceCatalog catalog,
            WorkerServiceabilityRuntime runtime
    ) {
        return policy(scores, catalog, runtime, 10_000L);
    }

    private static WorkerServiceabilityDispatchPolicy policy(
            WorkerScoreCore scores,
            WorkerResourceCatalog catalog,
            WorkerServiceabilityRuntime runtime,
            long currentTimeMillis
    ) {
        return new WorkerServiceabilityDispatchPolicy(
                scores,
                catalog,
                runtime,
                () -> currentTimeMillis
        );
    }

    private static WorkerServiceabilityDispatchConfig config() {
        return new WorkerServiceabilityDispatchConfig(
                1_000L,
                10_000L,
                1_000L,
                5,
                List.of("system-polling")
        );
    }

    private static WorkerDescriptor worker(
            String workerId,
            String endpointManagerId
    ) {
        return new WorkerDescriptor(
                workerId,
                "group-1",
                endpointManagerId
        );
    }

    private static WorkerScoreTransitionResult transitioned(long score) {
        return new WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED,
                score
        );
    }
}
