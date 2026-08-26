package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.assignment.CandidateWorkerCache.CandidateWorkerEntry;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerCandidateAcquirerTest {

    @Test
    void directExplicitRuleUsesPointObservationWithoutBroadFallback() {
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerScoreCore score = mock(WorkerScoreCore.class);
        WorkerCandidateMatcher matcher = mock(WorkerCandidateMatcher.class);
        WorkerCandidateAcquirer acquirer = new WorkerCandidateAcquirer(
                cache,
                score,
                matcher,
                100,
                1_000L
        );
        WorkerCandidateRequest request = new WorkerCandidateRequest(
                1,
                1,
                Map.of("workerId", Map.of("$eq", "w1"))
        );
        when(matcher.filterCandidateWorkerIds(
                eq("group-1"),
                any(),
                any()
        )).thenReturn(Map.of("candidate", List.of("w1")));
        when(score.observeDueHotScores(
                "group-1",
                List.of("w1"),
                1_000L
        )).thenReturn(Map.of("w1", 101L));
        when(score.acquireObservedHotScoreLeases(
                "group-1",
                Map.of("w1", 101L),
                2_000L
        )).thenReturn(Map.of(
                "w1",
                new WorkerScoreTransitionResult(
                        WorkerScoreTransitionStatus.TRANSITIONED,
                        201L
                )
        ));
        CandidateWorkerEntry entry = new CandidateWorkerEntry(
                "w1",
                "group-1",
                "adapter-1",
                201L
        );
        when(matcher.matchExplicitWorkerCandidates(
                eq("group-1"),
                eq(Map.of("w1", 201L)),
                any(),
                any()
        )).thenReturn(Map.of("candidate", List.of(entry)));

        var result = acquirer.acquireWorkerCandidates(
                WorkerCandidateAcquisitionStrategy.DIRECT,
                "group-1",
                Map.of("candidate", request),
                2_000L
        );

        assertEquals(List.of(entry), result.get("candidate"));
        verify(score, never()).acquireHotAcquireCandidates(
                any(),
                any(),
                anyInt()
        );
    }

    @Test
    void precomputedCacheMissDoesNotFallbackToDirectOrHotScan() {
        CandidateWorkerCache cache = mock(CandidateWorkerCache.class);
        WorkerScoreCore score = mock(WorkerScoreCore.class);
        WorkerCandidateMatcher matcher = mock(WorkerCandidateMatcher.class);
        when(cache.consumeCandidateWorkers("task-1", 2))
                .thenReturn(List.of());
        WorkerCandidateAcquirer acquirer = new WorkerCandidateAcquirer(
                cache,
                score,
                matcher,
                100,
                null
        );

        var result = acquirer.acquireWorkerCandidates(
                WorkerCandidateAcquisitionStrategy.PRECOMPUTED,
                "group-1",
                Map.of("task-1", new WorkerCandidateRequest(
                        1,
                        2,
                        Map.of()
                )),
                2_000L
        );

        assertTrue(result.get("task-1").isEmpty());
        verify(score, never()).acquireHotAcquireCandidates(
                any(),
                any(),
                anyInt()
        );
        verify(score, never()).observeDueHotScores(
                any(),
                any(),
                any()
        );
        verify(score, never()).acquireObservedHotScoreLeases(
                any(),
                any(),
                anyLong()
        );
    }
}
