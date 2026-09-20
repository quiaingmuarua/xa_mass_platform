package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.WorkerMatching;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkerEligibilityRefillPolicyTest {
    final WorkerScoreCore scores=mock(WorkerScoreCore.class);
    final WorkerMatching index=mock(WorkerMatching.class);
    final List<com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor> tasks=tasks(List.of("g"));
    static List<com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor> tasks(List<String> groups) {
        return groups.stream().map(group -> new com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor(
                "task-"+group, "test-project",group,com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition.PARK_WHEN_IDLE,
                Map.of("priority","0","maxRetryTimes","1"),
                List.of(new com.xa.mass.kernel.assignment.RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 100)), null, java.util.Map.of())).toList();
    }
    final List<com.xa.mass.kernel.assignment.RefillTarget> targets=List.of(new com.xa.mass.kernel.assignment.RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 100));
    final java.util.concurrent.atomic.AtomicLong clock=new java.util.concurrent.atomic.AtomicLong(1000);
    final WorkerEligibilityRefillPolicy policy=new WorkerEligibilityRefillPolicy(scores,index,500L,clock::get);


    static WorkerScoreTransitionResult changed(long score) {
        return new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.TRANSITIONED,score);
    }
    void oneIssuedWorker() {
        when(index.groupsNeedingRefill(anyMap())).thenReturn(Set.of("g"));
        when(scores.observeDueHotScoreCandidates("g",500L,100)).thenReturn(Map.of("w",10L));
        when(scores.candidateizeObservedHotScores("g", Map.of("w",10L))).thenReturn(Map.of("w",changed(20L)));
    }

    @Test void pacerCandidateizesBeforeMatchingAndSuppliesOnlyTheNewOpaqueFence() {
        oneIssuedWorker();
        when(index.refill(eq("g"), anyList(), anyMap())).thenAnswer(call->{
            Map<String, Long> offered=call.getArgument(2);
            assertEquals(Map.ofEntries(Map.entry("w", (long) (20L))),offered);
            verify(scores).candidateizeObservedHotScores("g", Map.of("w",10L));
            assertThrows(UnsupportedOperationException.class,()->offered.clear());
            return 1;
        });
        assertEquals(1,policy.refill(List.of("g"),tasks));
        var order=inOrder(scores,index);
        order.verify(index).groupsNeedingRefill(Map.of("g",targets));
        order.verify(scores).observeDueHotScoreCandidates("g",500L,100);
        order.verify(scores).candidateizeObservedHotScores("g", Map.of("w",10L));
        order.verify(index).refill("g",targets,Map.ofEntries(Map.entry("w", (long) (20L))));
        verify(scores,atLeastOnce()).observeHotCandidateScoresBefore(eq("g"),eq(500L),anyLong(),eq(100));
        verifyNoMoreInteractions(scores);
        verify(index,times(1)).groupsNeedingRefill(Map.of("g",targets));
    }

    @Test void partialCandidateizationOffersOnlySuccessfullyCandidateizedSuppliedIdentities() {
        oneIssuedWorker();
        var observed=Map.of("w",10L,"lost",11L,"same",12L);
        when(scores.observeDueHotScoreCandidates("g",500L,100)).thenReturn(observed);
        when(scores.candidateizeObservedHotScores("g", observed)).thenReturn(Map.of(
                "w",changed(20L),"lost",new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.STALE,30L),
                "same",changed(12L),"outside",changed(40L)));
        policy.refill(List.of("g"),tasks);
        verify(index).refill("g",targets,Map.ofEntries(Map.entry("w", (long) (20L))));
        verify(scores,times(1)).observeDueHotScoreCandidates("g",500L,100);
        verify(scores,times(1)).candidateizeObservedHotScores("g", observed);
        verify(scores,atLeastOnce()).observeHotCandidateScoresBefore(eq("g"),eq(500L),anyLong(),eq(100));
        verifyNoMoreInteractions(scores);
    }

    @Test void satisfiedInventoryAndForeignGroupDemandNeverAcquireWorkers() {
        when(index.groupsNeedingRefill(anyMap())).thenReturn(Set.of("outside"));
        assertEquals(0,policy.refill(List.of("g"),tasks));
        when(index.groupsNeedingRefill(anyMap())).thenReturn(Set.of());
        assertEquals(0,policy.refill(List.of("g"),tasks));
        assertEquals(0,policy.refill(List.of(),List.of()));
        verify(scores,times(2)).observeHotCandidateScoresBefore(eq("g"),eq(500L),anyLong(),eq(100));
        verifyNoMoreInteractions(scores);
        verify(index,never()).refill(anyString(), anyList(), anyMap());
    }

    @Test void emptyRoundsRotateAcrossMoreGroupsThanTheGlobalBudget() {
        var groups=IntStream.range(0,15).mapToObj(i->"g"+i).toList();
        when(index.groupsNeedingRefill(anyMap())).thenReturn(new LinkedHashSet<>(groups));
        var attempted=new ArrayList<String>();
        when(scores.observeDueHotScoreCandidates(anyString(),eq(500L),eq(100))).thenAnswer(call->{
            attempted.add(call.getArgument(0));return Map.of();
        });
        policy.refill(groups,tasks(groups));
        assertEquals(groups.subList(0,10),attempted);
        policy.refill(groups,tasks(groups));
        assertEquals(groups.subList(10,15),attempted.subList(10,15));
        assertEquals(20,attempted.size());
        verify(index,times(2)).groupsNeedingRefill(anyMap());
        verify(scores,never()).candidateizeObservedHotScores(anyString(), anyMap());
    }

    @Test void noMatchKeepsTheSingleCandidateizationWithoutRenewalOrRelease() {
        oneIssuedWorker();
        assertEquals(0,policy.refill(List.of("g"),tasks));
        verify(index).refill("g",targets,Map.ofEntries(Map.entry("w", (long) (20L))));
        verify(scores).observeDueHotScoreCandidates("g",500L,100);
        verify(scores).candidateizeObservedHotScores("g", Map.of("w",10L));
        verify(scores,atLeastOnce()).observeHotCandidateScoresBefore(eq("g"),eq(500L),anyLong(),eq(100));
        verifyNoMoreInteractions(scores);
    }

    @Test void projectionFailureLeavesTheCandidateForNormalRecycling() {
        oneIssuedWorker();
        when(index.refill(eq("g"), anyList(), anyMap())).thenThrow(new IllegalStateException("projection"));
        assertThrows(IllegalStateException.class,()->policy.refill(List.of("g"),tasks));
        verify(scores).observeDueHotScoreCandidates("g",500L,100);
        verify(scores).candidateizeObservedHotScores("g", Map.of("w",10L));
        verify(scores,atLeastOnce()).observeHotCandidateScoresBefore(eq("g"),eq(500L),anyLong(),eq(100));
        verifyNoMoreInteractions(scores);
    }

    @Test void failedCandidateizationsNeverReachMatching() {
        oneIssuedWorker();
        for(var status:List.of(WorkerScoreTransitionStatus.NOOP,WorkerScoreTransitionStatus.STALE,WorkerScoreTransitionStatus.INVALID)) {
            when(scores.candidateizeObservedHotScores("g", Map.of("w",10L)))
                    .thenReturn(Map.of("w",new WorkerScoreTransitionResult(status,20L)));
            assertEquals(0,policy.refill(List.of("g"),tasks));
        }
        verify(index,never()).refill(anyString(), anyList(), anyMap());
    }

    @Test void ambiguousCandidateizationDoesNotReachMatchingOrRetryWithinTheRound() {
        oneIssuedWorker();
        when(scores.candidateizeObservedHotScores(anyString(), anyMap())).thenThrow(new IllegalStateException("response lost"));
        assertThrows(IllegalStateException.class,()->policy.refill(List.of("g"),tasks));
        verify(index,never()).refill(anyString(), anyList(), anyMap());
        verify(scores,times(1)).candidateizeObservedHotScores(anyString(), anyMap());
    }

    @Test void qualificationNeverReceivesOrRenewsAWorkerDeadline() {
        oneIssuedWorker();
        when(index.refill(eq("g"),anyList(),anyMap())).thenAnswer(call->{
            clock.set(9000);
            assertEquals(Map.of("w",20L),call.getArgument(2)); return 0;
        });
        assertEquals(0,policy.refill(List.of("g"),tasks));
        verify(scores).candidateizeObservedHotScores("g",Map.of("w",10L));
        verify(scores,never()).acquireObservedHotScoreLeases(anyString(),anyMap(),anyLong());
    }

    @Test void oldCandidatesAreRecycledWithoutDeficitsAndUseAnIndependentBudget() {
        var groups=IntStream.range(0,15).mapToObj(i->"g"+i).toList();
        when(index.groupsNeedingRefill(anyMap())).thenReturn(Set.of());
        var attempts=new ArrayList<String>();
        when(scores.observeHotCandidateScoresBefore(anyString(),eq(500L),anyLong(),eq(100))).thenAnswer(call->{
            attempts.add(call.getArgument(0)); return Map.of("old",22L);
        });
        policy.refill(groups,tasks(groups));
        policy.refill(groups,tasks(groups));
        assertEquals(groups.subList(0,10),attempts.subList(0,10));
        assertEquals(groups.subList(10,15),attempts.subList(10,15));
        assertEquals(20,attempts.size());
        verify(scores,times(20)).recycleObservedHotCandidates(anyString(),eq(Map.of("old",22L)));
        verify(scores,never()).observeDueHotScoreCandidates(anyString(),anyLong(),anyInt());
    }

    @Test void noMatchAndProjectionFailureDoNotRescanWithinTheRound() {
        when(index.groupsNeedingRefill(anyMap())).thenReturn(Set.of("g"));
        when(scores.observeDueHotScoreCandidates("g",500L,100))
                .thenReturn(Map.of("a",11L),Map.of("b",12L),Map.of("c",13L));
        when(scores.candidateizeObservedHotScores(eq("g"), anyMap())).thenAnswer(call->{
            Map<String,Long> observed=call.getArgument(1);
            return Map.of(observed.keySet().iterator().next(),changed(20L));
        });
        when(index.refill(eq("g"), anyList(), anyMap())).thenReturn(0).thenThrow(new IllegalStateException("projection")).thenReturn(0);
        policy.refill(List.of("g"),tasks);
        assertThrows(IllegalStateException.class,()->policy.refill(List.of("g"),tasks));
        policy.refill(List.of("g"),tasks);
        verify(scores,times(3)).observeDueHotScoreCandidates("g",500L,100);
        for(String id:List.of("a","b","c")) {
            verify(index).refill("g",targets,Map.ofEntries(Map.entry(id, (long) (20L))));
        }
        verify(scores,times(3)).candidateizeObservedHotScores(eq("g"), anyMap());
        verify(scores,atLeastOnce()).observeHotCandidateScoresBefore(eq("g"),eq(500L),anyLong(),eq(100));
        verifyNoMoreInteractions(scores);
    }

    @Test void observationFailureStillRotatesToTheNextGroup() {
        var groups=List.of("a","b");
        when(index.groupsNeedingRefill(anyMap())).thenReturn(Set.copyOf(groups));
        var attempted=new ArrayList<String>();
        when(scores.observeDueHotScoreCandidates(anyString(),eq(500L),eq(100))).thenAnswer(call->{
            String group=call.getArgument(0);attempted.add(group);
            if(attempted.size()==1)throw new IllegalStateException("read");
            return Map.of();
        });
        assertThrows(IllegalStateException.class,()->policy.refill(groups,tasks(groups)));
        policy.refill(groups,tasks(groups));
        assertEquals(List.of("a","b","a"),attempted);
        verify(scores,never()).candidateizeObservedHotScores(anyString(), anyMap());
    }

    @Test void observationFailureDoesNotAcquireOrRescan() {
        oneIssuedWorker();
        when(scores.observeDueHotScoreCandidates("g",500L,100)).thenThrow(new IllegalStateException("read"))
                .thenReturn(Map.of());
        assertThrows(IllegalStateException.class,()->policy.refill(List.of("g"),tasks));
        assertEquals(0,policy.refill(List.of("g"),tasks));
        verify(scores,times(2)).observeDueHotScoreCandidates("g",500L,100);
    }
}
