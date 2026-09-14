package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex.CandidateRenewal;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex.HeldCandidate;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkerEligibilityRefillPolicyTest {
    final WorkerScoreCore scores=mock(WorkerScoreCore.class);
    final WorkerCandidateIndex index=mock(WorkerCandidateIndex.class);
    final WorkerCandidateIndex.TaskQuery query=mock(WorkerCandidateIndex.TaskQuery.class);
    final Map<String,WorkerCandidateIndex.TaskQuery> tasks=Map.of("task",query);
    final WorkerEligibilityRefillPolicy policy=new WorkerEligibilityRefillPolicy(scores,index,500L,()->1000L);

    static WorkerScoreTransitionResult changed(long score) {
        return new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.TRANSITIONED,score);
    }
    void oneIssuedWorker() {
        when(index.deficits(tasks)).thenReturn(Map.of("g",1));
        when(scores.observeDueHotScoreCandidates("g",500L,100)).thenReturn(Map.of("w",10L));
        when(scores.acquireObservedHotScoreLeases("g",Map.of("w",10L),2000L)).thenReturn(Map.of("w",changed(20L)));
        when(scores.extendActiveHotScoreLeases("g",Map.of("w",20L),6000L)).thenReturn(Map.of("w",changed(30L)));
    }

    @Test void pacerIssuesOnlyItsExactWinnersAndRenewalReturnsTheNewFence() {
        oneIssuedWorker();
        when(index.refill(eq(tasks),eq("g"),anyList(),any())).thenAnswer(call->{
            assertEquals(List.of(new HeldCandidate("w",20L,2000L)),call.getArgument(2));
            CandidateRenewal renewal=call.getArgument(3);
            assertEquals(List.of(new HeldCandidate("w",30L,6000L)),renewal.renew(List.of("w")));
            return 1;
        });
        assertEquals(1,policy.refill(List.of("g"),tasks));
        verify(scores).observeDueHotScoreCandidates("g",500L,100); // Full mechanical batch despite deficit=1.
        verify(scores).acquireObservedHotScoreLeases("g",Map.of("w",10L),2000L);
        verify(scores).extendActiveHotScoreLeases("g",Map.of("w",20L),6000L);
        verifyNoMoreInteractions(scores);
        verifyNoInteractions(query);
    }

    @Test void batchRejectsOutsideIdentitiesDuplicatesRepeatedAndRetainedRenewal() {
        oneIssuedWorker();
        var retained=new AtomicReference<CandidateRenewal>();
        when(index.refill(eq(tasks),eq("g"),anyList(),any())).thenAnswer(call->{
            CandidateRenewal renewal=call.getArgument(3); retained.set(renewal);
            assertThrows(IllegalArgumentException.class,()->renewal.renew(List.of("outside")));
            assertThrows(IllegalArgumentException.class,()->renewal.renew(List.of("w","w")));
            verify(scores,never()).extendActiveHotScoreLeases(anyString(),anyMap(),anyLong());
            renewal.renew(List.of("w"));
            assertThrows(IllegalStateException.class,()->renewal.renew(List.of("w")));
            return 1;
        });
        policy.refill(List.of("g"),tasks);
        assertThrows(IllegalStateException.class,()->retained.get().renew(List.of("w")));
        verify(scores,times(1)).extendActiveHotScoreLeases(anyString(),anyMap(),anyLong());
    }

    @Test void callbackCannotEscapeToAnotherThreadAndClosesOnFailure() {
        oneIssuedWorker();
        var retained=new AtomicReference<CandidateRenewal>();
        when(index.refill(eq(tasks),eq("g"),anyList(),any())).thenAnswer(call->{
            CandidateRenewal renewal=call.getArgument(3);retained.set(renewal);
            try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                executor.submit(()->assertThrows(IllegalStateException.class,()->renewal.renew(List.of("w")))).get();
            }
            throw new IllegalStateException("projection failure");
        });
        assertThrows(IllegalStateException.class,()->policy.refill(List.of("g"),tasks));
        assertThrows(IllegalStateException.class,()->retained.get().renew(List.of("w")));
        verify(scores,never()).extendActiveHotScoreLeases(anyString(),anyMap(),anyLong());
    }

    @Test void satisfiedInventoryAndForeignGroupDemandNeverAcquireWorkers() {
        when(index.deficits(tasks)).thenReturn(Map.of("outside",100));
        assertEquals(0,policy.refill(List.of("g"),tasks));
        when(index.deficits(tasks)).thenReturn(Map.of());
        assertEquals(0,policy.refill(List.of("g"),tasks));
        assertEquals(0,policy.refill(List.of(),Map.of()));
        verifyNoInteractions(scores);
        verify(index,never()).refill(anyMap(),anyString(),anyList(),any());
    }

    @Test void emptyRoundsRotateAcrossMoreGroupsThanTheGlobalBudget() {
        var groups=IntStream.range(0,15).mapToObj(i->"g"+i).toList();
        var demand=new LinkedHashMap<String,Integer>();groups.forEach(g->demand.put(g,1));
        when(index.deficits(tasks)).thenReturn(demand);
        var attempted=new ArrayList<String>();
        when(scores.observeDueHotScoreCandidates(anyString(),eq(500L),eq(100))).thenAnswer(call->{
            attempted.add(call.getArgument(0));return Map.of();
        });
        policy.refill(groups,tasks);
        assertEquals(groups.subList(0,10),attempted);
        policy.refill(groups,tasks);
        assertEquals(groups.subList(10,15),attempted.subList(10,15));
        assertEquals(20,attempted.size());
        verify(scores,never()).acquireObservedHotScoreLeases(anyString(),anyMap(),anyLong());
    }

    @Test void failedInitialAcquisitionDoesNotReachMatchingOrRetryWithNewScores() {
        oneIssuedWorker();
        when(scores.acquireObservedHotScoreLeases("g",Map.of("w",10L),2000L))
                .thenReturn(Map.of("w",new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.STALE,11L)));
        assertEquals(0,policy.refill(List.of("g"),tasks));
        verify(index,never()).refill(anyMap(),anyString(),anyList(),any());
        verify(scores,never()).extendActiveHotScoreLeases(anyString(),anyMap(),anyLong());
        verify(scores,never()).getScoreStates(anyString(),anyList());
    }

    @Test void onlyAnActualExtensionReturnsAnInventoryFence() {
        oneIssuedWorker();
        for(var status:List.of(WorkerScoreTransitionStatus.NOOP,WorkerScoreTransitionStatus.STALE,WorkerScoreTransitionStatus.INVALID)) {
            when(scores.extendActiveHotScoreLeases("g",Map.of("w",20L),6000L))
                    .thenReturn(Map.of("w",new WorkerScoreTransitionResult(status,20L)));
            when(index.refill(eq(tasks),eq("g"),anyList(),any())).thenAnswer(call->{
                CandidateRenewal renewal=call.getArgument(3);
                assertTrue(renewal.renew(List.of("w")).isEmpty());
                return 0;
            });
            assertEquals(0,policy.refill(List.of("g"),tasks));
        }
    }

    @Test void ambiguousRenewalCannotBeRetriedWithinOrAfterTheInvocation() {
        oneIssuedWorker();
        when(scores.extendActiveHotScoreLeases(anyString(),anyMap(),anyLong())).thenThrow(new IllegalStateException("response lost"));
        when(index.refill(eq(tasks),eq("g"),anyList(),any())).thenAnswer(call->{
            CandidateRenewal renewal=call.getArgument(3);
            assertThrows(IllegalStateException.class,()->renewal.renew(List.of("w")));
            assertThrows(IllegalStateException.class,()->renewal.renew(List.of("w")));
            return 0;
        });
        assertEquals(0,policy.refill(List.of("g"),tasks));
        verify(scores,times(1)).extendActiveHotScoreLeases(anyString(),anyMap(),anyLong());
    }
}
