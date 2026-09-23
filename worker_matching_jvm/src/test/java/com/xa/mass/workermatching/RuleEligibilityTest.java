package com.xa.mass.workermatching;

import com.xa.mass.workermatching.pool.CandidateBudget;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;


import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.workermatching.refill.AnyPoolPolicy;
import com.xa.mass.workermatching.refill.PoolMaintenance;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuleEligibilityTest {
    final CandidateBudget budget = new CandidateBudget();
    final AtomicLong clock=new AtomicLong(1000);
    final TestPoolPolicy rule=new TestPoolPolicy();
    static final EligibilityQuery ANY=EligibilityQuery.parse(Map.of());
    final class TestPoolPolicy extends com.xa.mass.workermatching.refill.PoolMaintenance<String> {
        Map<String,String> current=Map.of();
        int reads, evaluations;
        boolean fail, foreign;
        Runnable beforeRead=()->{};
        Runnable beforeMatch=()->{};
        List<String> readIds=List.of();
        final WorkerCandidatePool stock;
        TestPoolPolicy() { this(new WorkerCandidatePool(RuleEligibilityTest.this.clock::get, budget)); }
        TestPoolPolicy(WorkerCandidatePool stock) { super(stock);this.stock=stock; }
        QueryFunction functions() {
            return new QueryFunction() {
                public Object normalizeInput(String group, Object input) { return normalizeLocalInput(group, input); }
                public Map<String, WorkerCandidate> apply(String group, Map<String, Object> inputs) {
                    var grouped = new LinkedHashMap<EligibilityQuery, List<String>>();
                    inputs.forEach((id, input) -> grouped.computeIfAbsent(EligibilityQuery.parse((Map<?, ?>) input), ignored -> new ArrayList<>()).add(id));
                    var assigned = new HashMap<String, WorkerCandidate>();
                    grouped.forEach((query, ids) -> {
                        var candidates = query.query().isEmpty() ? stock.pollAnyBatch(group, ids.size())
                                : stock.pollBatch(group, Set.copyOf(query.query().get("pool")), ids.size());
                        for (int i = 0; i < candidates.size(); i++) assigned.put(ids.get(i), candidates.get(i));
                    });
                    var result = new LinkedHashMap<String, WorkerCandidate>();
                    inputs.keySet().forEach(id -> { if (assigned.containsKey(id)) result.put(id, assigned.get(id)); });
                    return Collections.unmodifiableMap(result);
                }
            };
        }
        @Override protected EligibilityQuery normalize(String group,EligibilityQuery input) {
            var expression = input.query();
            if(!Set.of("pool").containsAll(expression.keySet()))throw new IllegalArgumentException("unsupported query");
            @SuppressWarnings("unchecked") var q=(Map<String,List<String>>)expression;
            return new EligibilityQuery(q);
        }
        @Override protected Map<EligibilityQuery, Set<String>> matchingKeys(String group,
                Collection<EligibilityQuery> queries, Set<String> keys) {
            var result = new LinkedHashMap<EligibilityQuery, Set<String>>();
            for (var query : queries) {

                if (query.query().isEmpty()) result.put(query, keys);
                else {
                    var selected = new HashSet<>(query.query().get("pool"));
                    selected.retainAll(keys); result.put(query, selected);
                }
            }
            return result;
        }
        protected Object normalizeLocalInput(String group,Object input) {
            return normalize(group,EligibilityQuery.parse((Map<?,?>)input)).query();
        }

        @Override protected String bucketKey(String group,String id,String bucket) {
            assertFalse(Thread.holdsLock(stock)); beforeMatch.run(); evaluations++;
            return bucket;
        }
        @Override protected Map<String,String> readQualifications(String group,List<String> ids) {
            assertFalse(Thread.holdsLock(stock)); beforeRead.run(); reads++; readIds=List.copyOf(ids);
            if(fail)throw new IllegalStateException("read failed");
            if(foreign)return Map.of("outside","US");
            var found=new LinkedHashMap<String,String>();
            ids.forEach(id->{if(current.containsKey(id))found.put(id,current.get(id));});
            return found;
        }
    }
    static List<WorkerCandidate> consume(TestPoolPolicy rule,String group,Object input,int count) {
        var requests=new LinkedHashMap<String,Object>();
        for(int i=0;i<count;i++) requests.put("m"+i,input);
        return List.copyOf(rule.functions().apply(group,requests).values());
    }
    static Map<EligibilityQuery,Integer> targets(List<RefillTarget> targets) {
        var result=new LinkedHashMap<EligibilityQuery,Integer>();
        targets.forEach(target->result.put(target.target(),target.count()));
        return result;
    }
    static RefillTarget pools(int count,String... values) { return new RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of("pool",List.of(values))), count); }
    static WorkerCandidate candidate(Map.Entry<String, Long> held) { return new WorkerCandidate(held.getKey(),held.getValue()); }
    Map<String, Long> offers(int start,int count,String value) {
        var facts=new HashMap<>(rule.current);
        var held=new LinkedHashMap<String, Long>();
        for(int i=start;i<start+count;i++) { facts.put("w"+i,value); held.put("w"+i, (long) (100+i)); }
        rule.current=Map.copyOf(facts); return Collections.unmodifiableMap(held);
    }
    void populate(int count) {
        for(int i=0;i<count;i+=100)rule.refill("g",targets(List.of(pools(count,"US"))),offers(i,Math.min(100,count-i),"US"),100);
    }
    @Test void satisfiedWatermarksStillQualifyOffersWhileTakeDoesNotReadFacts() {
        populate(20); int reads=rule.reads;
        var targets=List.of(pools(10,"US"),pools(20,"US","CN"));
        var deficits=rule.deficits("g",targets(targets));
        assertEquals(List.of(0,0),new ArrayList<>(deficits.values()));
        assertEquals(List.of("w50"),rule.refill("g",targets(targets),offers(50,1,"US"),100));
        assertEquals(reads + 1,rule.reads);
        var taken=rule.functions().apply("g",Map.of("message",Map.of()));
        assertEquals(1,taken.size()); assertEquals(reads + 1,rule.reads);
        assertThrows(UnsupportedOperationException.class,()->deficits.clear());
        assertThrows(UnsupportedOperationException.class,()->taken.clear());
    }
    @Test void overlappingTargetsShareOneReadAndOriginalFences() {
        var offered=offers(0,10,"US");
        var admitted=rule.refill("g",targets(List.of(pools(10,"US"),pools(10,"US","CN"))),offered,100);
        assertEquals(10,admitted.size()); assertEquals(1,rule.reads);
        var taken=consume(rule,"g",Map.of(),100);
        assertEquals(offered.entrySet().stream().map(RuleEligibilityTest::candidate).toList(),taken);
        assertTrue(consume(rule,"g",Map.of(),100).isEmpty());
        assertThrows(UnsupportedOperationException.class,admitted::clear);
    }
    @Test void sourceReadAndMatchFailureNeverAdmitOrDiscoverWorkers() {
        var offered=offers(0,2,"CN");
        rule.current=Map.of("w0","CN","w1","US","better","US");
        rule.fail=true;
        assertThrows(IllegalStateException.class,()->rule.refill("g",targets(List.of(pools(2,"US"))),offered,100));
        rule.fail=false; rule.foreign=true;
        assertThrows(IllegalStateException.class,()->rule.refill("g",targets(List.of(pools(2,"US"))),offered,100));
        rule.foreign=false; rule.beforeMatch=()->{throw new IllegalStateException("match failed");};
        assertThrows(IllegalStateException.class,()->rule.refill("g",targets(List.of(pools(2,"US"))),offered,100));
        rule.beforeMatch=()->{};
        assertTrue(consume(rule,"g",Map.of(),100).isEmpty());
        assertEquals(List.of("w1"),rule.refill("g",targets(List.of(pools(2,"US"))),offered,100));
        assertEquals(List.of("w0","w1"),rule.readIds);
    }
    @Test void invalidInputDoesNotReadOrMutateExistingStock() {
        populate(2); int reads=rule.reads;
        var catalog = new DefaultWorkerMatchingCatalog(budget, Map.of("pool", rule.stock), clock::get,
                Map.of("pool", rule), Map.of("pool", rule.functions()),
                Map.of("g", new MatchingGroup(Set.of("pool"), Set.of("pool"), null)), List.of("pool"), Set.of());
        var oversized = new LinkedHashMap<String, WorkerQuery>();
        for (int i = 0; i < 1001; i++) oversized.put("m" + i, new WorkerQuery("pool", Map.of()));
        assertThrows(IllegalArgumentException.class,()->catalog.take("g",oversized));
        var inputs=new LinkedHashMap<String,WorkerQuery>();
        inputs.put("first",new WorkerQuery("pool",Map.of()));
        inputs.put("late",new WorkerQuery("pool",Map.of("unknown",List.of("x"))));
        assertThrows(IllegalArgumentException.class,()->catalog.take("g",inputs));
        var tooMany = new LinkedHashMap<EligibilityQuery,Integer>();
        for(int i=0;i<101;i++)tooMany.put(pools(1,"p"+i).target(),1);
        assertThrows(IllegalArgumentException.class,()->rule.deficits("g",tooMany));
        assertThrows(IllegalArgumentException.class,()->rule.refill("g", tooMany, Map.of(), 100));
        var offered=offers(3,1,"US");
        assertThrows(IllegalArgumentException.class,()->rule.refill("g",targets(List.of(pools(3,"US"))),Map.of("invalid", 0L),100));
        assertThrows(IllegalArgumentException.class,()->rule.refill("g",Map.of(ANY,3),offered,-1));
        assertEquals(reads,rule.reads); assertEquals(2,consume(rule,"g",Map.of(),100).size());
    }
    @Test void qualifiedOffersKeepInputOrderWithinTheBatchBudget() {
        var offered=offers(0,2,"US");rule.current=Map.of("w0","CN","w1","US");
        assertEquals(List.of("w0"),rule.refill("g",targets(List.of(new RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 1),pools(1,"US"))),offered,1));
        assertEquals(2,rule.evaluations);
    }
    @Test void hundredTargetsUseOneBoundedSourceRead() {
        var targets=IntStream.range(0,100).mapToObj(i->pools(1,"p"+i)).toList();
        var offered=offers(0,100,"unused");var facts=new HashMap<String,String>();
        for(int i=0;i<100;i++)facts.put("w"+i,"p"+i);rule.current=facts;
        assertEquals(100,rule.refill("g",targets(targets),offered,100).size());
        assertEquals(1,rule.reads); assertEquals(100,rule.readIds.size()); assertEquals(100,rule.evaluations);
    }
    @Test void concurrentTakesConsumeEachEntryOnce() throws Exception {
        populate(100);
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->consume(rule,"g",Map.of(),100));
            var b=executor.submit(()->consume(rule,"g",Map.of(),100));
            var all=new ArrayList<>(a.get(5,TimeUnit.SECONDS));all.addAll(b.get(5,TimeUnit.SECONDS));
            assertEquals(100,all.size());assertEquals(100,all.stream().map(h -> h.workerId()).distinct().count());
        }
    }
    @Test void concurrentRefillsMayExceedAnObservedTargetWithoutReplanning() throws Exception {
        var accepted=refillTogether(50,offers(0,40,"US"),offers(40,40,"US"));
        assertEquals(80,accepted.size(),"both calls retain the observed shortfall, not a target reservation");
        assertEquals(80,new HashSet<>(accepted).size());
        assertEquals(0,rule.deficits("g",Map.of(ANY,50)).get(ANY));
        assertEquals(80,consume(rule,"g",Map.of(),100).size());
        assertEquals(10_000,budget.available());
    }
    @Test void concurrentRefillsStillRespectCurrentHardCapacity() throws Exception {
        populate(950);
        var accepted=refillTogether(1000,offers(950,100,"US"),offers(1050,100,"US"));
        assertEquals(50,accepted.size());
        assertEquals(50,new HashSet<>(accepted).size());
        assertEquals(9000,budget.available());
        assertEquals(0,rule.deficits("g",Map.of(ANY,1000)).get(ANY));
    }
    private List<String> refillTogether(int target,Map<String, Long> first,Map<String, Long> second) throws Exception {
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        rule.beforeRead=()->{entered.countDown();await(release);};
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->rule.refill("g",Map.of(ANY,target),first,100));
            var b=executor.submit(()->rule.refill("g",Map.of(ANY,target),second,100));
            try { assertTrue(entered.await(5,TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            var accepted=new ArrayList<>(a.get(5,TimeUnit.SECONDS));accepted.addAll(b.get(5,TimeUnit.SECONDS));
            return accepted;
        } finally { rule.beforeRead=()->{}; }
    }
    @Test void groupIsolationIndependentEntriesAndLocalExpiry() {
        var held=offers(0,1,"US").entrySet().iterator().next();
        rule.refill("g",targets(List.of(pools(2,"US"))),Map.ofEntries(held),100);
        rule.refill("g",targets(List.of(pools(2,"US"))),Map.ofEntries(Map.entry("w0", (long) (999))),100);
        assertTrue(consume(rule,"other",Map.of(),1).isEmpty());
        assertEquals(List.of(candidate(held), new WorkerCandidate("w0",999)),consume(rule,"g",Map.of(),2));
        rule.refill("g",targets(List.of(pools(1,"US"))),Map.ofEntries(held),100); clock.set(61000);
        assertTrue(consume(rule,"g",Map.of(),1).isEmpty());
        var replacement=Map.entry("w0", (long) (999));
        assertEquals(List.of("w0"),rule.refill("g",targets(List.of(pools(1,"US"))),Map.ofEntries(replacement),100));
        assertEquals(candidate(replacement),consume(rule,"g",Map.of(),1).getFirst());
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5,TimeUnit.SECONDS)); }
        catch(InterruptedException error) { Thread.currentThread().interrupt();throw new AssertionError(error); }
    }
    @Test void localTtlStartsAfterQualification() {
        rule.beforeRead=()->clock.set(61000);
        assertEquals(List.of("w0"),rule.refill("g",targets(List.of(pools(1,"US"))),offers(0,1,"US"),100));
        clock.set(120_999);
        assertEquals(1,consume(rule,"g",Map.of(),1).size());
    }
    @Test void fullGroupStillQualifiesOffersAndReclaimsOnlyWhenRequested() {
        populate(1000); int reads=rule.reads;
        var target=pools(10,"CN");
        assertEquals(0,rule.deficits("g",targets(List.of(target))).get(target.target()));
        assertTrue(rule.refill("g",targets(List.of(target)),offers(1000,1,"CN"),100).isEmpty());
        assertEquals(reads + 1,rule.reads);
        rule.fail=true;
        assertThrows(IllegalStateException.class, () -> rule.refill("g",targets(List.of(target)),offers(1001,1,"CN"),100));
        rule.fail=false;
        clock.set(61000);
        assertEquals(0,rule.deficits("g",targets(List.of(target))).get(target.target()));
        rule.stock.discardExpired();
        assertEquals(10,rule.deficits("g",targets(List.of(target))).get(target.target()));
    }
    @Test void processAndResidentGroupCapsAreSharedAcrossRuleOwnedPools() {
        var defaultStock=new WorkerCandidatePool(clock::get, budget);
        var defaults=new AnyPoolPolicy(defaultStock);
        var any=new RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 1000);
        for(int g=0;g<10;g++)for(int n=0;n<10;n++)
            assertEquals(100,defaults.refill("g"+g,targets(List.of(any)),offers(n*100,100,"US"),100).size());
        assertEquals(0,budget.available());
        assertTrue(rule.refill("g",targets(List.of(pools(1,"US"))),offers(0,1,"US"),100).isEmpty());
        clock.set(61000);
        assertEquals(0,budget.available(),"unrelated expiry requires global shortage-observation cleanup");
        defaultStock.discardExpired(); rule.stock.discardExpired(); assertEquals(10_000,budget.available());
        for(int g=0;g<100;g++)assertEquals(1,defaults.refill("g"+g,targets(List.of(any)),Map.ofEntries(Map.entry("w", (long) (1))),100).size());
        assertTrue(defaults.refill("other",targets(List.of(any)),Map.ofEntries(Map.entry("w", (long) (1))),100).isEmpty());
    }
    @Test void blockedSourceReadDoesNotHoldCandidateGate() throws Exception {
        populate(1);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        rule.beforeRead=()->{entered.countDown();try { assertTrue(release.await(5,TimeUnit.SECONDS)); }catch(InterruptedException e){throw new RuntimeException(e);}};
        var offered=offers(1,1,"US");
        try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var refill=executor.submit(()->rule.refill("g",targets(List.of(pools(2,"US"))),offered,100));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            try { assertEquals(1,executor.submit(()->consume(rule,"g",Map.of(),1).size()).get(2,TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            assertEquals(1,refill.get(5,TimeUnit.SECONDS).size());
        }
    }
    @Test void commonQueryValuesRetainOrderAndDuplicatesUntilRuleNormalizes() {
        var values=List.of("z","a","z");
        assertEquals(values,new EligibilityQuery(Map.of("custom",values)).query().get("custom"));
    }
}
