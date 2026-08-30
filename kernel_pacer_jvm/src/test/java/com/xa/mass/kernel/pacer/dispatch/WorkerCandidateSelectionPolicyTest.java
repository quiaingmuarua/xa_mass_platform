package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class WorkerCandidateSelectionPolicyTest {

    @Test
    void leasesOnlyPreselectedMatchesAndRematchesCanonicalDescriptor() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        when(scores.acquireHotAcquireCandidates("group-1", null, 100))
                .thenReturn(linkedScores("east", 101L, "west", 102L));
        when(catalog.getWorkerDescriptors(
                "group-1",
                List.of("east", "west")
        )).thenReturn(Map.of(
                "east", worker("east", "east"),
                "west", worker("west", "west")
        ));
        when(scores.acquireObservedHotScoreLeases(
                "group-1",
                Map.of("east", 101L),
                5_000L
        )).thenReturn(Map.of("east", transitioned(201L)));
        when(catalog.getWorkerDescriptors(
                "group-1",
                List.of("east")
        )).thenReturn(Map.of("east", worker("east", "east")));

        Map<String, List<AcquiredWorkerCandidate>> acquired =
                policy(scores, cache, catalog).acquireHotPoolCandidates(
                        "group-1",
                        Map.of("candidate", new WorkerCandidateRequest(
                                0,
                                1,
                                Map.of(
                                        "worker.region",
                                        Map.of("$eq", "east")
                                )
                        )),
                        5_000L
                );

        assertEquals(List.of("east"), acquired.get("candidate").stream()
                .map(AcquiredWorkerCandidate::workerId).toList());
        assertEquals(
                201L,
                acquired.get("candidate").getFirst().workerLeaseScore()
        );
        verify(scores).acquireObservedHotScoreLeases(
                "group-1",
                Map.of("east", 101L),
                5_000L
        );
    }

    @Test
    void sharedPoolSelectionAppliesPriorityCountAndWorkerUniqueness() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        when(scores.acquireHotAcquireCandidates("group-1", null, 100))
                .thenReturn(linkedScores("east", 101L, "west", 102L));
        when(catalog.getWorkerDescriptors(eq("group-1"), anyList()))
                .thenAnswer(invocation -> descriptors(
                        invocation.<List<String>>getArgument(1)
                ));
        when(scores.acquireObservedHotScoreLeases(
                "group-1",
                linkedScores("east", 101L, "west", 102L),
                5_000L
        )).thenReturn(Map.of(
                "east", transitioned(201L),
                "west", transitioned(202L)
        ));
        LinkedHashMap<String, WorkerCandidateRequest> requests =
                new LinkedHashMap<>();
        requests.put("fallback", new WorkerCandidateRequest(
                1,
                2,
                Map.of()
        ));
        requests.put("preferred", new WorkerCandidateRequest(
                0,
                1,
                Map.of("workerId", Map.of("$eq", "east"))
        ));

        Map<String, List<AcquiredWorkerCandidate>> acquired =
                policy(scores, cache, catalog).acquireHotPoolCandidates(
                        "group-1",
                        requests,
                        5_000L
                );

        assertEquals(List.of("east"), acquired.get("preferred").stream()
                .map(AcquiredWorkerCandidate::workerId).toList());
        assertEquals(List.of("west"), acquired.get("fallback").stream()
                .map(AcquiredWorkerCandidate::workerId).toList());
        verify(scores, times(1)).acquireHotAcquireCandidates(
                "group-1",
                null,
                100
        );
    }

    @Test
    void postLeaseRematchDoesNotMoveWorkerToAnotherCandidate() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        when(scores.acquireHotAcquireCandidates("group-1", null, 100))
                .thenReturn(linkedScores("east", 101L, "west", 102L));
        when(scores.acquireObservedHotScoreLeases(
                "group-1",
                linkedScores("east", 101L, "west", 102L),
                5_000L
        )).thenReturn(Map.of(
                "east", transitioned(201L),
                "west", transitioned(202L)
        ));
        when(catalog.getWorkerDescriptors(
                "group-1",
                List.of("east", "west")
        ))
                .thenReturn(
                        Map.of(
                                "east", worker("east", "east"),
                                "west", worker("west", "west")
                        ),
                        Map.of(
                                "east", worker("east", "west"),
                                "west", worker("west", "west")
                        )
                );
        LinkedHashMap<String, WorkerCandidateRequest> requests =
                new LinkedHashMap<>();
        requests.put("preferred", new WorkerCandidateRequest(
                0,
                1,
                Map.of("worker.region", Map.of("$eq", "east"))
        ));
        requests.put("fallback", new WorkerCandidateRequest(
                1,
                1,
                Map.of()
        ));

        Map<String, List<AcquiredWorkerCandidate>> acquired =
                policy(scores, cache, catalog).acquireHotPoolCandidates(
                        "group-1",
                        requests,
                        5_000L
                );

        assertEquals(List.of(), acquired.get("preferred"));
        assertEquals(List.of("west"), acquired.get("fallback").stream()
                .map(AcquiredWorkerCandidate::workerId).toList());
        verify(scores).acquireObservedHotScoreLeases(
                "group-1",
                linkedScores("east", 101L, "west", 102L),
                5_000L
        );
    }

    @Test
    void precomputedRenewsOnlyCachedWorkersWithoutHotFallback() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        when(cache.consumeCandidateWorkers("candidate", 1)).thenReturn(
                List.of(new CandidateWorkerEntry(
                        "cached", "group-1", 101L
                ))
        );
        when(scores.renewActiveHotScoreLeases(
                "group-1",
                Map.of("cached", 101L),
                5_000L
        )).thenReturn(Map.of("cached", transitioned(201L)));
        when(catalog.getWorkerDescriptors("group-1", List.of("cached")))
                .thenReturn(Map.of("cached", worker("cached", "east")));

        Map<String, List<AcquiredWorkerCandidate>> acquired =
                policy(scores, cache, catalog).acquireWorkerCandidates(
                        WorkerCandidateAcquisitionStrategy.PRECOMPUTED,
                        "group-1",
                        Map.of("candidate", new WorkerCandidateRequest(
                                0,
                                1,
                                Map.of(
                                        "worker.region",
                                        Map.of("$eq", "east")
                                )
                        )),
                        5_000L
                );

        assertEquals(List.of("cached"), acquired.get("candidate").stream()
                .map(AcquiredWorkerCandidate::workerId).toList());
        verify(scores, never()).acquireHotAcquireCandidates(
                eq("group-1"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()
        );
    }

    @Test
    void invalidRuleDoesNotReadAnyAcquisitionSourceOrAttemptLease() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        WorkerCandidateSelectionPolicy policy = policy(
                scores,
                cache,
                catalog
        );
        Map<String, WorkerCandidateRequest> requests = Map.of(
                "invalid",
                new WorkerCandidateRequest(
                        0,
                        1,
                        Map.of(
                                "worker.region",
                                Map.of("$unknown", "east")
                        )
                )
        );

        assertEquals(List.of(), policy.acquireHotPoolCandidates(
                "group-1",
                requests,
                5_000L
        ).get("invalid"));
        for (WorkerCandidateAcquisitionStrategy strategy
                : WorkerCandidateAcquisitionStrategy.values()) {
            assertEquals(List.of(), policy.acquireWorkerCandidates(
                    strategy,
                    "group-1",
                    requests,
                    5_000L
            ).get("invalid"));
        }
        verifyNoInteractions(scores, cache, catalog);
    }

    @Test
    void directSelectionLeasesAtMostOneHundredUniqueWorkersPerRound() {
        WorkerScoreCore scores = mock(WorkerScoreCore.class);
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        LinkedHashMap<String, Long> broad = new LinkedHashMap<>();
        IntStream.range(0, 100).forEach(index ->
                broad.put("broad-" + index, 100L + index));
        when(scores.acquireHotAcquireCandidates("group-1", null, 100))
                .thenReturn(java.util.Collections.unmodifiableMap(broad));
        when(scores.observeDueHotScores(
                "group-1", List.of("explicit"), null
        )).thenReturn(Map.of("explicit", 301L));
        when(scores.acquireObservedHotScoreLeases(
                eq("group-1"),
                org.mockito.ArgumentMatchers.anyMap(),
                eq(5_000L)
        )).thenAnswer(invocation -> {
            Map<String, Long> input = invocation.getArgument(1);
            LinkedHashMap<String, WorkerScoreTransitionResult> result =
                    new LinkedHashMap<>();
            input.forEach((id, score) -> result.put(id, transitioned(score)));
            return result;
        });
        when(catalog.getWorkerDescriptors(eq("group-1"), anyList()))
                .thenAnswer(invocation -> descriptors(
                        invocation.<List<String>>getArgument(1)
                ));
        LinkedHashMap<String, WorkerCandidateRequest> requests =
                new LinkedHashMap<>();
        requests.put("broad", new WorkerCandidateRequest(0, 100, Map.of()));
        requests.put("explicit", new WorkerCandidateRequest(
                1,
                1,
                Map.of("workerId", Map.of("$eq", "explicit"))
        ));

        Map<String, List<AcquiredWorkerCandidate>> acquired =
                policy(scores, cache, catalog).acquireWorkerCandidates(
                        WorkerCandidateAcquisitionStrategy.DIRECT,
                        "group-1",
                        requests,
                        5_000L
                );

        assertEquals(100, acquired.get("broad").size());
        assertEquals(List.of(), acquired.get("explicit"));
        verify(scores).acquireObservedHotScoreLeases(
                eq("group-1"),
                argThat(workers -> workers.size() == 100
                        && !workers.containsKey("explicit")),
                eq(5_000L)
        );
    }

    private static WorkerCandidateSelectionPolicy policy(
            WorkerScoreCore scores,
            CandidateWorkerCache cache,
            WorkerResourceCatalog catalog
    ) {
        return new WorkerCandidateSelectionPolicy(
                scores,
                cache,
                new WorkerCandidateMatcher(catalog),
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

    private static Map<String, WorkerDescriptor> descriptors(
            List<String> workerIds
    ) {
        LinkedHashMap<String, WorkerDescriptor> result = new LinkedHashMap<>();
        workerIds.forEach(workerId -> result.put(
                workerId,
                worker(workerId, "east")
        ));
        return result;
    }

    private static WorkerDescriptor worker(String workerId, String region) {
        return new WorkerDescriptor(
                workerId,
                "group-1",
                "adapter-1",
                Map.of("region", region),
                Map.of()
        );
    }
}
