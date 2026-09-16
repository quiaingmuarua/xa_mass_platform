package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.score.WorkerScoreCore;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class WorkerServiceabilityDispatchPolicyTest {

    @Test
    void hotObservationIsDeferredWithoutPointReadBeforeProbeOffer() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        WorkerServiceabilityRuntime runtime = mock(
                WorkerServiceabilityRuntime.class
        );
        long opaqueScore = 777_777_777L;
        when(scores.observeHotCandidatesBefore(
                "group-1", 10_000L, 100
        )).thenReturn(Map.of(
                "worker-1", opaqueScore
        ));
        when(catalog.getWorkerDescriptors(List.of("worker-1"))).thenReturn(Map.of("worker-1", worker("worker-1", "adapter-1")));
        when(scores.deferObservedToRecovery("group-1", Map.of("worker-1", opaqueScore), 1_000L)).thenReturn(Map.of("worker-1", transitioned(-123L)));
        when(runtime.offerProbeRequests(
                "adapter-1", List.of("worker-1")
        )).thenReturn(Map.of("worker-1", ProbeRequestOfferStatus.OFFERED));

        int offered = policy(scores, catalog, runtime).dispatchProbes(
                List.of("group-1"),
                config()
        );

        assertEquals(1, offered);
        verify(scores, never()).observeSchedulingStates(anyString(), anyList());
        verify(scores).deferObservedToRecovery("group-1", Map.of("worker-1", opaqueScore), 1_000L);
        verify(scores, never()).observeRecoveryRecheckCandidates(
                "group-1", 100
        );
        var order = inOrder(scores, catalog, runtime);
        order.verify(scores).observeHotCandidatesBefore("group-1", 10_000L, 100);
        order.verify(catalog).getWorkerDescriptors(List.of("worker-1"));
        order.verify(scores).deferObservedToRecovery("group-1", Map.of("worker-1", opaqueScore), 1_000L);
        order.verify(runtime).offerProbeRequests("adapter-1", List.of("worker-1"));
        order.verifyNoMoreInteractions();
    }

    @Test
    void emptyHotPageFallsThroughToDueRecoveryAndAdvancesExactScore() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        WorkerServiceabilityRuntime runtime = mock(
                WorkerServiceabilityRuntime.class
        );
        long opaqueScore = -888_888_888L;
        when(scores.observeHotCandidatesBefore(
                "group-1", 10_000L, 100
        )).thenReturn(Map.of());
        when(scores.observeRecoveryRecheckCandidates(
                "group-1", 100
        )).thenReturn(Map.of(
                "worker-1", opaqueScore
        ));
        when(catalog.getWorkerDescriptors(List.of("worker-1"))).thenReturn(Map.of("worker-1", worker("worker-1", "adapter-1")));
        when(scores.deferObservedToRecovery("group-1", Map.of("worker-1", opaqueScore), 1_000L)).thenReturn(Map.of("worker-1", transitioned(-321L)));
        when(runtime.offerProbeRequests(
                "adapter-1", List.of("worker-1")
        )).thenReturn(Map.of("worker-1", ProbeRequestOfferStatus.OFFERED));

        assertEquals(1, policy(scores, catalog, runtime).dispatchProbes(
                List.of("group-1"),
                config()
        ));
        verify(scores).deferObservedToRecovery("group-1", Map.of("worker-1", opaqueScore), 1_000L);
        verify(scores, never()).observeSchedulingStates(anyString(), anyList());
    }

    @Test
    void recoveryAppearingAfterAnEmptyScanIsObservedOnTheNextRound() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        WorkerServiceabilityRuntime runtime = mock(WorkerServiceabilityRuntime.class);
        AtomicLong now = new AtomicLong(10_000L);
        long opaqueScore = -888_888_888L;
        when(scores.observeRecoveryRecheckCandidates("group-1", 100))
                .thenReturn(Map.of(), Map.of("worker-1", opaqueScore));
        when(catalog.getWorkerDescriptors(List.of("worker-1")))
                .thenReturn(Map.of("worker-1", worker("worker-1", "adapter-1")));
        when(scores.deferObservedToRecovery("group-1", Map.of("worker-1", opaqueScore), 1_000L))
                .thenReturn(Map.of("worker-1", transitioned(-321L)));
        when(runtime.offerProbeRequests("adapter-1", List.of("worker-1")))
                .thenReturn(Map.of("worker-1", ProbeRequestOfferStatus.OFFERED));
        WorkerServiceabilityDispatchPolicy policy = new WorkerServiceabilityDispatchPolicy(
                scores, catalog, runtime, now::get);

        assertEquals(0, policy.dispatchProbes(List.of("group-1"), config()));
        now.set(11_000L);
        assertEquals(1, policy.dispatchProbes(List.of("group-1"), config()));
        verify(scores, times(2)).observeRecoveryRecheckCandidates("group-1", 100);
        verify(scores).deferObservedToRecovery("group-1", Map.of("worker-1", opaqueScore), 1_000L);
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
        when(scores.observeHotCandidatesBefore(
                "group-1", 10_000L, 100
        )).thenReturn(Map.of(
                "worker-1", opaqueScore
        ));
        when(catalog.getWorkerDescriptors(List.of("worker-1"))).thenReturn(Map.of(
                "worker-1",
                worker("worker-1", "system-polling")
        ));
        when(scores.toggleCurrentPolarity(
                "group-1", "worker-1", opaqueScore
        )).thenReturn(transitioned(-opaqueScore));
        when(scores.parkObservedRecoveryScore(
                "group-1", "worker-1", -opaqueScore
        )).thenReturn(transitioned(-1L));

        assertEquals(0, policy(scores, catalog, runtime).dispatchProbes(
                List.of("group-1"),
                config()
        ));
        var order = inOrder(scores);
        order.verify(scores).toggleCurrentPolarity(
                "group-1", "worker-1", opaqueScore
        );
        order.verify(scores).parkObservedRecoveryScore(
                "group-1", "worker-1", -opaqueScore
        );
        verify(runtime, never()).offerProbeRequests(
                "system-polling", List.of("worker-1")
        );
    }

    @Test
    void rejectedHotHeadIsReadAgainWithoutFallingThroughToRecovery() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        WorkerServiceabilityRuntime runtime = mock(
                WorkerServiceabilityRuntime.class
        );
        when(scores.observeHotCandidatesBefore(
                "group-1", 10_000L, 100
        )).thenReturn(Map.of(
                "worker-1", 123_456_789L
        ));
        when(catalog.getWorkerDescriptors(List.of("worker-1")))
                .thenReturn(Map.of("worker-1", worker("worker-1", "adapter-1")));
        when(scores.deferObservedToRecovery("group-1", Map.of("worker-1", 123_456_789L), 1_000))
                .thenReturn(Map.of("worker-1", new WorkerScoreTransitionResult(
                        WorkerScoreTransitionStatus.STALE, null)));
        WorkerServiceabilityDispatchPolicy policy = policy(
                scores, catalog, runtime
        );

        policy.dispatchProbes(List.of("group-1"), config());
        policy.dispatchProbes(List.of("group-1"), config());

        verify(scores, times(2)).observeHotCandidatesBefore(
                "group-1", 10_000L, 100
        );
        verify(scores, never()).observeRecoveryRecheckCandidates("group-1", 100);
        verify(scores, times(2)).deferObservedToRecovery("group-1", Map.of("worker-1", 123_456_789L), 1_000);
        verifyNoInteractions(runtime);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void onlySuccessfulExactDeferralsOfferProbes(boolean hot) {
        var scores = mock(WorkerScoreCore.class);
        var catalog = mock(WorkerResourceCatalog.class);
        var runtime = mock(WorkerServiceabilityRuntime.class);
        var ids = List.of("deleted", "time-changed", "dirty-changed", "polarity-changed", "paused", "unchanged");
        long fence = hot ? 777L : -888L;
        var observations = observations(ids, fence);
        if (hot) {
            when(scores.observeHotCandidatesBefore("group-1", 10_000, 100)).thenReturn(observations);
        } else {
            when(scores.observeRecoveryRecheckCandidates("group-1", 100)).thenReturn(observations);
        }
        var descriptors = new LinkedHashMap<String, WorkerDescriptor>();
        var fences = new LinkedHashMap<String, Long>();
        var results = new LinkedHashMap<String, WorkerScoreTransitionResult>();
        ids.forEach(id -> {
            descriptors.put(id, worker(id, "adapter-1"));
            fences.put(id, fence);
            results.put(id, new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.STALE, null));
        });
        results.put("unchanged", transitioned(-999L));
        when(catalog.getWorkerDescriptors(ids)).thenReturn(descriptors);
        when(scores.deferObservedToRecovery("group-1", fences, 1_000)).thenReturn(results);
        when(runtime.offerProbeRequests("adapter-1", List.of("unchanged")))
                .thenReturn(Map.of("unchanged", ProbeRequestOfferStatus.OFFERED));

        assertEquals(1, policy(scores, catalog, runtime).dispatchProbes(List.of("group-1"), config()));
        verify(scores, never()).observeSchedulingStates(anyString(), anyList());
        verify(scores).deferObservedToRecovery("group-1", fences, 1_000);
        verify(runtime).offerProbeRequests("adapter-1", List.of("unchanged"));
        org.mockito.Mockito.verifyNoMoreInteractions(runtime);
    }

    @Test
    void missingOrMismatchedBindingCannotDeferOrParkAnObservedWorker() {
        var scores = mock(WorkerScoreCore.class);
        var catalog = mock(WorkerResourceCatalog.class);
        var runtime = mock(WorkerServiceabilityRuntime.class);
        var ids = List.of("missing", "wrong-group", "wrong-id");
        when(scores.observeHotCandidatesBefore("group-1", 10_000, 100))
                .thenReturn(observations(ids, 777L));
        when(catalog.getWorkerDescriptors(ids)).thenReturn(Map.of(
                "wrong-group", new WorkerDescriptor("wrong-group", "group-2", "system-polling"),
                "wrong-id", worker("another-worker", "system-polling")));

        assertEquals(0, policy(scores, catalog, runtime).dispatchProbes(List.of("group-1"), config()));
        var order = inOrder(scores);
        order.verify(scores).observeHotCandidatesBefore("group-1", 10_000, 100);
        order.verify(scores).deferObservedToRecovery("group-1", Map.of(), 1_000);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(runtime);
    }

    @Test
    void excludedHotWorkerWithChangedFenceIsNotColdParked() {
        var scores = mock(WorkerScoreCore.class);
        var catalog = mock(WorkerResourceCatalog.class);
        var runtime = mock(WorkerServiceabilityRuntime.class);
        when(scores.observeHotCandidatesBefore("group-1", 10_000, 100))
                .thenReturn(Map.of("worker-1", 777L));
        when(catalog.getWorkerDescriptors(List.of("worker-1")))
                .thenReturn(Map.of("worker-1", worker("worker-1", "system-polling")));
        when(scores.toggleCurrentPolarity("group-1", "worker-1", 777L))
                .thenReturn(new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.STALE, 999L));

        assertEquals(0, policy(scores, catalog, runtime).dispatchProbes(List.of("group-1"), config()));
        verify(scores, never()).parkObservedRecoveryScore(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong());
        verifyNoInteractions(runtime);
    }

    @Test
    void stalePostEpochHotCandidateEntersProbeCompensation() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        WorkerServiceabilityRuntime runtime = mock(
                WorkerServiceabilityRuntime.class
        );
        long opaqueScore = 444_444_444L;
        when(scores.observeHotCandidatesBefore(
                "group-1", 19_000L, 100
        )).thenReturn(Map.of(
                "worker-1", opaqueScore
        ));
        when(catalog.getWorkerDescriptors(List.of("worker-1"))).thenReturn(Map.of("worker-1", worker("worker-1", "adapter-1")));
        when(scores.deferObservedToRecovery("group-1", Map.of("worker-1", opaqueScore), 1_000L)).thenReturn(Map.of("worker-1", transitioned(-123L)));
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

    @Test
    void recoveryKeepsTheSameDelayAcrossManyLateSchedulingRounds() {
        var scores = mock(WorkerScoreCore.class);
        var catalog = mock(WorkerResourceCatalog.class);
        var runtime = mock(WorkerServiceabilityRuntime.class);
        var now = new AtomicLong(200_000);
        var policy = new WorkerServiceabilityDispatchPolicy(scores, catalog, runtime, now::get);
        var config = WorkerServiceabilityDispatchConfig.defaults(10_000);
        when(catalog.getWorkerDescriptors(List.of("worker-1")))
                .thenReturn(Map.of("worker-1", worker("worker-1", "adapter-1")));
        when(runtime.offerProbeRequests("adapter-1", List.of("worker-1")))
                .thenReturn(Map.of("worker-1", ProbeRequestOfferStatus.OFFERED));
        for (int round = 0; round < 8; round++) {
            long fence = -7_000L - round;
            when(scores.observeRecoveryRecheckCandidates("group-1", 100))
                    .thenReturn(Map.of("worker-1", fence));
            var targets = Map.of("worker-1", fence);
            when(scores.deferObservedToRecovery("group-1", targets, 15_000))
                    .thenReturn(Map.of("worker-1", transitioned(-8_000L - round)));
            assertEquals(1, policy.dispatchProbes(List.of("group-1"), config));
            verify(scores).deferObservedToRecovery("group-1", targets, 15_000);
            // Eligibility is not a deadline: the next Producer invocation can be much later.
            now.addAndGet(60_000);
        }
        verify(scores).observeHotCandidatesBefore("group-1", 140_000, 100);
        verify(scores, never()).parkObservedRecoveryScore(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(runtime, times(8)).offerProbeRequests("adapter-1", List.of("worker-1"));
    }

    @ParameterizedTest
    @EnumSource(ProbeRequestOfferStatus.class)
    void successfulHoldsConsumeTheRoundBudgetRegardlessOfOfferStatus(ProbeRequestOfferStatus status) {
        var scores = mock(WorkerScoreCore.class);
        var catalog = mock(WorkerResourceCatalog.class);
        var runtime = mock(WorkerServiceabilityRuntime.class);
        var ids = java.util.stream.IntStream.range(0, 100).mapToObj(i -> "worker-" + i).toList();
        long fence = 1234567L;
        when(scores.observeHotCandidatesBefore("group-1", 10_000L, 100))
                .thenReturn(observations(ids, fence));
        var descriptors = new LinkedHashMap<String, WorkerDescriptor>();
        var targets = new LinkedHashMap<String, Long>();
        var held = new LinkedHashMap<String, WorkerScoreTransitionResult>();
        var offers = new LinkedHashMap<String, ProbeRequestOfferStatus>();
        ids.forEach(id -> {
            descriptors.put(id, worker(id, "adapter-1"));
            targets.put(id, fence);
            held.put(id, transitioned(-987654L));
            offers.put(id, status);
        });
        when(catalog.getWorkerDescriptors(ids)).thenReturn(descriptors);
        when(scores.deferObservedToRecovery("group-1", targets, 1_000)).thenReturn(held);
        when(runtime.offerProbeRequests("adapter-1", ids)).thenReturn(offers);

        assertEquals(status == ProbeRequestOfferStatus.OFFERED ? 100 : 0,
                policy(scores, catalog, runtime).dispatchProbes(List.of("group-1", "group-2"), config()));
        verify(scores, never()).observeHotCandidatesBefore("group-2", 10_000L, 100);
        verify(scores, never()).releaseScoreHolds(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void recheckDelayAndHotThresholdAreIndependentPositiveDurations() {
        var defaults = WorkerServiceabilityDispatchConfig.defaults(10_000);
        assertEquals(15_000, defaults.recheckDelayMillis());
        assertEquals(60_000, defaults.hotProbeStaleAfterMillis());
        assertEquals(1_000, defaults.intervalMillis());
        assertThrows(IllegalArgumentException.class, () -> new WorkerServiceabilityDispatchConfig(
                1_000, 10_000, 0, 60_000, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new WorkerServiceabilityDispatchConfig(
                1_000, 10_000, 15_000, 0, List.of()));
    }

    @Test
    void probeSubmissionExceptionDoesNotUndoTheSuccessfulDeferral() {
        var scores = mock(WorkerScoreCore.class);
        var catalog = mock(WorkerResourceCatalog.class);
        var runtime = mock(WorkerServiceabilityRuntime.class);
        long fence = -777L;
        when(scores.observeRecoveryRecheckCandidates("group-1", 100))
                .thenReturn(Map.of("worker-1", fence));
        when(catalog.getWorkerDescriptors(List.of("worker-1")))
                .thenReturn(Map.of("worker-1", worker("worker-1", "adapter-1")));
        var targets = Map.of("worker-1", fence);
        when(scores.deferObservedToRecovery("group-1", targets, 15_000))
                .thenReturn(Map.of("worker-1", transitioned(-999L)));
        when(runtime.offerProbeRequests("adapter-1", List.of("worker-1")))
                .thenThrow(new IllegalStateException("offer unavailable"));

        assertThrows(IllegalStateException.class, () -> policy(scores, catalog, runtime)
                .dispatchProbes(List.of("group-1"), WorkerServiceabilityDispatchConfig.defaults(10_000)));
        var order = inOrder(scores, runtime);
        order.verify(scores).observeHotCandidatesBefore("group-1", 10_000, 100);
        order.verify(scores).observeRecoveryRecheckCandidates("group-1", 100);
        order.verify(scores).deferObservedToRecovery("group-1", targets, 15_000);
        order.verify(runtime).offerProbeRequests("adapter-1", List.of("worker-1"));
        order.verifyNoMoreInteractions();
    }

    @Test
    void excludedRecoveryIsParkedDirectlyWithoutOfferingOrChangingPolarity() {
        var scores = mock(WorkerScoreCore.class);
        var catalog = mock(WorkerResourceCatalog.class);
        var runtime = mock(WorkerServiceabilityRuntime.class);
        long fence = -777L;
        when(scores.observeRecoveryRecheckCandidates("group-1", 100))
                .thenReturn(Map.of("worker-1", fence));
        when(catalog.getWorkerDescriptors(List.of("worker-1")))
                .thenReturn(Map.of("worker-1", worker("worker-1", "system-polling")));

        assertEquals(0, policy(scores, catalog, runtime).dispatchProbes(List.of("group-1"), config()));
        verify(scores).parkObservedRecoveryScore("group-1", "worker-1", fence);
        verify(scores, never()).toggleCurrentPolarity("group-1", "worker-1", fence);
        verify(scores).deferObservedToRecovery("group-1", Map.of(), 1_000);
        org.mockito.Mockito.verifyNoInteractions(runtime);
    }

    @Test
    void forwardsMillisecondFloorAndStaleCutoffWithoutSlotAlignment() {
        var scores = mock(WorkerScoreCore.class);
        var catalog = mock(WorkerResourceCatalog.class);
        var runtime = mock(WorkerServiceabilityRuntime.class);
        var config = new WorkerServiceabilityDispatchConfig(1_000, 10_055, 15_000, 1_001, List.of());
        policy(scores, catalog, runtime, 10_060).dispatchProbes(List.of("group-1"), config);
        verify(scores).observeHotCandidatesBefore("group-1", 10_055, 100);
        policy(scores, catalog, runtime, 20_087).dispatchProbes(List.of("group-1"), config);
        verify(scores).observeHotCandidatesBefore("group-1", 19_086, 100);
    }

    private static Map<String, Long> observations(List<String> ids, long fence) {
        var observations = new LinkedHashMap<String, Long>();
        ids.forEach(id -> observations.put(id, fence));
        return observations;
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
                1_000L,
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
