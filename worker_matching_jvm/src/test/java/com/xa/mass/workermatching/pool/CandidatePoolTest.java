package com.xa.mass.workermatching.pool;

import com.xa.mass.workermatching.functions.AnyQueryFunction;
import com.xa.mass.workermatching.functions.CountryQueryFunction;
import com.xa.mass.workermatching.QueryFunction;


import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.xa.mass.workermatching.pool.CandidatePool.*;

class CandidatePoolTest {
    final AtomicLong clock=new AtomicLong(1000);
    final CandidateBudget budget=new CandidateBudget();
    final CandidatePool pool=new CandidatePool(clock::get,budget);
    CandidatePool.Admission row(String id,String country,long score) {
        return new CandidatePool.Admission(id, score, Map.of("country",country,"phone","number-"+id,"country-phone",country+"/number-"+id));
    }
    List<String> ids(List<WorkerCandidate> candidates) { return candidates.stream().map(WorkerCandidate::workerId).toList(); }

    @Test void duplicateFenceDoesNotExtendTtlAndReplacementCannotBeConsumedByOldSelection() {
        pool.admit("g", List.of(row("w", "CN", 17)));
        clock.set(60_000);
        assertEquals(List.of(), pool.admit("g", List.of(row("w", "CN", 17))));
        clock.set(61_000);
        assertTrue(pool.take("g", Map.of(all(), 1)).get(all()).isEmpty());
        pool.admit("g", List.of(row("w", "CN", 18)));
        var oldSelection = pool.select("g", Map.of(all(), 1));
        assertEquals(List.of("w"), pool.admit("g", List.of(row("w", "US", 19))));
        assertTrue(pool.commit("g", oldSelection).get(all()).isEmpty());
        assertTrue(pool.take("g", Map.of(range("country", List.of("CN")), 1)).get(range("country", List.of("CN"))).isEmpty());
        assertEquals(new WorkerCandidate("w", 19), pool.take("g", Map.of(all(), 1)).get(all()).getFirst());
    }

    @Test void fullProcessAndPoolStillReplaceExistingIdentitiesWithoutExtraCapacity() {
        for (int g = 0; g < 10; g++) {
            var rows = new ArrayList<Admission>();
            for (int i = 0; i < 1000; i++) rows.add(row("w" + i, "CN", 20));
            pool.admit("g" + g, rows);
        }
        assertEquals(0, budget.available());
        assertEquals(List.of("w0"), pool.admit("g0", List.of(row("new", "US", 21), row("w0", "US", 21))));
        assertEquals(0, budget.available());
        assertEquals(new WorkerCandidate("w0", 21), pool.take("g0", Map.of(range("country", List.of("US")), 1))
                .get(range("country", List.of("US"))).getFirst());
    }

    @Test void distinctFunctionsShareOneResourceAndAllMembershipsDisappearTogether() {
        Map<String, QueryFunction> functions=Map.of(
                "by-country",new CountryQueryFunction(pool), "any",new AnyQueryFunction(pool));
        pool.admit("g",List.of(row("a","CN",5000)));
        assertEquals(9999,budget.available());
        var first=functions.get("by-country").apply("g",Map.of("country-request",List.of("CN")));
        assertEquals("a",first.get("country-request").workerId());
        assertTrue(functions.get("any").apply("g",Map.of("any-request",Map.of())).isEmpty());
        assertEquals(0,pool.viewBuckets("g")); assertEquals(10_000,budget.available());
        pool.admit("g",List.of(row("b","CN",5000)));
        assertEquals("b",functions.get("any").apply("g",Map.of("any-request",Map.of())).get("any-request").workerId());
        assertTrue(functions.get("by-country").apply("g",Map.of("country-request",List.of("CN"))).isEmpty());
        assertEquals(10_000,budget.available());
    }

