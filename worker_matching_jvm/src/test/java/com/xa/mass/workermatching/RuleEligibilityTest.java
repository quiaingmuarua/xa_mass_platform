package com.xa.mass.workermatching;

import com.xa.mass.workermatching.pool.CandidateBudget;
import com.xa.mass.workermatching.pool.CandidatePool;

import com.xa.mass.workermatching.storage.FactsIndexStore;

import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.kernel.assignment.WorkerMatching.HeldCandidate;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.workermatching.refill.AnyPoolPolicy;
import com.xa.mass.workermatching.refill.PoolMaintenance;

import com.xa.mass.workermatching.pool.CandidatePool.Selection;
import static com.xa.mass.workermatching.pool.CandidatePool.*;
import io.lettuce.core.RedisClient;
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
    final FactsIndexStore storage=new FactsIndexStore(mock(RedisClient.class), new RedisKeyspace("test_rule_stock"), Map.of());
    final TestPoolPolicy rule=new TestPoolPolicy();
    static final EligibilityQuery ANY=EligibilityQuery.parse(Map.of());
    @AfterEach void close() { storage.close(); }
    final class TestPoolPolicy extends com.xa.mass.workermatching.refill.PoolMaintenance<String> {
        Map<String,String> current=Map.of();
        int reads, evaluations;
        boolean fail, foreign;
        Runnable beforeRead=()->{};
        Runnable beforeMatch=()->{};
        List<String> readIds=List.of();
        final CandidatePool stock;
        TestPoolPolicy() { this(new CandidatePool(RuleEligibilityTest.this.clock::get, budget)); }
        TestPoolPolicy(CandidatePool stock) { super(RuleEligibilityTest.this.clock::get,stock);this.stock=stock; }
        QueryFunction functions() {
            return new QueryFunction() {
                public Object normalizeInput(String group, Object input) { return normalizeLocalInput(group, input); }
                public Map<String, WorkerCandidate> apply(String group, Map<String, Object> inputs) {
                    var grouped = new LinkedHashMap<Selection, List<String>>();
                    inputs.forEach((id, input) -> grouped.computeIfAbsent(select(group, input), ignored -> new ArrayList<>()).add(id));
                    var limits = new LinkedHashMap<Selection, Integer>();
                    grouped.forEach((selection, ids) -> limits.put(selection, ids.size()));
                    var taken = stock.take(group, limits);
                    var assigned = new HashMap<String, WorkerCandidate>();
                    grouped.forEach((selection, ids) -> {
                        var candidates = taken.get(selection);
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
        @Override protected Selection target(String group,EligibilityQuery query) {
            return query.query().isEmpty() ? all() : range("pool",query.query().get("pool"));
        }
        protected Object normalizeLocalInput(String group,Object input) {
            return normalize(group,EligibilityQuery.parse((Map<?,?>)input)).query();
        }
        protected Selection select(String group,Object input) {
            return target(group,EligibilityQuery.parse((Map<?,?>)input));
        }
        @Override protected Map<String,String> memberships(String group,String id,String pool) {
            assertFalse(Thread.holdsLock(this)); beforeMatch.run(); evaluations++;
            return pool==null ? null : Map.of("pool",pool);
        }
        @Override protected Map<String,String> readQualifications(String group,List<String> ids) {
            assertFalse(Thread.holdsLock(this)); beforeRead.run(); reads++; readIds=List.copyOf(ids);
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
    static WorkerCandidate candidate(HeldCandidate held) { return new WorkerCandidate(held.workerId(),held.score()); }
    List<HeldCandidate> offers(int start,int count,String value) {
        var facts=new HashMap<>(rule.current);
        var held=new ArrayList<HeldCandidate>();
        for(int i=start;i<start+count;i++) { facts.put("w"+i,value); held.add(new HeldCandidate("w"+i,100+i,6000)); }
        rule.current=Map.copyOf(facts); return List.copyOf(held);
    }
    void populate(int count) {
        for(int i=0;i<count;i+=100)rule.refill("g",targets(List.of(pools(count,"US"))),offers(i,Math.min(100,count-i),"US"),100);
    }
    @Test void fullTargetsAndTakeDoNotReadFactsAndResultsAreImmutable() {
        populate(20); int reads=rule.reads;
        var targets=List.of(pools(10,"US"),pools(20,"US","CN"));
        var deficits=rule.deficits("g",targets(targets));
        assertEquals(List.of(0,0),new ArrayList<>(deficits.values()));
        assertTrue(rule.refill("g",targets(targets),offers(50,1,"US"),100).isEmpty());
        var taken=rule.functions().apply("g",Map.of("message",Map.of()));
        assertEquals(1,taken.size()); assertEquals(reads,rule.reads);
        assertThrows(UnsupportedOperationException.class,()->deficits.clear());
        assertThrows(UnsupportedOperationException.class,()->taken.clear());
    }
    @Test void overlappingTargetsShareOneReadAndOriginalFences() {
        var offered=offers(0,10,"US");
        var admitted=rule.refill("g",targets(List.of(pools(10,"US"),pools(10,"US","CN"))),offered,100);
        assertEquals(10,admitted.size()); assertEquals(1,rule.reads);
        var taken=consume(rule,"g",Map.of(),100);
        assertEquals(offered.stream().map(RuleEligibilityTest::candidate).toList(),taken);
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
        var catalog = new RedisWorkerMatchingCatalog(storage, budget, Map.of("pool", rule.stock), clock::get,
                Map.of("pool", rule), Map.of("pool", rule.functions()),
                Map.of("g", new MatchingGroup(Set.of("pool"), Set.of("pool"))));
        var oversized = new LinkedHashMap<String, WorkerQuery>();
        for (int i = 0; i < 101; i++) oversized.put("m" + i, new WorkerQuery("pool", Map.of()));
        assertThrows(IllegalArgumentException.class,()->catalog.take("g",oversized));
        var inputs=new LinkedHashMap<String,WorkerQuery>();
        inputs.put("first",new WorkerQuery("pool",Map.of()));
        inputs.put("late",new WorkerQuery("pool",Map.of("unknown",List.of("x"))));
        assertThrows(IllegalArgumentException.class,()->catalog.take("g",inputs));
        var tooMany = new LinkedHashMap<EligibilityQuery,Integer>();
        for(int i=0;i<101;i++)tooMany.put(pools(1,"p"+i).target(),1);
        assertThrows(IllegalArgumentException.class,()->rule.deficits("g",tooMany));
        assertThrows(IllegalArgumentException.class,()->rule.refill("g",tooMany,List.of(),100));
        var offered=offers(3,1,"US");
        assertThrows(IllegalArgumentException.class,()->rule.refill("g",targets(List.of(pools(3,"US"))),List.of(offered.getFirst(),offered.getFirst()),100));
        assertThrows(IllegalArgumentException.class,()->rule.refill("g",targets(List.of(pools(3,"US"))),offered,101));
        assertThrows(IllegalArgumentException.class,()->rule.refill("g",Map.of(ANY,3),offered,-1));
        assertThrows(IllegalArgumentException.class,()->rule.refill("g",Map.of(ANY,3),offers(10,101,"US"),100));
        assertEquals(reads,rule.reads); assertEquals(2,consume(rule,"g",Map.of(),100).size());
    }
    @Test void constrainedTargetsPrecedeAnyAndMembershipsArePreparedOnce() {
        var offered=offers(0,2,"US");rule.current=Map.of("w0","CN","w1","US");
        assertEquals(List.of("w1"),rule.refill("g",targets(List.of(new RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 1),pools(1,"US"))),offered,1));
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
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
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
    private List<String> refillTogether(int target,List<HeldCandidate> first,List<HeldCandidate> second) throws Exception {
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        rule.beforeRead=()->{entered.countDown();await(release);};
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->rule.refill("g",Map.of(ANY,target),first,100));
            var b=executor.submit(()->rule.refill("g",Map.of(ANY,target),second,100));
            try { assertTrue(entered.await(5,TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            var accepted=new ArrayList<>(a.get(5,TimeUnit.SECONDS));accepted.addAll(b.get(5,TimeUnit.SECONDS));
            return accepted;
        } finally { rule.beforeRead=()->{}; }
    }
    @Test void groupIsolationAndExpiryDoNotRenewOrReplaceAnActiveFence() {
        var held=offers(0,1,"US").getFirst();
        rule.refill("g",targets(List.of(pools(2,"US"))),List.of(held),100);
        rule.refill("g",targets(List.of(pools(2,"US"))),List.of(new HeldCandidate("w0",999,9000)),100);
        assertTrue(consume(rule,"other",Map.of(),1).isEmpty());
        assertEquals(candidate(held),consume(rule,"g",Map.of(),1).getFirst());
        rule.refill("g",targets(List.of(pools(1,"US"))),List.of(held),100); clock.set(6000);
        assertTrue(consume(rule,"g",Map.of(),1).isEmpty());
        var replacement=new HeldCandidate("w0",999,9000);
        assertEquals(List.of("w0"),rule.refill("g",targets(List.of(pools(1,"US"))),List.of(replacement),100));
        assertEquals(candidate(replacement),consume(rule,"g",Map.of(),1).getFirst());
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5,TimeUnit.SECONDS)); }
        catch(InterruptedException error) { Thread.currentThread().interrupt();throw new AssertionError(error); }
    }
    @Test void timeSpentReadingConsumesOriginalLease() {
        rule.beforeRead=()->clock.set(6000);
        assertTrue(rule.refill("g",targets(List.of(pools(1,"US"))),offers(0,1,"US"),100).isEmpty());
        assertTrue(consume(rule,"g",Map.of(),1).isEmpty());
    }
    @Test void fullGroupDoesNotReadOrAcquireForAnotherTargetUntilExpiry() {
        populate(1000); int reads=rule.reads;
        var target=pools(10,"CN");
        assertEquals(0,rule.deficits("g",targets(List.of(target))).get(target.target()));
        assertTrue(rule.refill("g",targets(List.of(target)),offers(1000,1,"CN"),100).isEmpty());
        assertEquals(reads,rule.reads);
        clock.set(6000);
        assertEquals(10,rule.deficits("g",targets(List.of(target))).get(target.target()));
    }
    @Test void processAndResidentGroupCapsAreSharedAcrossRuleOwnedPools() {
        var defaultStock=new CandidatePool(clock::get, budget);
        var defaults=new AnyPoolPolicy(clock::get, defaultStock);
        var any=new RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 1000);
        for(int g=0;g<10;g++)for(int n=0;n<10;n++)
            assertEquals(100,defaults.refill("g"+g,targets(List.of(any)),offers(n*100,100,"US"),100).size());
        assertEquals(0,budget.available());
        assertTrue(rule.refill("g",targets(List.of(pools(1,"US"))),offers(0,1,"US"),100).isEmpty());
        clock.set(6000);
        assertEquals(0,budget.available(),"unrelated expiry requires global shortage-observation cleanup");
        defaultStock.expireAll(); rule.stock.expireAll(); assertEquals(10_000,budget.available());
        for(int g=0;g<100;g++)assertEquals(1,defaults.refill("g"+g,targets(List.of(any)),List.of(new HeldCandidate("w",1,9000)),100).size());
        assertTrue(defaults.refill("other",targets(List.of(any)),List.of(new HeldCandidate("w",1,9000)),100).isEmpty());
    }
    @Test void blockedSourceReadDoesNotHoldCandidateGate() throws Exception {
        populate(1);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        rule.beforeRead=()->{entered.countDown();try { assertTrue(release.await(5,TimeUnit.SECONDS)); }catch(InterruptedException e){throw new RuntimeException(e);}};
        var offered=offers(1,1,"US");
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
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
