package com.xa.mass.workermatching.rules;

import com.xa.mass.kernel.assignment.WorkerMatching.HeldCandidate;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.xa.mass.workermatching.rules.PoolRule.*;

class CandidatePoolTest {
    final AtomicLong clock=new AtomicLong(1000);
    final CandidateBudget budget=new CandidateBudget();
    final CandidatePool pool=new CandidatePool(clock::get,budget);
    CandidatePool.Admission row(String id,String country,long deadline) {
        return new CandidatePool.Admission(new HeldCandidate(id,17,deadline),
                Map.of("country",country,"phone","number-"+id,"country-phone",country+"/number-"+id));
    }
    List<String> ids(List<WorkerCandidate> candidates) { return candidates.stream().map(WorkerCandidate::workerId).toList(); }

    @Test void countsAndSelectionVisitOnlyRequestedRangesAndExpiryHasIndependentAccounting() {
        var entries=new ArrayList<CandidatePool.Admission>();
        for(int i=0;i<1000;i++) entries.add(row("w"+i,i<990?"US":"CN",i==0?1100:5000));
        pool.admit("g",entries); var cn=range("country",List.of("CN"));
        assertEquals(10,pool.observe("g",List.of(cn),List.of()).counts().get(cn));
        assertEquals(1,pool.visits().countBuckets());
        assertEquals(List.of("w990"),ids(pool.take("g",Map.of(cn,1)).get(cn)));
        assertEquals(1,pool.visits().selectedEntries()); assertEquals(0,pool.visits().expiredEntries());
        clock.set(1100);
        assertEquals(9,pool.observe("g",List.of(cn),List.of()).counts().get(cn));
        assertEquals(1,pool.visits().expiredEntries()); assertEquals(1,pool.visits().selectedEntries());
        assertEquals(9002,budget.available());
        clock.set(5000); pool.expireAll();
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
        pool.take("g",Map.of(identities(List.of("a")),1)); pool.admit("g",List.of(row("a","CN",5000)));
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