    @Test void countsAndSelectionVisitOnlyRequestedRangesAndExpiryHasIndependentAccounting() {
        var entries=new ArrayList<CandidatePool.Admission>();
        for(int i=0;i<1000;i++) entries.add(row("w"+i,i<990?"US":"CN",i==0?1100:5000));
        pool.admit("g",entries.subList(0,1)); clock.set(1100); pool.admit("g",entries.subList(1,entries.size())); var cn=range("country",List.of("CN"));
        assertEquals(10,pool.observe("g",List.of(cn),List.of()).counts().get(cn));
        assertEquals(1,pool.visits().countBuckets());
        assertEquals(List.of("w990"),ids(pool.take("g",Map.of(cn,1)).get(cn)));
        assertEquals(1,pool.visits().selectedEntries()); assertEquals(0,pool.visits().expiredEntries());
        clock.set(61000);
        assertEquals(9,pool.observe("g",List.of(cn),List.of()).counts().get(cn));
        assertEquals(1,pool.visits().expiredEntries()); assertEquals(1,pool.visits().selectedEntries());
        assertEquals(9002,budget.available());
        clock.set(61100); pool.expireAll();
        assertEquals(0,pool.viewBuckets("g")); assertEquals(10_000,budget.available());
    }

    @Test void multipleRangesMergeByAdmissionOrderAndOverlappingRequestsNeverDuplicate() {
        pool.admit("g",List.of(row("a","US",5000),row("b","CN",5000),row("c","US",5000)));
        var both=range("country",List.of("CN","US")); var limits=new LinkedHashMap<Selection,Integer>();
        limits.put(both,2); limits.put(all(),2);
        var result=pool.take("g",limits);
        assertEquals(List.of("a","b"),ids(result.get(both))); assertEquals(List.of("c"),ids(result.get(all())));
        assertEquals(0,pool.viewBuckets("g")); assertEquals(10_000,budget.available());
    }

    @Test void expiryAndReplacementSkipOnlyOriginalEntryWithoutReselecting() {
        pool.admit("g",List.of(row("a","CN",1100),row("b","US",5000)));
        var pending=pool.select("g",Map.of(all(),2));
        clock.set(1100); pool.admit("g",List.of(row("a","CN",5000)));
        assertEquals(List.of("b"),ids(pool.commit("g",pending).get(all())));
        assertEquals(List.of("a"),ids(pool.take("g",Map.of(all(),1)).get(all())));
        assertEquals(10_000,budget.available());
    }

    @Test void equalReinsertionAndUnrelatedChangesDoNotInvalidateOtherSelectedEntries() {
        pool.admit("g",List.of(row("a","CN",5000),row("b","US",5000)));
        var pending=pool.select("g",Map.of(all(),2));
        pool.take("g",Map.of(range("country",List.of("CN")),1)); pool.admit("g",List.of(row("a","CN",5000)));
        pool.admit("other",List.of(row("other","CN",5000)));
        assertEquals(List.of("b"),ids(pool.commit("g",pending).get(all())));
        assertEquals(List.of("a"),ids(pool.take("g",Map.of(all(),2)).get(all())));
        assertEquals(List.of("other"),ids(pool.take("other",Map.of(all(),2)).get(all())));
    }

    @Test void exactPhoneViewsDoNotAddCapacityAndDisappearWithTheirEntry() {
        pool.admit("g",List.of(row("a","CN",5000)));
        assertEquals(9999,budget.available()); assertEquals(3,pool.viewBuckets("g"));
        assertTrue(pool.take("g",Map.of(range("country-phone",List.of("US/number-a")),1)).values().stream().allMatch(List::isEmpty));
        assertEquals(List.of("a"),ids(pool.take("g",Map.of(range("phone",List.of("number-a")),1)).values().iterator().next()));
        assertEquals(0,pool.viewBuckets("g")); assertEquals(10_000,budget.available());
    }

    @Test void concurrentConsumersCannotCommitTheSameIdentity() throws Exception {
        var entries=new ArrayList<CandidatePool.Admission>();
        for(int i=0;i<100;i++)entries.add(row("w"+i,"CN",5000)); pool.admit("g",entries);
        var selected=pool.select("g",Map.of(all(),100));
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->pool.commit("g",selected).get(all()));
            var second=executor.submit(()->pool.commit("g",selected).get(all()));
            var all=new ArrayList<>(first.get(5,TimeUnit.SECONDS)); all.addAll(second.get(5,TimeUnit.SECONDS));
            assertEquals(100,all.size()); assertEquals(100,ids(all).stream().distinct().count());
        }
        assertEquals(10_000,budget.available());
    }
}
