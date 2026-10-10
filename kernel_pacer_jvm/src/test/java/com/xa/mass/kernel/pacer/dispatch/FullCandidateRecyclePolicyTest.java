package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.WorkerMatching;
import com.xa.mass.kernel.assignment.WorkerMatching.RefillOutcome;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FullCandidateRecyclePolicyTest {
    final WorkerScoreCore scores = mock(WorkerScoreCore.class);
    final WorkerMatching matching = mock(WorkerMatching.class);
    final AtomicLong nanos = new AtomicLong();
    final CandidateRecycleHints hints = new CandidateRecycleHints(nanos::get);
    final WorkerEligibilityRefillPolicy policy = new WorkerEligibilityRefillPolicy(scores, matching, null, 100, () -> 50_000);
    static WorkerScoreTransitionResult changed(long fence) {
        return new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.TRANSITIONED, fence);
    }
    @Test void fullFeedbackRecyclesOriginalFenceAfterTenSecondsEvenWithNoDeficit() {
        var tasks = WorkerEligibilityRefillPolicyTest.tasks(List.of("g"));
        when(matching.observeRefillDeficits(anyMap())).thenReturn(Map.of("g", 1));
        when(scores.observeDueHotScoreCandidates("g", null, 1)).thenReturn(Map.of("w", 10L));
        when(scores.candidateizeObservedHotScores("g", Map.of("w", 10L))).thenReturn(Map.of("w", changed(20L)));
        when(matching.refill(eq("g"), anyList(), anyMap())).thenReturn(new RefillOutcome(List.of(), List.of("w")));
        assertEquals(0, policy.refill(List.of("g"), tasks, hints));
        when(matching.observeRefillDeficits(anyMap())).thenReturn(Map.of());
        nanos.set(TimeUnit.SECONDS.toNanos(9)); policy.refill(List.of("g"), tasks, hints);
        verify(scores, never()).recycleObservedHotCandidates(anyString(), anyMap());
        when(scores.recycleObservedHotCandidates("g", Map.of("w", 20L))).thenReturn(Map.of("w", changed(30L)));
        nanos.set(TimeUnit.SECONDS.toNanos(10)); policy.refill(List.of("g"), tasks, hints);
        verify(scores).recycleObservedHotCandidates("g", Map.of("w", 20L));
        verify(matching, times(1)).refill(anyString(), anyList(), anyMap());
        assertEquals(0, hints.pending());
    }
    @Test void ordinaryObservationWinsAndFailureDoesNotReplayHints() {
        hints.offer("g", Map.of("w", 20L), List.of("w")); nanos.set(TimeUnit.SECONDS.toNanos(10));
        when(scores.observeHotCandidateScoresBefore("g", null, 20_000, 99)).thenReturn(Map.of("w", 40L));
        when(scores.recycleObservedHotCandidates("g", Map.of("w", 40L))).thenThrow(new IllegalStateException("Redis unavailable"));
        assertThrows(IllegalStateException.class, () -> policy.refill(List.of("g"), WorkerEligibilityRefillPolicyTest.tasks(List.of("g")), hints));
        assertEquals(0, hints.pending());
        policy.refill(List.of("g"), WorkerEligibilityRefillPolicyTest.tasks(List.of("g")), hints);
        verify(scores, times(1)).recycleObservedHotCandidates(anyString(), anyMap());
        verify(scores).observeHotCandidateScoresBefore("g", null, 20_000, 100);
    }
    @Test void bothPathsShareRoundBudgetAndOrdinaryHeadsKeepHalfOfEachGroup() {
        var groups = new ArrayList<String>(); var offered = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 100; i++) offered.put("hint" + i, 20L);
        for (int g = 0; g < 11; g++) { groups.add("g" + g); hints.offer("g" + g, offered, List.copyOf(offered.keySet())); }
        nanos.set(TimeUnit.SECONDS.toNanos(10));
        when(scores.observeHotCandidateScoresBefore(anyString(), isNull(), anyLong(), anyInt())).thenAnswer(call -> {
            int limit = call.getArgument(3); assertEquals(50, limit);
            var old = new LinkedHashMap<String, Long>(); for (int i = 0; i < limit; i++) old.put("old" + i, 30L); return old;
        });
        var visited = new ArrayList<String>(); var counts = new ArrayList<Integer>();
        when(scores.recycleObservedHotCandidates(anyString(), anyMap())).thenAnswer(call -> {
            visited.add(call.getArgument(0)); Map<String, Long> batch = call.getArgument(1); counts.add(batch.size()); return Map.of();
        });
        policy.refill(groups, WorkerEligibilityRefillPolicyTest.tasks(groups), hints);
        assertEquals(1000, counts.stream().mapToInt(Integer::intValue).sum());
        assertFalse(visited.contains("g10"));
        policy.refill(groups, WorkerEligibilityRefillPolicyTest.tasks(groups), hints);
        assertEquals("g10", visited.get(10));
        assertTrue(counts.stream().allMatch(count -> count <= 100));
    }
}
