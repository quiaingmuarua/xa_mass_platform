package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex.HeldCandidate;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex.InitialHold;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SharedEligibilityTest {
    final AtomicLong clock=new AtomicLong(1000);
    final SharedEligibilityInventory stock=new SharedEligibilityInventory(clock::get);
    final SharedEligibilityInventory.Scope scope=new SharedEligibilityInventory.Scope("g","worker.country");
    final RuleIndex source=mock(RuleIndex.class);
    final InitialHold holds=mock(InitialHold.class);
    final SharedEligibility index=new SharedEligibility(stock,scope,RuleHandler.COUNTRY,source,0);

    static EligibilityQuery countries(int count,String... countries) {
        return new EligibilityQuery(Map.of("worker.country",List.of(countries)),count);
    }
    static RuleIndex.Projection country(String code) {
        return new RuleIndex.Projection(Integer.toString(CountryIndex.code(code)),Set.of());
    }
    void populate(int size,String country) {
        stock.add(scope,IntStream.range(0,size).mapToObj(i -> new SharedEligibilityInventory.Entry(
                new HeldCandidate("w"+i,100+i,6000),country(country))).toList());
    }

    @Test void fullTargetsAndTakeNeedNoSourceOrHoldCall() {
        populate(20,"US");
        var targets=List.of(countries(10,"US"),countries(20,"US","CN"));
        assertEquals(List.of(0,0),new ArrayList<>(index.deficits(targets).values()));
        assertEquals(0,index.refill(targets,100,holds));
        var request=countries(1,"CN","US");
        assertEquals(1,index.take(List.of(request)).get(request).size());
        verifyNoInteractions(source,holds);
    }

    @Test void overlappingTargetsRecountAfterAdmissionAndNeverDuplicatePhysicalStock() {
        var us=countries(10,"US"); var either=countries(10,"US","CN");
        var criteria=index.criteria(us);
        var ids=IntStream.range(0,10).mapToObj(i -> "w"+i).toList();
        var candidates=IntStream.range(0,10).mapToObj(i -> new HeldCandidate("w"+i,100+i,6000)).toList();
        when(source.take(anyMap())).thenReturn(ids.stream().map(id -> new RuleIndex.Member(id,country("US"))).toList());
        when(holds.identities("g",ids,10)).thenReturn(candidates);
        var projections=new java.util.LinkedHashMap<String,RuleIndex.Projection>();
        ids.forEach(id -> projections.put(id,country("US")));
        when(source.snapshot(ids)).thenReturn(projections);
        assertEquals(10,index.refill(List.of(us,either),100,holds));
        verify(source,times(1)).take(anyMap());
        assertEquals(10,index.take(List.of(either)).get(either).size());
        assertTrue(index.take(List.of(us)).get(us).isEmpty());
    }

    @Test void membershipIsRecheckedAfterHoldAndFailedProjectionLeavesNoStock() {
        var target=countries(1,"US"); var criteria=index.criteria(target);
        when(source.take(Map.of(criteria,1))).thenReturn(List.of(new RuleIndex.Member("w",country("US"))));
        when(holds.identities("g",List.of("w"),1)).thenReturn(List.of(new HeldCandidate("w",123,6000)));
        when(source.snapshot(List.of("w"))).thenReturn(Map.of("w",country("CN")));
        assertEquals(0,index.refill(List.of(target),1,holds));
        var order=inOrder(source,holds);
        order.verify(source).take(anyMap()); order.verify(holds).identities("g",List.of("w"),1);
        order.verify(source).snapshot(List.of("w"));
        when(source.snapshot(List.of("w"))).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class,() -> index.refill(List.of(target),1,holds));
        assertTrue(index.take(List.of(target)).get(target).isEmpty());
    }

    @Test void manyQueryTargetsShareOneSupplyHoldAndPostHoldBatch() {
        var targets=IntStream.range(0,100).mapToObj(i -> countries(1,
                ""+(char)('A'+i/26)+(char)('A'+i%26))).toList();
        var members=IntStream.range(0,100).mapToObj(i -> new RuleIndex.Member("w"+i,
                country(targets.get(i).query().get("worker.country").getFirst()))).toList();
        var candidates=IntStream.range(0,100).mapToObj(i -> new HeldCandidate("w"+i,100+i,6000)).toList();
        var projections=new java.util.LinkedHashMap<String,RuleIndex.Projection>();
        members.forEach(member -> projections.put(member.workerId(),member.projection()));
        when(source.take(anyMap())).thenAnswer(call -> {
            Map<?,Integer> limits=call.getArgument(0);
            assertEquals(100,limits.size()); assertTrue(limits.values().stream().allMatch(n -> n==1));
            return members;
        });
        when(holds.identities("g",List.copyOf(projections.keySet()),100)).thenReturn(candidates);
        when(source.snapshot(List.copyOf(projections.keySet()))).thenReturn(projections);
        assertEquals(100,index.refill(targets,100,holds));
        verify(source).take(anyMap()); verify(source).snapshot(anyList());
        verify(holds).identities(anyString(),anyList(),eq(100));
        verifyNoMoreInteractions(source,holds);
    }

    @Test void saturatedConstrainedTargetCannotTakeTheWholeRefillBudgetFromItsPeer() {
        var us=countries(100,"US"); var cn=countries(100,"CN");
        when(source.take(anyMap())).thenAnswer(call -> {
            assertEquals(Map.of(index.criteria(us),50,index.criteria(cn),50),call.getArgument(0));
            return List.of();
        });
        assertEquals(0,index.refill(List.of(us,cn),100,holds));
        verifyNoInteractions(holds);
    }

    @Test void concurrentConsumersDeliverEachFenceOnceAndExpiryNeedsNoIo() throws Exception {
        populate(100,"US"); var query=countries(100,"US");
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(() -> index.take(List.of(query)).get(query));
            var b=executor.submit(() -> index.take(List.of(query)).get(query));
            var all=new ArrayList<>(a.get()); all.addAll(b.get());
            assertEquals(100,all.size()); assertEquals(100,all.stream().map(HeldCandidate::workerId).distinct().count());
        }
        populate(10,"US"); clock.set(6000);
        assertTrue(index.take(List.of(query)).get(query).isEmpty());
        verifyNoInteractions(source,holds);
    }

    @Test void physicalCapacityIsSharedAndBoundedAcrossEligibilities() {
        var entries=IntStream.range(0,1000).mapToObj(i -> new SharedEligibilityInventory.Entry(
                new HeldCandidate("w"+i,1,6000),null)).toList();
        for (int i=0;i<10;i++) assertEquals(1000,stock.add(new SharedEligibilityInventory.Scope("g","r"+i),entries));
        assertEquals(0,stock.room(scope));
        clock.set(6000);
        assertEquals(1000,stock.room(scope));
        for (int i=0;i<100;i++) stock.add(new SharedEligibilityInventory.Scope("g","r"+i),
                List.of(new SharedEligibilityInventory.Entry(new HeldCandidate("w",1,9000),null)));
        assertEquals(0,stock.room(scope));
    }

    @Test void boundedExplicitIdentityPagesRotatePastOccupiedPrefixes() {
        var defaults=new SharedEligibility(stock,scope,null,null,0);
        var a=IntStream.range(0,100).mapToObj(i -> "a"+String.format("%02d",i)).toList();
        var b=IntStream.range(0,100).mapToObj(i -> "b"+String.format("%02d",i)).toList();
        var targets=List.of(new EligibilityQuery(Map.of("workerId",a),1),
                new EligibilityQuery(Map.of("workerId",b),1));
        assertEquals(0,defaults.refill(targets,100,holds));
        assertEquals(0,new SharedEligibility(stock,scope,null,null,1).refill(targets,100,holds));
        org.mockito.ArgumentCaptor<List<String>> pages=org.mockito.ArgumentCaptor.forClass(List.class);
        verify(holds,times(2)).identities(eq("g"),pages.capture(),eq(2));
        assertEquals(100,pages.getAllValues().get(0).size());
        assertEquals(100,pages.getAllValues().get(1).size());
        var seen=new java.util.HashSet<>(pages.getAllValues().get(0));
        seen.addAll(pages.getAllValues().get(1));
        assertEquals(200,seen.size());
        verifyNoInteractions(source);
    }

    @Test void defaultIdentityRequiresNoFactsAndKeepsExplicitIdentitySemantics() {
        var defaults=new SharedEligibility(stock,scope,null,null,0);
        var query=new EligibilityQuery(Map.of("workerId",List.of("busy","ready")),1);
        when(holds.identities("g",List.of("busy","ready"),1))
                .thenReturn(List.of(new HeldCandidate("ready",7,6000)));
        assertEquals(1,defaults.refill(List.of(query),100,holds));
        assertEquals("ready",defaults.take(List.of(query)).get(query).getFirst().workerId());
        verifyNoInteractions(source);
    }
}
