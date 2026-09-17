package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.WorkerMatching;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerCandidateSelectionPolicyTest {
    final WorkerResourceCatalog catalog=mock(WorkerResourceCatalog.class);
    final WorkerMatching matching=mock(WorkerMatching.class);
    final WorkerCandidateSelectionPolicy policy=new WorkerCandidateSelectionPolicy(catalog,matching);
    final WorkerQuery any=new WorkerQuery("worker.default", Map.of());

    @Test void emptyStockDoesNotReadAddressesOrObtainNewHolds() {
        assertTrue(policy.takeCandidates("g",Map.of("m",any),new HashSet<>()).isEmpty());
        verify(matching).take("g",Map.of("m",any));
        verifyNoMoreInteractions(matching);
        verifyNoInteractions(catalog);
    }
    @Test void queriesAreForwardedOnceWithOriginalIdentityAndRepresentation() {
        var first=new WorkerQuery("worker.default", Map.of("country",List.of("US","CN","US")));
        var second=new WorkerQuery("worker.default", Map.of("country",List.of("CN","US")));
        var items=new LinkedHashMap<String,WorkerQuery>();
        items.put("one",first); items.put("two",second); items.put("three",first);
        assertTrue(policy.takeCandidates("g",items,new HashSet<>()).isEmpty());
        verify(matching).take(eq("g"),same(items));
        verifyNoMoreInteractions(matching);
        verifyNoInteractions(catalog);
    }
    @Test void everySelectorKeepsItsExpectedFenceAndReadsAddressesInOneBatch() {
        var ids=new WorkerQuery("worker.default", Map.of("workerId",List.of("target")));
        var items=new LinkedHashMap<String,WorkerQuery>();
        items.put("a",any); items.put("b",ids); items.put("c",any);
        when(matching.take("g",items)).thenReturn(Map.of(
                "a",new WorkerCandidate("one",0),"b",new WorkerCandidate("target",13),
                "c",new WorkerCandidate("two",12)));
        when(catalog.getWorkerDescriptors(anyList())).thenAnswer(call -> {
            var result=new LinkedHashMap<String,WorkerResourceCatalog.WorkerDescriptor>();
            for (String id:(List<String>)call.getArgument(0)) result.put(id,descriptor(id,"g"));
            return result;
        });
        var selected=policy.takeCandidates("g",items,new HashSet<>());
        assertEquals(List.of("a","b","c"),List.copyOf(selected.keySet()));
        assertEquals("one",selected.get("a").workerId());
        assertEquals("target",selected.get("b").workerId());
        assertEquals(0,selected.get("a").expectedScore());
        assertEquals(13,selected.get("b").expectedScore());
        assertEquals(12,selected.get("c").expectedScore());
        verify(matching).take("g",items);
        verifyNoMoreInteractions(matching);
        verify(catalog).getWorkerDescriptors(List.of("one","target","two"));
    }
    @Test void unavailableRuleNeverFallsBack() {
        when(matching.take("g",Map.of("m",any))).thenThrow(new IllegalArgumentException("unavailable Rule"));
        assertThrows(IllegalArgumentException.class,
                ()->policy.takeCandidates("g",Map.of("m",any),new HashSet<>()));
        verify(matching).take("g",Map.of("m",any));
        verifyNoMoreInteractions(matching);
        verifyNoInteractions(catalog);
    }
    @Test void filteredCandidatesNeverMoveToAnotherMessageOrTriggerAnotherTake() {
        var items=new LinkedHashMap<String,WorkerQuery>();
        for(String id:List.of("used","missing","wrongGroup","wrongId","valid","unfilled"))items.put(id,any);
        when(matching.take("g",items)).thenReturn(Map.of(
                "used",new WorkerCandidate("old",1),"missing",new WorkerCandidate("missing",2),
                "wrongGroup",new WorkerCandidate("other",3),"wrongId",new WorkerCandidate("alias",4),
                "valid",new WorkerCandidate("ok",5)));
        when(catalog.getWorkerDescriptors(List.of("missing","other","alias","ok"))).thenReturn(Map.of(
                "other",descriptor("other","otherGroup"),"alias",descriptor("different","g"),"ok",descriptor("ok","g")));
        var round=new HashSet<>(Set.of("old"));
        var selected=policy.takeCandidates("g",items,round);
        assertEquals(Set.of("valid"),selected.keySet());
        assertEquals("ok",selected.get("valid").workerId());
        assertEquals(Set.of("old","missing","other","alias","ok"),round);
        verify(matching).take("g",items);
        verifyNoMoreInteractions(matching);
        verify(catalog).getWorkerDescriptors(List.of("missing","other","alias","ok"));
    }
    @Test void addressFailureConsumesTheCandidateAndCannotReviveItsFence() {
        when(matching.take("g",Map.of("m",any))).thenReturn(Map.of("m",new WorkerCandidate("w",1)));
        when(matching.take("g",Map.of("next",any))).thenReturn(Map.of("next",new WorkerCandidate("w",1)));
        when(catalog.getWorkerDescriptors(List.of("w"))).thenThrow(new IllegalStateException("unavailable"));
        var round=new HashSet<String>();
        assertThrows(IllegalStateException.class,()->policy.takeCandidates("g",Map.of("m",any),round));
        verify(matching).take("g",Map.of("m",any));
        verifyNoMoreInteractions(matching);
        assertTrue(policy.takeCandidates("g",Map.of("next",any),round).isEmpty());
        verify(catalog,times(1)).getWorkerDescriptors(anyList());
    }
    @Test void emptyAndOversizedBatchesDoNotReachOwners() {
        assertTrue(policy.takeCandidates("g",Map.of(),new HashSet<>()).isEmpty());
        var items=new LinkedHashMap<String,WorkerQuery>();
        for(int i=0;i<101;i++)items.put("m"+i,any);
        assertThrows(IllegalArgumentException.class,()->policy.takeCandidates("g",items,new HashSet<>()));
        verifyNoInteractions(matching,catalog);
    }
    @Test void directIdentitiesStillRequireExistingSameGroupDescriptors() {
        var input = new LinkedHashMap<String, WorkerQuery>();
        input.put("missing", new WorkerQuery("workerId", "absent"));
        input.put("foreign", new WorkerQuery("workerId", "foreign"));
        input.put("valid", new WorkerQuery("worker.phone", "+1"));
        when(matching.take("g", input)).thenReturn(Map.of("missing", new WorkerCandidate("absent", 0),
                "foreign", new WorkerCandidate("foreign", 0), "valid", new WorkerCandidate("ok", 0)));
        when(catalog.getWorkerDescriptors(List.of("absent", "foreign", "ok")))
                .thenReturn(Map.of("foreign", descriptor("foreign", "another-group"), "ok", descriptor("ok", "g")));
        assertEquals(Set.of("valid"), policy.takeCandidates("g", input, new HashSet<>()).keySet());
        verify(matching).take("g", input); verifyNoMoreInteractions(matching);
    }
    private static WorkerResourceCatalog.WorkerDescriptor descriptor(String id,String group) {
        return new WorkerResourceCatalog.WorkerDescriptor(id,group,"adapter");
    }
}
