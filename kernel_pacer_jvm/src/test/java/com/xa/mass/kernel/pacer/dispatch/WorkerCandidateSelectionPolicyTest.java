package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex;
import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import com.xa.mass.kernel.assignment.CandidateWorkerCache.CandidateWorkerEntry;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerDescriptor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkerCandidateSelectionPolicyTest {

    @Test void indexedBudgetIsSharedByDistinctQueriesInItemOrderWithoutRefill() {
        var scores = mock(WorkerScoreCore.class);
        var cache = mock(CandidateWorkerCache.class);
        var catalog = mock(WorkerResourceCatalog.class);
        var index = mock(WorkerCandidateIndex.class);
        var policy = new WorkerCandidateSelectionPolicy(scores, cache, catalog, null, index);
        var targets = new LinkedHashMap<String, TaskItemWorkerSelector>();
        var us = TaskItemWorkerSelector.parse(Map.of("worker.country", List.of("US")));
        var cn = TaskItemWorkerSelector.parse(Map.of("worker.country", List.of("CN")));
        var gb = TaskItemWorkerSelector.parse(Map.of("worker.country", List.of("GB")));
        for (int i = 0; i < 100; i++) {
            String id = "item-" + i;
            targets.put(id, TaskItemWorkerSelector.parse((i % 3 == 0 ? us : i % 3 == 1 ? cn : gb).expression()));
        }
        assertEquals(Map.of(), policy.acquireOnDemandCandidates("group-1", targets, Set.of(), 5000));
        var order = org.mockito.Mockito.inOrder(index);
        order.verify(index).takeWorkerIds("group-1", us, 34);
        order.verify(index).takeWorkerIds("group-1", cn, 33);
        order.verify(index).takeWorkerIds("group-1", gb, 33);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(scores, cache, catalog);
    }

    @Test void oneIndexedItemDoesNotTouchAnEntireCountryBucketJustBecauseBudgetAllowsIt() {
        var scores = mock(WorkerScoreCore.class);
        var index = mock(WorkerCandidateIndex.class);
        var query = TaskItemWorkerSelector.parse(Map.of("worker.country", List.of("CN")));
        var policy = new WorkerCandidateSelectionPolicy(scores, mock(CandidateWorkerCache.class),
                mock(WorkerResourceCatalog.class), null, index);
        when(index.takeWorkerIds("group-1", query, 1)).thenReturn(List.of("busy"), List.of("offline"));
        for (int round = 0; round < 2; round++) {
            assertEquals(Map.of(), policy.acquireOnDemandCandidates("group-1", Map.of("m", query), Set.of(), 5000));
        }
        verify(index, org.mockito.Mockito.times(2)).takeWorkerIds("group-1", query, 1);
        verify(index, never()).takeWorkerIds("group-1", query, 100);
        verify(scores).observeDueHotScores("group-1", List.of("busy"), null);
        verify(scores).observeDueHotScores("group-1", List.of("offline"), null);
    }

    @Test void explicitThenIndexThenAnyWithPostHoldMembershipFenceAndRoundDeduplication() {
        var scores = mock(WorkerScoreCore.class);
        var cache = mock(CandidateWorkerCache.class);
        var catalog = mock(WorkerResourceCatalog.class);
        var index = mock(WorkerCandidateIndex.class);
        var query = TaskItemWorkerSelector.parse(Map.of("worker.country", List.of("CN")));
        var targets = new LinkedHashMap<String, TaskItemWorkerSelector>();
        targets.put("any", TaskItemWorkerSelector.parse(Map.of()));
        targets.put("country", query);
        targets.put("country-2", query);
        targets.put("country-3", query);
        targets.put("country-4", query);
        targets.put("explicit", TaskItemWorkerSelector.parse(Map.of("workerId", List.of("id"))));
        when(scores.observeDueHotScores("group-1", List.of("id"), null)).thenReturn(Map.of("id", 1L));
        when(scores.acquireObservedHotScoreLeases("group-1", Map.of("id", 1L), 5000L))
                .thenReturn(Map.of("id", transitioned(11L)));
        when(index.takeWorkerIds("group-1", query, 4)).thenReturn(List.of("already-used", "id", "cold", "changed"));
        when(scores.observeDueHotScores("group-1", List.of("cold", "changed"), null)).thenReturn(Map.of("changed", 2L));
        when(scores.acquireObservedHotScoreLeases("group-1", Map.of("changed", 2L), 5000L))
                .thenReturn(Map.of("changed", transitioned(12L)));
        // Country changed between query and hold; the new hold cleared dirty but cannot pass membership.
        when(index.retainWorkerIds("group-1", query, List.of("changed"))).thenReturn(Set.of());
        when(scores.observeDueHotScoreCandidates("group-1", null, 4)).thenReturn(Map.of("changed", 12L, "free", 3L));
        when(scores.acquireObservedHotScoreLeases("group-1", Map.of("free", 3L), 5000L))
                .thenReturn(Map.of("free", transitioned(13L)));
        when(catalog.getWorkerDescriptors(List.of("id", "free"))).thenReturn(Map.of("id", workerDescriptor("id"), "free", workerDescriptor("free")));
        var policy = new WorkerCandidateSelectionPolicy(scores, cache, catalog, null, index);
        assertEquals(Map.of("explicit", worker("id", 11), "any", worker("free", 13)),
                policy.acquireOnDemandCandidates("group-1", targets, Set.of("already-used"), 5000));
        var order = org.mockito.Mockito.inOrder(scores, index);
        order.verify(scores).observeDueHotScores("group-1", List.of("id"), null);
        order.verify(scores).acquireObservedHotScoreLeases("group-1", Map.of("id", 1L), 5000L);
        order.verify(index).takeWorkerIds("group-1", query, 4);
        order.verify(scores).observeDueHotScores("group-1", List.of("cold", "changed"), null);
        order.verify(scores).acquireObservedHotScoreLeases("group-1", Map.of("changed", 2L), 5000L);
        order.verify(index).retainWorkerIds("group-1", query, List.of("changed"));
        order.verify(scores).observeDueHotScoreCandidates("group-1", null, 4);
        verifyNoInteractions(cache);
    }

    @Test void indexFailureNeverFallsBackToAny() {
        var scores = mock(WorkerScoreCore.class);
        var index = mock(WorkerCandidateIndex.class);
        var query = TaskItemWorkerSelector.parse(Map.of("worker.country", List.of("CN")));
        when(index.takeWorkerIds("group-1", query, 1)).thenThrow(new IllegalStateException("unavailable"));
        var policy = new WorkerCandidateSelectionPolicy(scores, mock(CandidateWorkerCache.class), mock(WorkerResourceCatalog.class), null, index);
        assertThrows(IllegalStateException.class, () -> policy.acquireOnDemandCandidates("group-1", Map.of("m", query), Set.of(), 5000));
        verifyNoInteractions(scores);
    }

    @Test
    void opaqueNonCountrySelectorIsPassedUnchangedThroughAcquisitionAndPostHoldRecheck() {
        var scores = mock(WorkerScoreCore.class);
        var cache = mock(CandidateWorkerCache.class);
        var catalog = mock(WorkerResourceCatalog.class);
        var index = mock(WorkerCandidateIndex.class);
        var selector = TaskItemWorkerSelector.parse(Map.of("worker.test.region", List.of("east", "west")));
        when(index.takeWorkerIds("group-1", selector, 1)).thenReturn(List.of("worker"));
        when(scores.observeDueHotScores("group-1", List.of("worker"), null)).thenReturn(Map.of("worker", 7L));
        when(scores.acquireObservedHotScoreLeases("group-1", Map.of("worker", 7L), 5000L))
                .thenReturn(Map.of("worker", transitioned(17L)));
        when(index.retainWorkerIds("group-1", selector, List.of("worker"))).thenReturn(Set.of("worker"));
        when(catalog.getWorkerDescriptors(List.of("worker"))).thenReturn(Map.of("worker", workerDescriptor("worker")));
        var policy = new WorkerCandidateSelectionPolicy(scores, cache, catalog, null, index);
        assertEquals(Map.of("item", worker("worker", 17L)),
                policy.acquireOnDemandCandidates("group-1", Map.of("item", selector), Set.of(), 5000L));
        var order = org.mockito.Mockito.inOrder(index, scores);
        order.verify(index).takeWorkerIds(org.mockito.ArgumentMatchers.eq("group-1"),
                org.mockito.ArgumentMatchers.same(selector), org.mockito.ArgumentMatchers.eq(1));
        order.verify(scores).observeDueHotScores("group-1", List.of("worker"), null);
        order.verify(scores).acquireObservedHotScoreLeases("group-1", Map.of("worker", 7L), 5000L);
        order.verify(index).retainWorkerIds(org.mockito.ArgumentMatchers.eq("group-1"),
                org.mockito.ArgumentMatchers.same(selector), org.mockito.ArgumentMatchers.eq(List.of("worker")));
        verifyNoInteractions(cache);
    }

    @Test
    void anyAndExplicitIdentitiesNeverConsultMatchingIndex() {
        var index = mock(WorkerCandidateIndex.class);
        var policy = new WorkerCandidateSelectionPolicy(mock(WorkerScoreCore.class),
                mock(CandidateWorkerCache.class), mock(WorkerResourceCatalog.class), null, index);
        var selectors = new LinkedHashMap<String, TaskItemWorkerSelector>();
        selectors.put("any", TaskItemWorkerSelector.parse(Map.of()));
        selectors.put("explicit", TaskItemWorkerSelector.parse(Map.of("workerId", List.of("worker"))));
        assertEquals(Map.of(), policy.acquireOnDemandCandidates("group-1", selectors, Set.of(), 5000L));
        verifyNoInteractions(index);
    }

    @Test
    void cachedCandidateCarriesExactHeldScoreWithoutEarlyRenewal() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        when(cache.consumeCandidateWorkers("task-1", 1)).thenReturn(
                List.of(new CandidateWorkerEntry("worker-1", 101L))
        );
        when(catalog.getWorkerDescriptors(List.of("worker-1"))).thenReturn(Map.of("worker-1", workerDescriptor("worker-1")));

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
        when(catalog.getWorkerDescriptors(List.of("worker-1"))).thenReturn(Map.of());

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
        when(catalog.getWorkerDescriptors(List.of("worker-2", "worker-1"))).thenReturn(Map.of(
                "worker-1", workerDescriptor("worker-1"),
                "worker-2", workerDescriptor("worker-2")
        ));
        LinkedHashMap<String, TaskItemWorkerSelector> targets = new LinkedHashMap<>();
        targets.put("message-explicit", TaskItemWorkerSelector.parse(Map.of("workerId", List.of("worker-2", "worker-1"))));
        targets.put("message-any", TaskItemWorkerSelector.parse(Map.of()));

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
        when(catalog.getWorkerDescriptors(List.of("worker-free"))).thenReturn(Map.of(
                "worker-free", workerDescriptor("worker-free")
        ));

        assertEquals(
                Map.of("message-any", worker("worker-free", 202L)),
                policy(scores, cache, catalog).acquireOnDemandCandidates(
                        "group-1",
                        Map.of("message-any", TaskItemWorkerSelector.parse(Map.of())),
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
                        Map.of("message-1", TaskItemWorkerSelector.parse(Map.of("workerId", List.of("worker-1", "worker-2")))),
                        Set.of("worker-1"),
                        5_000L
                )
        );
        verify(catalog, never()).getWorkerDescriptors(org.mockito.ArgumentMatchers.anyList());
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
                        Map.of("message-1", TaskItemWorkerSelector.parse(Map.of("workerId", List.of("worker-1", "worker-1")))),
                        Set.of(),
                        5_000L
                ));
        LinkedHashMap<String, TaskItemWorkerSelector> tooMany = new LinkedHashMap<>();
        for (int index = 0; index < 101; index++) {
            tooMany.put("message-" + index, TaskItemWorkerSelector.parse(Map.of()));
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
                scores, cache, catalog, null,
            mock(WorkerCandidateIndex.class)
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
