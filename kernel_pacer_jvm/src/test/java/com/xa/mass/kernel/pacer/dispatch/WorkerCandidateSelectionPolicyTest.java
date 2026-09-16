package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.WorkerMatching;
import com.xa.mass.kernel.assignment.WorkerMatching.HeldCandidate;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerCandidateSelectionPolicyTest {
    final WorkerResourceCatalog catalog=mock(WorkerResourceCatalog.class);
    final WorkerMatching matching=mock(WorkerMatching.class);
    final WorkerCandidateSelectionPolicy policy=new WorkerCandidateSelectionPolicy(catalog,matching);
    final EligibilityQuery any=EligibilityQuery.parse(Map.of());

    @Test void emptyStockDoesNotReadAddressesOrObtainNewHolds() {
        assertTrue(policy.takeCandidates("worker.default","g",Map.of("m",any),new HashSet<>()).isEmpty());
        verify(matching).take("g","worker.default",Map.of("m",any));
        verifyNoMoreInteractions(matching);
        verifyNoInteractions(catalog);
    }
    @Test void queriesAreForwardedOnceWithOriginalIdentityAndRepresentation() {
        var first=EligibilityQuery.parse(Map.of("country",List.of("US","CN","US")));
        var second=EligibilityQuery.parse(Map.of("country",List.of("CN","US")));
        var items=new LinkedHashMap<String,EligibilityQuery>();
        items.put("one",first); items.put("two",second); items.put("three",first);
        assertTrue(policy.takeCandidates("worker.default","g",items,new HashSet<>()).isEmpty());
        verify(matching).take(eq("g"),eq("worker.default"),same(items));
        verifyNoMoreInteractions(matching);
        verifyNoInteractions(catalog);
    }
    @Test void everySelectorConsumesSharedHeldStockAndReadsAddressesInOneBatch() {
        var ids=EligibilityQuery.parse(Map.of("workerId",List.of("target")));
        var items=new LinkedHashMap<String,EligibilityQuery>();
        items.put("a",any); items.put("b",ids); items.put("c",any);
        when(matching.take("g","worker.default",items)).thenReturn(Map.of(
                "a",new HeldCandidate("one",11,6000),"b",new HeldCandidate("target",13,6000),
                "c",new HeldCandidate("two",12,6000)));
        when(catalog.getWorkerDescriptors(anyList())).thenAnswer(call -> {
            var result=new LinkedHashMap<String,WorkerResourceCatalog.WorkerDescriptor>();
            for (String id:(List<String>)call.getArgument(0)) result.put(id,descriptor(id,"g"));
            return result;
        });
        var selected=policy.takeCandidates("worker.default","g",items,new HashSet<>());
        assertEquals(List.of("a","b","c"),List.copyOf(selected.keySet()));
        assertEquals("one",selected.get("a").workerId());
        assertEquals("target",selected.get("b").workerId());
        assertEquals(12,selected.get("c").heldWorkerLeaseScore());
        verify(matching).take("g","worker.default",items);
        verifyNoMoreInteractions(matching);
        verify(catalog).getWorkerDescriptors(List.of("one","target","two"));
    }
    @Test void unavailableRuleNeverFallsBack() {
        when(matching.take("g","unavailable",Map.of("m",any))).thenThrow(new IllegalArgumentException("unavailable Rule"));
        assertThrows(IllegalArgumentException.class,
                ()->policy.takeCandidates("unavailable","g",Map.of("m",any),new HashSet<>()));
        verify(matching).take("g","unavailable",Map.of("m",any));
        verifyNoMoreInteractions(matching);
        verifyNoInteractions(catalog);
    }
    @Test void filteredCandidatesNeverMoveToAnotherMessageOrTriggerAnotherTake() {
        var items=new LinkedHashMap<String,EligibilityQuery>();
        for(String id:List.of("used","missing","wrongGroup","wrongId","valid","unfilled"))items.put(id,any);
        when(matching.take("g","worker.default",items)).thenReturn(Map.of(
                "used",new HeldCandidate("old",1,6000),"missing",new HeldCandidate("missing",2,6000),
                "wrongGroup",new HeldCandidate("other",3,6000),"wrongId",new HeldCandidate("alias",4,6000),
                "valid",new HeldCandidate("ok",5,6000)));
        when(catalog.getWorkerDescriptors(List.of("missing","other","alias","ok"))).thenReturn(Map.of(
                "other",descriptor("other","otherGroup"),"alias",descriptor("different","g"),"ok",descriptor("ok","g")));
        var round=new HashSet<>(Set.of("old"));
        var selected=policy.takeCandidates("worker.default","g",items,round);
        assertEquals(Set.of("valid"),selected.keySet());
        assertEquals("ok",selected.get("valid").workerId());
        assertEquals(Set.of("old","missing","other","alias","ok"),round);
        verify(matching).take("g","worker.default",items);
        verifyNoMoreInteractions(matching);
        verify(catalog).getWorkerDescriptors(List.of("missing","other","alias","ok"));
    }
    @Test void addressFailureConsumesTheCandidateAndCannotReviveItsFence() {
        when(matching.take("g","worker.default",Map.of("m",any))).thenReturn(Map.of("m",new HeldCandidate("w",1,6000)));
        when(matching.take("g","worker.default",Map.of("next",any))).thenReturn(Map.of("next",new HeldCandidate("w",1,6000)));
        when(catalog.getWorkerDescriptors(List.of("w"))).thenThrow(new IllegalStateException("unavailable"));
        var round=new HashSet<String>();
        assertThrows(IllegalStateException.class,()->policy.takeCandidates("worker.default","g",Map.of("m",any),round));
        verify(matching).take("g","worker.default",Map.of("m",any));
        verifyNoMoreInteractions(matching);
        assertTrue(policy.takeCandidates("worker.default","g",Map.of("next",any),round).isEmpty());
        verify(catalog,times(1)).getWorkerDescriptors(anyList());
    }
    @Test void emptyAndOversizedBatchesDoNotReachOwners() {
        assertTrue(policy.takeCandidates("worker.default","g",Map.of(),new HashSet<>()).isEmpty());
        var items=new LinkedHashMap<String,EligibilityQuery>();
        for(int i=0;i<101;i++)items.put("m"+i,any);
        assertThrows(IllegalArgumentException.class,()->policy.takeCandidates("worker.default","g",items,new HashSet<>()));
        verifyNoInteractions(matching,catalog);
    }
    private static WorkerResourceCatalog.WorkerDescriptor descriptor(String id,String group) {
        return new WorkerResourceCatalog.WorkerDescriptor(id,group,"adapter");
    }
}
