package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex.HeldCandidate;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerCandidateSelectionPolicyTest {
    final WorkerResourceCatalog catalog=mock(WorkerResourceCatalog.class);
    final WorkerCandidateIndex index=mock(WorkerCandidateIndex.class);
    final WorkerCandidateSelectionPolicy policy=new WorkerCandidateSelectionPolicy(catalog,index);
    final EligibilityQuery any=EligibilityQuery.parse(Map.of());

    @org.junit.jupiter.api.BeforeEach void normalizeQueries() {
        when(index.normalizeQuery(eq("g"),eq("worker.default"),any())).thenAnswer(call -> call.getArgument(2));
    }

    @Test void emptyStockDoesNotReadAddressesOrObtainNewHolds() {
        assertTrue(policy.takeCandidates("worker.default","g",Map.of("m",any),new HashSet<>()).isEmpty());
        verify(index).take("g","worker.default",Map.of(any,1));
        verifyNoInteractions(catalog);
    }
    @Test void equivalentQueriesAggregateActualItemCountsAfterRuleNormalization() {
        var first=EligibilityQuery.parse(Map.of("country",List.of("US","CN","US")));
        var second=EligibilityQuery.parse(Map.of("country",List.of("CN","US")));
        when(index.normalizeQuery("g","worker.default",first)).thenReturn(second);
        var items=new LinkedHashMap<String,EligibilityQuery>();
        items.put("one",first); items.put("two",second); items.put("three",first);
        assertTrue(policy.takeCandidates("worker.default","g",items,new HashSet<>()).isEmpty());
        verify(index).take("g","worker.default",Map.of(second,3));
        verifyNoInteractions(catalog);
    }
    @Test void everySelectorConsumesSharedHeldStockAndReadsAddressesInOneBatch() {
        var ids=EligibilityQuery.parse(Map.of("workerId",List.of("target")));
        var items=new LinkedHashMap<String,EligibilityQuery>();
        items.put("a",any); items.put("b",ids); items.put("c",any);
        when(index.take("g","worker.default",Map.of(any,2,ids,1))).thenReturn(Map.of(any,
                List.of(new HeldCandidate("one",11,6000),new HeldCandidate("two",12,6000)),
                ids,List.of(new HeldCandidate("target",13,6000))));
        when(catalog.getWorkerDescriptors(anyList())).thenAnswer(call -> {
            var result=new LinkedHashMap<String,WorkerResourceCatalog.WorkerDescriptor>();
            for (String id:(List<String>)call.getArgument(0)) result.put(id,new WorkerResourceCatalog.WorkerDescriptor(id,"g","adapter"));
            return result;
        });
        var selected=policy.takeCandidates("worker.default","g",items,new HashSet<>());
        assertEquals("one",selected.get("a").workerId());
        assertEquals("target",selected.get("b").workerId());
        assertEquals(12,selected.get("c").heldWorkerLeaseScore());
        verify(catalog,times(1)).getWorkerDescriptors(anyList());
    }
    @Test void unavailableRuleNeverFallsBackAndWrongGroupIsNotAssigned() {
        when(index.normalizeQuery("g","unavailable",any)).thenThrow(new IllegalArgumentException("unavailable Rule"));
        assertThrows(IllegalArgumentException.class,
                ()->policy.takeCandidates("unavailable","g",Map.of("m",any),new HashSet<>()));
        verify(index).normalizeQuery("g","unavailable",any);
        verifyNoMoreInteractions(index);
        verifyNoInteractions(catalog);
        when(index.take(eq("g"),eq("worker.default"),anyMap())).thenReturn(Map.of(any,List.of(new HeldCandidate("w",1,6000))));
        when(catalog.getWorkerDescriptors(List.of("w"))).thenReturn(Map.of("w",new WorkerResourceCatalog.WorkerDescriptor("w","other","adapter")));
        var round=new HashSet<String>();
        assertTrue(policy.takeCandidates("worker.default","g",Map.of("m",any),round).isEmpty());
        assertEquals(Set.of("w"),round);
    }
    @Test void addressFailureConsumesTheCandidateAndCannotReviveItsFence() {
        when(index.take(eq("g"),eq("worker.default"),anyMap())).thenReturn(Map.of(any,List.of(new HeldCandidate("w",1,6000))));
        when(catalog.getWorkerDescriptors(List.of("w"))).thenThrow(new IllegalStateException("unavailable"));
        var round=new HashSet<String>();
        assertThrows(IllegalStateException.class,()->policy.takeCandidates("worker.default","g",Map.of("m",any),round));
        assertTrue(policy.takeCandidates("worker.default","g",Map.of("next",any),round).isEmpty());
        verify(catalog,times(1)).getWorkerDescriptors(anyList());
    }
}
