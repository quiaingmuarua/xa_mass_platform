package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreObservation;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime.ProbeRequestOfferStatus;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
                "group-1", 10_000L, 0L, 100
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
        when(catalog.getWorkerDescriptors(
                "group-1", List.of("worker-1")
        )).thenReturn(Map.of("worker-1", worker("worker-1", "adapter-1")));
        when(scores.holdObservedHotForServiceabilityProbes(
                "group-1", Map.of("worker-1", opaqueScore)
        )).thenReturn(Map.of("worker-1", transitioned(-123L)));
        when(runtime.offerProbeRequests(
                "adapter-1", List.of("worker-1")
        )).thenReturn(Map.of("worker-1", ProbeRequestOfferStatus.OFFERED));

        int offered = policy(scores, catalog, runtime).dispatchProbes(
                List.of("group-1"),
                config(),
                10_000L
        );

        assertEquals(1, offered);
        verify(scores).holdObservedHotForServiceabilityProbes(
                "group-1", Map.of("worker-1", opaqueScore)
        );
        verify(scores, never()).acquireRecoveryRecheckCandidates(
                "group-1", 0L, 100
        );
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
                "group-1", 10_000L, 0L, 100
        )).thenReturn(List.of());
        when(scores.acquireRecoveryRecheckCandidates(
                "group-1", 0L, 100
        )).thenReturn(List.of(new WorkerScoreObservation(
                "worker-1", opaqueScore
        )));
        when(scores.getScoreStates("group-1", List.of("worker-1")))
                .thenReturn(Map.of("worker-1", new WorkerScoreState(
                        "worker-1",
                        opaqueScore,
                        WorkerScorePolarity.RECOVERY_RECHECK,
                        7_000L,
                        1,
                        0
                )));
        when(catalog.getWorkerDescriptors(
                "group-1", List.of("worker-1")
        )).thenReturn(Map.of("worker-1", worker("worker-1", "adapter-1")));
        when(scores.advanceObservedRecoveryRechecks(
                "group-1", Map.of("worker-1", opaqueScore)
        )).thenReturn(Map.of("worker-1", transitioned(-321L)));
        when(runtime.offerProbeRequests(
                "adapter-1", List.of("worker-1")
        )).thenReturn(Map.of("worker-1", ProbeRequestOfferStatus.OFFERED));

        assertEquals(1, policy(scores, catalog, runtime).dispatchProbes(
                List.of("group-1"),
                config(),
                10_000L
        ));
        verify(scores).advanceObservedRecoveryRechecks(
                "group-1", Map.of("worker-1", opaqueScore)
        );
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
                "group-1", 10_000L, 0L, 100
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
        when(catalog.getWorkerDescriptors(
                "group-1", List.of("worker-1")
        )).thenReturn(Map.of(
                "worker-1",
                worker("worker-1", "system-polling")
        ));
        when(scores.toggleCurrentPolarity(
                "group-1", "worker-1", opaqueScore
        )).thenReturn(transitioned(-opaqueScore));
        when(scores.exhaustRecoveryRecheck(
                "group-1", "worker-1", -opaqueScore, 5
        )).thenReturn(transitioned(-1L));

        assertEquals(0, policy(scores, catalog, runtime).dispatchProbes(
                List.of("group-1"),
                config(),
                10_000L
        ));
        verify(scores).toggleCurrentPolarity(
                "group-1", "worker-1", opaqueScore
        );
        verify(scores).exhaustRecoveryRecheck(
                "group-1", "worker-1", -opaqueScore, 5
        );
        verify(runtime, never()).offerProbeRequests(
                "system-polling", List.of("worker-1")
        );
    }

    @Test
    void nextHotPageUsesPreviousRawScoreAsExclusiveCursor() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        WorkerServiceabilityRuntime runtime = mock(
                WorkerServiceabilityRuntime.class
        );
        when(scores.acquireHotCandidatesBefore(
                "group-1", 10_000L, 0L, 100
        )).thenReturn(List.of(new WorkerScoreObservation(
                "worker-1", 123_456_789L
        )));
        when(scores.getScoreStates("group-1", List.of("worker-1")))
                .thenReturn(Map.of());
        WorkerServiceabilityDispatchPolicy policy = policy(
                scores, catalog, runtime
        );

        policy.dispatchProbes(List.of("group-1"), config(), 10_000L);
        policy.dispatchProbes(List.of("group-1"), config(), 10_000L);

        verify(scores).acquireHotCandidatesBefore(
                "group-1", 10_000L, 123_456_789L, 100
        );
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
                "group-1", 19_000L, 0L, 100
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
        when(catalog.getWorkerDescriptors(
                "group-1", List.of("worker-1")
        )).thenReturn(Map.of("worker-1", worker("worker-1", "adapter-1")));
        when(scores.holdObservedHotForServiceabilityProbes(
                "group-1", Map.of("worker-1", opaqueScore)
        )).thenReturn(Map.of("worker-1", transitioned(-123L)));
        when(runtime.offerProbeRequests(
                "adapter-1", List.of("worker-1")
        )).thenReturn(Map.of("worker-1", ProbeRequestOfferStatus.OFFERED));

        int offered = policy(
                scores,
                catalog,
                runtime,
                20_000L
        ).dispatchProbes(List.of("group-1"), config(), 10_000L);

        assertEquals(1, offered);
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
