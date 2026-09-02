package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.CandidateWorkerCache.CandidateWorkerEntry;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerCandidateSelectionPolicyTest {

    @Test
    void dueObservationAndExactHoldRemainSeparateKernelOperations() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        LinkedHashMap<String, Long> observed = linkedScores(
                "worker-1", 101L,
                "worker-2", 102L
        );
        when(scores.observeDueHotScoreCandidates(
                "group-1",
                null,
                100
        )).thenReturn(observed);
        when(scores.acquireObservedHotScoreLeases(
                "group-1",
                observed,
                5_000L
        )).thenReturn(Map.of(
                "worker-1", transitioned(201L),
                "worker-2", new WorkerScoreTransitionResult(
                        WorkerScoreTransitionStatus.STALE,
                        102L
                )
        ));

        WorkerCandidateSelectionPolicy policy = policy(scores, cache, catalog);

        assertEquals(observed, policy.observeDueCandidates("group-1"));
        assertEquals(
                Map.of("worker-1", 201L),
                policy.holdObservedCandidates(
                        "group-1",
                        observed,
                        5_000L
                )
        );
        verifyNoInteractions(cache, catalog);
    }

    @Test
    void heldEvidenceStillUsesKernelPriorityAndUniqueness() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        LinkedHashMap<String, Long> active = linkedScores(
                "worker-1", 201L,
                "worker-2", 202L
        );
        when(scores.observeActiveHotScoreLeases(
                "group-1",
                List.of("worker-1", "worker-2"),
                5_000L
        )).thenReturn(active);
        when(catalog.getWorkerDescriptors(
                "group-1",
                List.of("worker-1", "worker-2")
        )).thenReturn(Map.of(
                "worker-1", worker("worker-1"),
                "worker-2", worker("worker-2")
        ));
        LinkedHashMap<String, WorkerCandidateRequest> requests =
                new LinkedHashMap<>();
        requests.put("lower", new WorkerCandidateRequest(10, 2));
        requests.put("higher", new WorkerCandidateRequest(1, 1));

        Map<String, List<AcquiredWorkerCandidate>> result = policy(
                scores,
                cache,
                catalog
        ).selectHeldCandidates(
                "group-1",
                requests,
                Map.of(
                        "lower", List.of("worker-1", "worker-2"),
                        "higher", List.of("worker-1")
                ),
                Map.of("lower", 5_000L, "higher", 5_000L),
                100
        );

        assertEquals(
                List.of("worker-1"),
                result.get("higher").stream()
                        .map(AcquiredWorkerCandidate::workerId)
                        .toList()
        );
        assertEquals(
                List.of("worker-2"),
                result.get("lower").stream()
                        .map(AcquiredWorkerCandidate::workerId)
                        .toList()
        );
        verifyNoInteractions(cache);
    }

    @Test
    void staleHoldOrMissingDescriptorCannotTurnEvidenceIntoAWorker() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        when(scores.observeActiveHotScoreLeases(
                "group-1",
                List.of("worker-1", "worker-2"),
                5_000L
        )).thenReturn(Map.of("worker-1", 201L));
        when(catalog.getWorkerDescriptors(
                "group-1",
                List.of("worker-1")
        )).thenReturn(Map.of());

        Map<String, List<AcquiredWorkerCandidate>> result = policy(
                scores,
                cache,
                catalog
        ).selectHeldCandidates(
                "group-1",
                Map.of("item", new WorkerCandidateRequest(0, 2)),
                Map.of("item", List.of("worker-1", "worker-2")),
                Map.of("item", 5_000L),
                100
        );

        assertEquals(List.of(), result.get("item"));
    }

    @Test
    void cachedRenewalUsesOnlyKernelCandidateCache() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        when(cache.consumeCandidateWorkers("task-1", 1)).thenReturn(
                List.of(new CandidateWorkerEntry(
                        "worker-1",
                        "group-1",
                        101L
                ))
        );
        when(scores.renewActiveHotScoreLeases(
                "group-1",
                Map.of("worker-1", 101L),
                5_000L
        )).thenReturn(Map.of("worker-1", transitioned(201L)));
        when(catalog.getWorkerDescriptors(
                "group-1",
                List.of("worker-1")
        )).thenReturn(Map.of("worker-1", worker("worker-1")));

        Map<String, List<AcquiredWorkerCandidate>> result = policy(
                scores,
                cache,
                catalog
        ).renewCachedCandidates(
                "group-1",
                Map.of("task-1", new WorkerCandidateRequest(0, 1)),
                5_000L
        );

        assertEquals("worker-1", result.get("task-1").getFirst().workerId());
        verify(scores, never()).observeDueHotScoreCandidates(
                "group-1",
                null,
                100
        );
        verify(scores, never()).acquireObservedHotScoreLeases(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyLong()
        );
    }

    @Test
    void candidateEvidenceCannotExceedKernelRoundBound() {
        assertThrows(IllegalArgumentException.class, () -> policy(
                mock(WorkerScoreCore.class),
                mock(CandidateWorkerCache.class),
                mock(WorkerResourceCatalog.class)
        ).selectHeldCandidates(
                "group-1",
                Map.of("item", new WorkerCandidateRequest(0, 1)),
                Map.of("item", List.of("worker-1")),
                Map.of("item", 5_000L),
                101
        ));
    }

    private static WorkerCandidateSelectionPolicy policy(
            WorkerScoreCore scores,
            CandidateWorkerCache cache,
            WorkerResourceCatalog catalog
    ) {
        return new WorkerCandidateSelectionPolicy(
                scores,
                cache,
                catalog,
                100,
                null
        );
    }

    private static LinkedHashMap<String, Long> linkedScores(
            String firstId,
            long firstScore,
            String secondId,
            long secondScore
    ) {
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        result.put(firstId, firstScore);
        result.put(secondId, secondScore);
        return result;
    }

    private static WorkerScoreTransitionResult transitioned(long score) {
        return new WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED,
                score
        );
    }

    private static WorkerDescriptor worker(String workerId) {
        return new WorkerDescriptor(workerId, "group-1", "adapter-1");
    }
}
