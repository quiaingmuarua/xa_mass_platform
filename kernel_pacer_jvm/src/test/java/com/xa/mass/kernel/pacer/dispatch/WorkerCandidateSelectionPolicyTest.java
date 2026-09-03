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
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkerCandidateSelectionPolicyTest {

    @Test
    void cachedCandidateCarriesExactHeldScoreWithoutEarlyRenewal() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        when(cache.consumeCandidateWorkers("task-1", 1)).thenReturn(
                List.of(new CandidateWorkerEntry("worker-1", 101L))
        );
        when(catalog.getWorkerDescriptors(
                "group-1", List.of("worker-1")
        )).thenReturn(Map.of("worker-1", workerDescriptor("worker-1")));

        List<HeldWorkerCandidate> result = policy(
                scores, cache, catalog
        ).consumeCachedCandidates("group-1", "task-1", 1);

        assertEquals(List.of(worker("worker-1", 101L)), result);
        verifyNoInteractions(scores);
    }

    @Test
    void missingDescriptorDropsConsumedCandidate() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        when(cache.consumeCandidateWorkers("task-1", 1)).thenReturn(
                List.of(new CandidateWorkerEntry("worker-1", 101L))
        );
        when(catalog.getWorkerDescriptors(
                "group-1", List.of("worker-1")
        )).thenReturn(Map.of());

        assertEquals(
                List.of(),
                policy(scores, cache, catalog).consumeCachedCandidates(
                        "group-1", "task-1", 1
                )
        );
        verifyNoInteractions(scores);
    }

    @Test
    void onDemandUsesExplicitTargetsThenAnyDueWorkersUniquely() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        when(scores.observeDueHotScores(
                "group-1", List.of("worker-2", "worker-1"), null
        )).thenReturn(Map.of("worker-1", 101L, "worker-2", 102L));
        when(scores.acquireObservedHotScoreLeases(
                "group-1", Map.of("worker-2", 102L), 5_000L
        )).thenReturn(Map.of("worker-2", transitioned(202L)));
        when(scores.observeDueHotScoreCandidates(
                "group-1", null, 2
        )).thenReturn(Map.of("worker-1", 101L));
        when(scores.acquireObservedHotScoreLeases(
                "group-1", Map.of("worker-1", 101L), 5_000L
        )).thenReturn(Map.of("worker-1", transitioned(201L)));
        when(catalog.getWorkerDescriptors(
                "group-1", List.of("worker-2", "worker-1")
        )).thenReturn(Map.of(
                "worker-1", workerDescriptor("worker-1"),
                "worker-2", workerDescriptor("worker-2")
        ));
        LinkedHashMap<String, List<String>> targets = new LinkedHashMap<>();
        targets.put("message-explicit", List.of("worker-2", "worker-1"));
        targets.put("message-any", List.of());

        Map<String, HeldWorkerCandidate> result = policy(
                scores, cache, catalog
        ).acquireOnDemandCandidates(
                "group-1", targets, Set.of(), 5_000L
        );

        assertEquals(
                Map.of(
                        "message-explicit", worker("worker-2", 202L),
                        "message-any", worker("worker-1", 201L)
                ),
                result
        );
        verifyNoInteractions(cache);
    }

    @Test
    void anyTargetsScanPastWorkersAlreadyUsedInTheRound() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        LinkedHashMap<String, Long> observed = new LinkedHashMap<>();
        observed.put("worker-used", 101L);
        observed.put("worker-free", 102L);
        when(scores.observeDueHotScoreCandidates(
                "group-1", null, 2
        )).thenReturn(observed);
        when(scores.acquireObservedHotScoreLeases(
                "group-1", Map.of("worker-free", 102L), 5_000L
        )).thenReturn(Map.of("worker-free", transitioned(202L)));
        when(catalog.getWorkerDescriptors(
                "group-1", List.of("worker-free")
        )).thenReturn(Map.of(
                "worker-free", workerDescriptor("worker-free")
        ));

        assertEquals(
                Map.of("message-any", worker("worker-free", 202L)),
                policy(scores, cache, catalog).acquireOnDemandCandidates(
                        "group-1",
                        Map.of("message-any", List.of()),
                        Set.of("worker-used"),
                        5_000L
                )
        );
        verifyNoInteractions(cache);
    }

    @Test
    void excludedAndStaleExplicitTargetsDoNotBecomeCandidates() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        when(scores.observeDueHotScores(
                "group-1", List.of("worker-2"), null
        )).thenReturn(Map.of("worker-2", 102L));
        when(scores.acquireObservedHotScoreLeases(
                "group-1", Map.of("worker-2", 102L), 5_000L
        )).thenReturn(Map.of(
                "worker-2",
                new WorkerScoreTransitionResult(
                        WorkerScoreTransitionStatus.STALE, 102L
                )
        ));

        assertEquals(
                Map.of(),
                policy(scores, cache, catalog).acquireOnDemandCandidates(
                        "group-1",
                        Map.of("message-1", List.of(
                                "worker-1", "worker-2"
                        )),
                        Set.of("worker-1"),
                        5_000L
                )
        );
        verify(catalog, never()).getWorkerDescriptors(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList()
        );
    }

    @Test
    void targetValidationRejectsDuplicateWorkersAndOversizedRounds() {
        WorkerCandidateSelectionPolicy policy = policy(
                mock(WorkerScoreCore.class),
                mock(CandidateWorkerCache.class),
                mock(WorkerResourceCatalog.class)
        );

        assertThrows(IllegalArgumentException.class, () ->
                policy.acquireOnDemandCandidates(
                        "group-1",
                        Map.of("message-1", List.of(
                                "worker-1", "worker-1"
                        )),
                        Set.of(),
                        5_000L
                ));
        LinkedHashMap<String, List<String>> tooMany = new LinkedHashMap<>();
        for (int index = 0; index < 101; index++) {
            tooMany.put("message-" + index, List.of());
        }
        assertThrows(IllegalArgumentException.class, () ->
                policy.acquireOnDemandCandidates(
                        "group-1", tooMany, Set.of(), 5_000L
                ));
    }

    private static WorkerCandidateSelectionPolicy policy(
            WorkerScoreCore scores,
            CandidateWorkerCache cache,
            WorkerResourceCatalog catalog
    ) {
        return new WorkerCandidateSelectionPolicy(
                scores, cache, catalog, null
        );
    }

    private static WorkerScoreTransitionResult transitioned(long score) {
        return new WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED, score
        );
    }

    private static WorkerDescriptor workerDescriptor(String workerId) {
        return new WorkerDescriptor(workerId, "group-1", "adapter-1");
    }

    private static HeldWorkerCandidate worker(
            String workerId,
            long score
    ) {
        return new HeldWorkerCandidate(
                workerId, "group-1", "adapter-1", score
        );
    }
}
