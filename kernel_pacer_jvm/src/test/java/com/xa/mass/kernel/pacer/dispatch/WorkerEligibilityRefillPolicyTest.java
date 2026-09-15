package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex.CandidateLease;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex.HeldCandidate;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkerEligibilityRefillPolicyTest {
    final WorkerScoreCore scores=mock(WorkerScoreCore.class);
    final WorkerCandidateIndex index=mock(WorkerCandidateIndex.class);
    final WorkerCandidateIndex.RefillBatch batch=mock(WorkerCandidateIndex.RefillBatch.class);
    final WorkerCandidateIndex.TaskQuery query=mock(WorkerCandidateIndex.TaskQuery.class);
    final Map<String,WorkerCandidateIndex.TaskQuery> tasks=Map.of("task",query);
    final java.util.concurrent.atomic.AtomicLong clock=new java.util.concurrent.atomic.AtomicLong(1000);
    final WorkerEligibilityRefillPolicy policy=new WorkerEligibilityRefillPolicy(scores,index,500L,clock::get);

    @BeforeEach void prepareBatch() { when(index.prepareRefill(anyMap())).thenReturn(batch); }

    static WorkerScoreTransitionResult changed(long score) {
        return new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.TRANSITIONED,score);
    }
    void oneIssuedWorker() {
        when(batch.groupsNeedingRefill()).thenReturn(Set.of("g"));
        when(scores.observeDueHotScoreCandidates("g",500L,0,100)).thenReturn(new WorkerScoreCore.WorkerScoreCandidatePage(Map.of("w",10L),0));
        when(scores.acquireObservedHotScoreLeases("g",Map.of("w",10L),2000L)).thenReturn(Map.of("w",changed(20L)));
    }

    @Test void pacerObservesWithoutHoldingAndAcquiresOnlyAfterMatching() {
        oneIssuedWorker();
        when(batch.refill(eq("g"),anyMap(),any())).thenAnswer(call->{
            assertEquals(Map.of("w",10L),call.getArgument(1));
            verify(scores,never()).acquireObservedHotScoreLeases(anyString(),anyMap(),anyLong());
            CandidateLease lease=call.getArgument(2);
            assertEquals(List.of(new HeldCandidate("w",20L,2000L)),lease.acquire(List.of("w")));
            return 1;
        });
        assertEquals(1,policy.refill(List.of("g"),tasks));
        verify(scores).observeDueHotScoreCandidates("g",500L,0,100); // Demand enables the full mechanical batch.
        verify(scores).acquireObservedHotScoreLeases("g",Map.of("w",10L),2000L);
        verifyNoMoreInteractions(scores);
        verifyNoInteractions(query);
        verify(index,times(1)).prepareRefill(tasks);
    }

    @Test void batchRejectsOutsideIdentitiesDuplicatesRepeatedAndRetainedAcquisition() {
        oneIssuedWorker();
        var retained=new AtomicReference<CandidateLease>();
        when(batch.refill(eq("g"),anyMap(),any())).thenAnswer(call->{
            CandidateLease lease=call.getArgument(2); retained.set(lease);
            assertThrows(IllegalArgumentException.class,()->lease.acquire(List.of("outside")));
            assertThrows(IllegalArgumentException.class,()->lease.acquire(List.of("w","w")));
            verify(scores,never()).acquireObservedHotScoreLeases(anyString(),anyMap(),anyLong());
            lease.acquire(List.of("w"));
            assertThrows(IllegalStateException.class,()->lease.acquire(List.of("w")));
            return 1;
        });
        policy.refill(List.of("g"),tasks);
        assertThrows(IllegalStateException.class,()->retained.get().acquire(List.of("w")));
        verify(scores,times(1)).acquireObservedHotScoreLeases(anyString(),anyMap(),anyLong());
    }

    @Test void callbackCannotEscapeToAnotherThreadAndClosesOnFailure() {
        oneIssuedWorker();
        var retained=new AtomicReference<CandidateLease>();
        when(batch.refill(eq("g"),anyMap(),any())).thenAnswer(call->{
            CandidateLease lease=call.getArgument(2);retained.set(lease);
            try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                executor.submit(()->assertThrows(IllegalStateException.class,()->lease.acquire(List.of("w")))).get();
            }
            throw new IllegalStateException("projection failure");
        });
        assertThrows(IllegalStateException.class,()->policy.refill(List.of("g"),tasks));
        assertThrows(IllegalStateException.class,()->retained.get().acquire(List.of("w")));
        verify(scores,never()).acquireObservedHotScoreLeases(anyString(),anyMap(),anyLong());
    }

    @Test void satisfiedInventoryAndForeignGroupDemandNeverAcquireWorkers() {
        when(batch.groupsNeedingRefill()).thenReturn(Set.of("outside"));
        assertEquals(0,policy.refill(List.of("g"),tasks));
        when(batch.groupsNeedingRefill()).thenReturn(Set.of());
        assertEquals(0,policy.refill(List.of("g"),tasks));
        assertEquals(0,policy.refill(List.of(),Map.of()));
        verifyNoInteractions(scores);
        verify(batch,never()).refill(anyString(),anyMap(),any());
    }

    @Test void emptyRoundsRotateAcrossMoreGroupsThanTheGlobalBudget() {
        var groups=IntStream.range(0,15).mapToObj(i->"g"+i).toList();
        when(batch.groupsNeedingRefill()).thenReturn(new LinkedHashSet<>(groups));
        var attempted=new ArrayList<String>();
        when(scores.observeDueHotScoreCandidates(anyString(),eq(500L),anyLong(),eq(100))).thenAnswer(call->{
            attempted.add(call.getArgument(0));return new WorkerScoreCore.WorkerScoreCandidatePage(Map.of(),0);
        });
        policy.refill(groups,tasks);
        assertEquals(groups.subList(0,10),attempted);
        policy.refill(groups,tasks);
        assertEquals(groups.subList(10,15),attempted.subList(10,15));
        assertEquals(20,attempted.size());
        verify(index,times(2)).prepareRefill(tasks);
        verify(batch,times(2)).groupsNeedingRefill();
        verify(scores,never()).acquireObservedHotScoreLeases(anyString(),anyMap(),anyLong());
    }

    @Test void noMatchNeverAcquiresALease() {
        oneIssuedWorker();
        assertEquals(0,policy.refill(List.of("g"),tasks));
        verify(batch).refill(eq("g"),eq(Map.of("w",10L)),any());
        verify(scores,never()).acquireObservedHotScoreLeases(anyString(),anyMap(),anyLong());
    }

    @Test void onlySuccessfulAcquisitionReturnsAnInventoryFence() {
        oneIssuedWorker();
        for(var status:List.of(WorkerScoreTransitionStatus.NOOP,WorkerScoreTransitionStatus.STALE,WorkerScoreTransitionStatus.INVALID)) {
            when(scores.acquireObservedHotScoreLeases("g",Map.of("w",10L),2000L))
                    .thenReturn(Map.of("w",new WorkerScoreTransitionResult(status,20L)));
            when(batch.refill(eq("g"),anyMap(),any())).thenAnswer(call->{
                CandidateLease lease=call.getArgument(2);
                assertTrue(lease.acquire(List.of("w")).isEmpty());
                return 0;
            });
            assertEquals(0,policy.refill(List.of("g"),tasks));
        }
    }

    @Test void ambiguousAcquisitionCannotBeRetriedWithinOrAfterTheInvocation() {
        oneIssuedWorker();
        when(scores.acquireObservedHotScoreLeases(anyString(),anyMap(),anyLong())).thenThrow(new IllegalStateException("response lost"));
        when(batch.refill(eq("g"),anyMap(),any())).thenAnswer(call->{
            CandidateLease lease=call.getArgument(2);
            assertThrows(IllegalStateException.class,()->lease.acquire(List.of("w")));
            assertThrows(IllegalStateException.class,()->lease.acquire(List.of("w")));
            return 0;
        });
        assertEquals(0,policy.refill(List.of("g"),tasks));
        verify(scores,times(1)).acquireObservedHotScoreLeases(anyString(),anyMap(),anyLong());
    }

    @Test void matchingTimeDoesNotConsumeCandidateLease() {
        oneIssuedWorker();
        when(scores.acquireObservedHotScoreLeases("g",Map.of("w",10L),7000L)).thenReturn(Map.of("w",changed(30L)));
        when(batch.refill(eq("g"),anyMap(),any())).thenAnswer(call->{
            clock.set(6000);
            CandidateLease lease=call.getArgument(2);
            assertEquals(7000L,lease.acquire(List.of("w")).getFirst().expiresAtMillis()); return 1;
        });
        assertEquals(1,policy.refill(List.of("g"),tasks));
        verify(scores,never()).acquireObservedHotScoreLeases("g",Map.of("w",10L),2000L);
    }

    @Test void noMatchAndProjectionFailureAdvancePagesAndRemovedGroupsForgetProgress() {
        when(batch.groupsNeedingRefill()).thenReturn(Set.of("g"));
        var offsets=new ArrayList<Long>();
        when(scores.observeDueHotScoreCandidates(eq("g"),eq(500L),anyLong(),eq(100))).thenAnswer(call->{
            long offset=call.getArgument(2);offsets.add(offset);
            return new WorkerScoreCore.WorkerScoreCandidatePage(Map.of("w",11L),offset+100);
        });
        when(batch.refill(eq("g"),anyMap(),any())).thenReturn(0).thenThrow(new IllegalStateException("projection")).thenReturn(0);
        policy.refill(List.of("g"),tasks);
        assertThrows(IllegalStateException.class,()->policy.refill(List.of("g"),tasks));
        policy.refill(List.of("g"),tasks);
        policy.refill(List.of(),Map.of());policy.refill(List.of("g"),tasks);
        assertEquals(List.of(0L,100L,200L,0L),offsets);
        verify(scores,never()).acquireObservedHotScoreLeases(anyString(),anyMap(),anyLong());
    }

    @Test void observationFailureDoesNotInventPageProgress() {
        oneIssuedWorker();
        when(scores.observeDueHotScoreCandidates("g",500L,0,100)).thenThrow(new IllegalStateException("read"))
                .thenReturn(new WorkerScoreCore.WorkerScoreCandidatePage(Map.of(),0));
        assertThrows(IllegalStateException.class,()->policy.refill(List.of("g"),tasks));
        assertEquals(0,policy.refill(List.of("g"),tasks));
        verify(scores,times(2)).observeDueHotScoreCandidates("g",500L,0,100);
    }
}
