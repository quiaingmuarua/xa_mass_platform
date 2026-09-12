package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex.TaskQuery;
import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerDescriptor;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WorkerCandidateSelectionPolicyTest {
    final WorkerScoreCore scores=mock(WorkerScoreCore.class);
    final WorkerResourceCatalog catalog=mock(WorkerResourceCatalog.class);
    final WorkerCandidateIndex index=mock(WorkerCandidateIndex.class);
    final TaskQuery query=mock(TaskQuery.class);
    final WorkerCandidateSelectionPolicy policy=new WorkerCandidateSelectionPolicy(scores,catalog,null,index);
    private static TaskItemWorkerSelector anyWorker() { return TaskItemWorkerSelector.parse(Map.of()); }
    private static TaskItemWorkerSelector selector(String value) {
        return TaskItemWorkerSelector.parse(Map.of("business.route",Map.of("op","eq","values",List.of(value))));
    }
    private static WorkerScoreTransitionResult held(long score) {
        return new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.TRANSITIONED,score);
    }
    private static WorkerDescriptor descriptor(String id) { return new WorkerDescriptor(id,"g","adapter"); }
    private Map<String,HeldWorkerCandidate> acquire(Map<String,TaskItemWorkerSelector> items) {
        return policy.acquireCandidates(query,"g",items,new java.util.LinkedHashSet<>(),5000);
    }

    @Test void missingQueryNeverFallsBackToAnyOrCreatesAHold() {
        assertEquals(Map.of(),policy.acquireCandidates(null,"g",Map.of("m",anyWorker()),Set.of(),5000));
        verifyNoInteractions(scores,catalog,index);
    }
    @Test void hundredTaskCoordinatesTravelAsOneBatch() {
        var tasks=new LinkedHashMap<String,String>();
        for(int i=0;i<100;i++)tasks.put("task-"+i,"g");
        policy.prepareQueries(tasks);
        verify(index).prepareTaskQueries(tasks);
        verifyNoMoreInteractions(index);
    }
    @Test void hundredDistinctOpaqueQueriesShareOneTakeHotHoldRecheckAndBindingRead() {
        var items=new LinkedHashMap<String,TaskItemWorkerSelector>();
        var limits=new LinkedHashMap<TaskItemWorkerSelector,Integer>();
        var candidates=new LinkedHashMap<TaskItemWorkerSelector,List<String>>();
        var retained=new LinkedHashMap<TaskItemWorkerSelector,Set<String>>();
        var observed=new LinkedHashMap<String,Long>();
        var transitions=new LinkedHashMap<String,WorkerScoreTransitionResult>();
        var descriptions=new LinkedHashMap<String,WorkerDescriptor>();
        for(int i=0;i<100;i++) {
            String id="worker-"+i; var selector=selector("route-"+i);
            items.put("item-"+i,selector); limits.put(selector,1);
            candidates.put(selector,List.of(id)); retained.put(selector,Set.of(id));
            observed.put(id,(long)i); transitions.put(id,held(1000+i)); descriptions.put(id,descriptor(id));
        }
        when(query.take(limits)).thenReturn(candidates);
        when(scores.observeDueHotScores("g",List.copyOf(observed.keySet()),null)).thenReturn(observed);
        when(scores.acquireObservedHotScoreLeases("g",observed,5000)).thenReturn(transitions);
        when(query.retain(candidates)).thenReturn(retained);
        when(catalog.getWorkerDescriptors(List.copyOf(observed.keySet()))).thenReturn(descriptions);
        var result=acquire(items);
        assertEquals(100,result.size());
        for(int i=0;i<100;i++) assertEquals(1000+i,result.get("item-"+i).heldWorkerLeaseScore());
        var order=inOrder(query,scores,catalog);
        order.verify(query).take(limits);
        order.verify(scores).observeDueHotScores("g",List.copyOf(observed.keySet()),null);
        order.verify(scores).acquireObservedHotScoreLeases("g",observed,5000);
        order.verify(query).retain(candidates);
        order.verify(catalog).getWorkerDescriptors(List.copyOf(observed.keySet()));
        verifyNoInteractions(index);
    }
    @Test void actualDemandBoundsTakeAndBusyCandidatesDoNotTriggerRefill() {
        var a=selector("a"); var b=selector("b");
        var items=new LinkedHashMap<String,TaskItemWorkerSelector>();
        items.put("a0",a); items.put("b0",b); items.put("a1",a);
        var limits=new LinkedHashMap<TaskItemWorkerSelector,Integer>(); limits.put(a,2); limits.put(b,1);
        when(query.take(limits)).thenReturn(Map.of(a,List.of("busy"),b,List.of()));
        assertTrue(acquire(items).isEmpty());
        verify(query).take(limits);
        verify(query,never()).retain(anyMap());
        verify(scores,never()).acquireObservedHotScoreLeases(anyString(),anyMap(),anyLong());
    }
    @Test void postHoldMembershipLossPairsSurvivorsWithEarliestItems() {
        var selector=selector("opaque");
        var items=new LinkedHashMap<String,TaskItemWorkerSelector>();
        for(int i=0;i<3;i++)items.put("item-"+i,selector);
        var observed=new LinkedHashMap<String,Long>(); observed.put("stale",1L); observed.put("changed",2L); observed.put("survivor",3L);
        when(query.take(Map.of(selector,3))).thenReturn(Map.of(selector,List.copyOf(observed.keySet())));
        when(scores.observeDueHotScores("g",List.copyOf(observed.keySet()),null)).thenReturn(observed);
        when(scores.acquireObservedHotScoreLeases("g",observed,5000)).thenReturn(Map.of("changed",held(20),"survivor",held(30)));
        when(query.retain(Map.of(selector,List.of("changed","survivor")))).thenReturn(Map.of(selector,Set.of("survivor")));
        when(catalog.getWorkerDescriptors(List.of("survivor"))).thenReturn(Map.of("survivor",descriptor("survivor")));
        assertEquals(Map.of("item-0",new HeldWorkerCandidate("survivor","g","adapter",30)),acquire(items));
        verify(scores,never()).releaseScoreHolds(anyString(),anyMap(),anyLong());
    }
    @Test void defaultAnyUsesKernelHotPoolAndDoesNotTouchAnIndex() {
        when(query.usesIdentitySelection(any())).thenReturn(true);
        when(scores.observeDueHotScoreCandidates("g",null,1)).thenReturn(Map.of("w",10L));
        when(scores.acquireObservedHotScoreLeases("g",Map.of("w",10L),5000)).thenReturn(Map.of("w",held(20)));
        when(catalog.getWorkerDescriptors(List.of("w"))).thenReturn(Map.of("w",descriptor("w")));
        assertEquals("w",acquire(Map.of("m",anyWorker())).get("m").workerId());
        verify(query,never()).take(anyMap()); verify(query,never()).retain(anyMap());
    }
    @Test void explicitTargetsBeyondTheFirstHundredRemainEligibleWithOneBoundedRead() {
        when(query.usesIdentitySelection(any())).thenReturn(true);
        var items = new LinkedHashMap<String, TaskItemWorkerSelector>();
        var targets = new java.util.ArrayList<String>();
        for (int i = 0; i < 100; i++) {
            var ids = new java.util.ArrayList<String>();
            for (int j = 0; j < 100; j++) ids.add("w-" + i + "-" + j);
            targets.addAll(ids);
            items.put("m-" + i, TaskItemWorkerSelector.parse(Map.of("workerId", ids)));
        }
        when(scores.observeDueHotScores("g", targets, null)).thenReturn(Map.of("w-99-99", 10L));
        when(scores.acquireObservedHotScoreLeases("g", Map.of("w-99-99", 10L), 5000))
                .thenReturn(Map.of("w-99-99", held(20)));
        when(catalog.getWorkerDescriptors(List.of("w-99-99")))
                .thenReturn(Map.of("w-99-99", descriptor("w-99-99")));
        assertEquals(Map.of("m-99", new HeldWorkerCandidate("w-99-99", "g", "adapter", 20)), acquire(items));
        verify(scores).observeDueHotScores("g", targets, null);
        verify(scores).acquireObservedHotScoreLeases("g", Map.of("w-99-99", 10L), 5000);
        verify(query, never()).take(anyMap());
    }
    @Test void namedAnyAndExplicitIdsAlwaysUseBoundIndex() {
        for(var selector:List.of(anyWorker(),TaskItemWorkerSelector.parse(Map.of("workerId",List.of("w"))))) {
            assertTrue(acquire(Map.of("m",selector)).isEmpty());
            verify(query).take(Map.of(selector,1));
        }
        verifyNoInteractions(scores,catalog);
    }
    @Test void failedMembershipReadStillExcludesItsHoldFromLaterTasksInTheRound() {
        var round = new java.util.LinkedHashSet<String>();
        when(query.take(Map.of(anyWorker(), 1))).thenReturn(Map.of(anyWorker(), List.of("w")));
        when(scores.observeDueHotScores("g", List.of("w"), null)).thenReturn(Map.of("w", 1L));
        when(scores.acquireObservedHotScoreLeases("g", Map.of("w", 1L), 5000)).thenReturn(Map.of("w", held(2)));
        when(query.retain(Map.of(anyWorker(), List.of("w")))).thenThrow(new IllegalStateException("index read failed"));
        assertThrows(IllegalStateException.class, () ->
                policy.acquireCandidates(query, "g", Map.of("first", anyWorker()), round, 5000));
        assertEquals(Set.of("w"), round);
        assertTrue(policy.acquireCandidates(query, "g", Map.of("second", anyWorker()), round, 5000).isEmpty());
        verify(scores).observeDueHotScores("g", List.of("w"), null);
        verify(scores).acquireObservedHotScoreLeases("g", Map.of("w", 1L), 5000);
    }
    @Test void wrongGroupOrMissingDescriptorsDiscardHeldEvidence() {
        when(query.take(Map.of(anyWorker(),1))).thenReturn(Map.of(anyWorker(),List.of("w")));
        when(scores.observeDueHotScores("g",List.of("w"),null)).thenReturn(Map.of("w",1L));
        when(scores.acquireObservedHotScoreLeases("g",Map.of("w",1L),5000)).thenReturn(Map.of("w",held(2)));
        when(query.retain(Map.of(anyWorker(),List.of("w")))).thenReturn(Map.of(anyWorker(),Set.of("w")));
        when(catalog.getWorkerDescriptors(List.of("w"))).thenReturn(Map.of("w",new WorkerDescriptor("w","other","adapter")));
        assertTrue(acquire(Map.of("m",anyWorker())).isEmpty());
    }
}
