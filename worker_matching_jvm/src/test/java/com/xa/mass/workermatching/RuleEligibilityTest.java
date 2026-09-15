package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex.HeldCandidate;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import com.xa.mass.workermatching.rules.*;
import io.lettuce.core.RedisClient;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuleEligibilityTest {
    final AtomicLong clock=new AtomicLong(1000);
    final RedisRuleStorage storage=new RedisRuleStorage(mock(RedisClient.class),new RedisKeyspace("test_rule_stock"),Map.of(),clock::get);
    final PoolRule rule=new PoolRule();
    static final TaskItemWorkerSelector ANY=TaskItemWorkerSelector.parse(Map.of());
    @AfterEach void close() { storage.close(); }
    final class PoolRule extends LocalCandidateRule<String> {
        Map<String,String> current=Map.of();
        int reads, evaluations;
        boolean fail, foreign;
        Runnable beforeRead=()->{};
        Runnable beforeMatch=()->{};
        List<String> readIds=List.of();
        PoolRule() { super(RuleEligibilityTest.this.storage); }
        @Override protected EligibilityQuery normalize(String group,Map<String,?> expression,int count,boolean selector) {
            if(!Set.of("pool").containsAll(expression.keySet()))throw new IllegalArgumentException("unsupported query");
            @SuppressWarnings("unchecked") var q=(Map<String,List<String>>)expression;
            return new EligibilityQuery(q,count);
        }
        @Override protected BiPredicate<String,String> predicate(String group,EligibilityQuery query) {
            return (id,pool)->{
                assertFalse(Thread.holdsLock(this),"matching must stay outside the state gate");
                beforeMatch.run(); evaluations++;
                return pool!=null && (query.query().isEmpty() || query.query().get("pool").contains(pool));
            };
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
    static EligibilityQuery pools(int count,String... values) { return new EligibilityQuery(Map.of("pool",List.of(values)),count); }
    List<HeldCandidate> offers(int start,int count,String value) {
        var facts=new HashMap<>(rule.current);
        var held=new ArrayList<HeldCandidate>();
        for(int i=start;i<start+count;i++) { facts.put("w"+i,value); held.add(new HeldCandidate("w"+i,100+i,6000)); }
        rule.current=Map.copyOf(facts); return List.copyOf(held);
    }
    void populate(int count) {
        for(int i=0;i<count;i+=100)rule.refill("g",List.of(pools(count,"US")),offers(i,Math.min(100,count-i),"US"),100);
    }
    @Test void fullTargetsAndTakeDoNotReadFactsAndResultsAreImmutable() {
        populate(20); int reads=rule.reads;
        var targets=List.of(pools(10,"US"),pools(20,"US","CN"));
        var deficits=rule.deficits("g",targets);
        assertEquals(List.of(0,0),new ArrayList<>(deficits.values()));
        assertTrue(rule.refill("g",targets,offers(50,1,"US"),100).isEmpty());
        var taken=rule.take("g",Map.of(ANY,1));
        assertEquals(1,taken.get(ANY).size()); assertEquals(reads,rule.reads);
        assertThrows(UnsupportedOperationException.class,()->deficits.clear());
        assertThrows(UnsupportedOperationException.class,()->taken.clear());
        assertThrows(UnsupportedOperationException.class,()->taken.get(ANY).clear());
    }
    @Test void overlappingTargetsShareOneReadAndOriginalFences() {
        var offered=offers(0,10,"US");
        var admitted=rule.refill("g",List.of(pools(10,"US"),pools(10,"US","CN")),offered,100);
        assertEquals(10,admitted.size()); assertEquals(1,rule.reads);
        var taken=rule.take("g",Map.of(ANY,100)).get(ANY);
        assertEquals(offered,taken);
        for(int i=0;i<taken.size();i++)assertSame(offered.get(i),taken.get(i));
        assertTrue(rule.take("g",Map.of(ANY,100)).get(ANY).isEmpty());
        assertThrows(UnsupportedOperationException.class,admitted::clear);
    }
    @Test void sourceReadAndMatchFailureNeverAdmitOrDiscoverWorkers() {
        var offered=offers(0,2,"CN");
        rule.current=Map.of("w0","CN","w1","US","better","US");
        rule.fail=true;
        assertThrows(IllegalStateException.class,()->rule.refill("g",List.of(pools(2,"US")),offered,100));
        rule.fail=false; rule.foreign=true;
        assertThrows(IllegalStateException.class,()->rule.refill("g",List.of(pools(2,"US")),offered,100));
        rule.foreign=false; rule.beforeMatch=()->{throw new IllegalStateException("match failed");};
        assertThrows(IllegalStateException.class,()->rule.refill("g",List.of(pools(2,"US")),offered,100));
        rule.beforeMatch=()->{};
        assertTrue(rule.take("g",Map.of(ANY,100)).get(ANY).isEmpty());
        assertEquals(List.of("w1"),rule.refill("g",List.of(pools(2,"US")),offered,100));
        assertEquals(List.of("w0","w1"),rule.readIds);
    }
    @Test void invalidInputDoesNotReadOrMutateExistingStock() {
        populate(2); int reads=rule.reads;
        assertThrows(IllegalArgumentException.class,()->rule.take("g",Map.of(ANY,101)));
        var limits=new LinkedHashMap<TaskItemWorkerSelector,Integer>();
        limits.put(ANY,1); limits.put(TaskItemWorkerSelector.parse(Map.of("unknown",List.of("x"))),1);
        assertThrows(IllegalArgumentException.class,()->rule.take("g",limits));
        var offered=offers(3,1,"US");
        assertThrows(IllegalArgumentException.class,()->rule.refill("g",List.of(pools(3,"US")),List.of(offered.getFirst(),offered.getFirst()),100));
        assertThrows(IllegalArgumentException.class,()->rule.refill("g",List.of(pools(3,"US")),offered,101));
        assertEquals(reads,rule.reads); assertEquals(2,rule.take("g",Map.of(ANY,100)).get(ANY).size());
    }
    @Test void constrainedTargetsPrecedeAnyAndEachPairIsEvaluatedOnce() {
        var offered=offers(0,2,"US");rule.current=Map.of("w0","CN","w1","US");
        assertEquals(List.of("w1"),rule.refill("g",List.of(new EligibilityQuery(Map.of(),1),pools(1,"US")),offered,1));
        assertEquals(4,rule.evaluations);
    }
    @Test void hundredTargetsUseOneBoundedSourceRead() {
        var targets=IntStream.range(0,100).mapToObj(i->pools(1,"p"+i)).toList();
        var offered=offers(0,100,"unused");var facts=new HashMap<String,String>();
        for(int i=0;i<100;i++)facts.put("w"+i,"p"+i);rule.current=facts;
        assertEquals(100,rule.refill("g",targets,offered,100).size());
        assertEquals(1,rule.reads); assertEquals(100,rule.readIds.size()); assertEquals(10_000,rule.evaluations);
    }
    @Test void concurrentTakesConsumeOnceAndConcurrentRefillsDoNotOverfill() throws Exception {
        populate(100);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->rule.take("g",Map.of(ANY,100)).get(ANY));
            var b=executor.submit(()->rule.take("g",Map.of(ANY,100)).get(ANY));
            var all=new ArrayList<>(a.get(5,TimeUnit.SECONDS));all.addAll(b.get(5,TimeUnit.SECONDS));
            assertEquals(100,all.size());assertEquals(100,all.stream().map(HeldCandidate::workerId).distinct().count());
            var first=offers(100,100,"US");var second=offers(200,100,"US");
            var c=executor.submit(()->rule.refill("g",List.of(pools(50,"US")),first,100));
            var d=executor.submit(()->rule.refill("g",List.of(pools(50,"US")),second,100));
            assertEquals(50,c.get(5,TimeUnit.SECONDS).size()+d.get(5,TimeUnit.SECONDS).size());
        }
    }
    @Test void groupIsolationAndExpiryDoNotRenewOrReplaceAnActiveFence() {
        var held=offers(0,1,"US").getFirst();
        rule.refill("g",List.of(pools(2,"US")),List.of(held),100);
        rule.refill("g",List.of(pools(2,"US")),List.of(new HeldCandidate("w0",999,9000)),100);
        assertTrue(rule.take("other",Map.of(ANY,1)).get(ANY).isEmpty());
        assertSame(held,rule.take("g",Map.of(ANY,1)).get(ANY).getFirst());
        rule.refill("g",List.of(pools(1,"US")),List.of(held),100); clock.set(6000);
        assertTrue(rule.take("g",Map.of(ANY,1)).get(ANY).isEmpty());
        var replacement=new HeldCandidate("w0",999,9000);
        assertEquals(List.of("w0"),rule.refill("g",List.of(pools(1,"US")),List.of(replacement),100));
        assertSame(replacement,rule.take("g",Map.of(ANY,1)).get(ANY).getFirst());
    }
    @Test void concurrentExpiryAndReplacementCannotReturnTheOldFence() throws Exception {
        populate(1);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var first=new java.util.concurrent.atomic.AtomicBoolean(true);
        rule.beforeMatch=()->{
            if(first.compareAndSet(true,false)) {
                entered.countDown();
                try { assertTrue(release.await(5,TimeUnit.SECONDS)); }catch(InterruptedException e){throw new RuntimeException(e);}
            }
        };
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var take=executor.submit(()->rule.take("g",Map.of(ANY,1)).get(ANY));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var replacement=new HeldCandidate("w0",999,9000);
            try {
                clock.set(6000);
                assertEquals(List.of("w0"),rule.refill("g",List.of(pools(1,"US")),List.of(replacement),100));
            } finally { release.countDown(); }
            assertSame(replacement,take.get(5,TimeUnit.SECONDS).getFirst());
            assertTrue(rule.take("g",Map.of(ANY,1)).get(ANY).isEmpty());
        }
    }
    @Test void timeSpentReadingConsumesOriginalLease() {
        rule.beforeRead=()->clock.set(6000);
        assertTrue(rule.refill("g",List.of(pools(1,"US")),offers(0,1,"US"),100).isEmpty());
        assertTrue(rule.take("g",Map.of(ANY,1)).get(ANY).isEmpty());
    }
    @Test void fullGroupDoesNotReadOrAcquireForAnotherTargetUntilExpiry() {
        populate(1000); int reads=rule.reads;
        var target=pools(10,"CN");
        assertEquals(0,rule.deficits("g",List.of(target)).get(target));
        assertTrue(rule.refill("g",List.of(target),offers(1000,1,"CN"),100).isEmpty());
        assertEquals(reads,rule.reads);
        clock.set(6000);
        assertEquals(10,rule.deficits("g",List.of(target)).get(target));
    }
    @Test void processAndResidentGroupCapsAreSharedAcrossRuleOwnedPools() {
        var defaults=new DefaultRuleHandler(storage,Map.of());
        var any=new EligibilityQuery(Map.of(),1000);
        for(int g=0;g<10;g++)for(int n=0;n<10;n++)
            assertEquals(100,defaults.refill("g"+g,List.of(any),offers(n*100,100,"US"),100).size());
        assertEquals(0,storage.availableCapacity());
        assertTrue(rule.refill("g",List.of(pools(1,"US")),offers(0,1,"US"),100).isEmpty());
        clock.set(6000);
        assertEquals(0,storage.availableCapacity(),"unrelated expiry is reclaimed only at preparation");
        storage.expireCandidates(); assertEquals(10_000,storage.availableCapacity());
        for(int g=0;g<100;g++)assertEquals(1,defaults.refill("g"+g,List.of(any),List.of(new HeldCandidate("w",1,9000)),100).size());
        assertTrue(defaults.refill("other",List.of(any),List.of(new HeldCandidate("w",1,9000)),100).isEmpty());
    }
    @Test void blockedSourceReadDoesNotHoldCandidateGate() throws Exception {
        populate(1);
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        rule.beforeRead=()->{entered.countDown();try { assertTrue(release.await(5,TimeUnit.SECONDS)); }catch(InterruptedException e){throw new RuntimeException(e);}};
        var offered=offers(1,1,"US");
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var refill=executor.submit(()->rule.refill("g",List.of(pools(2,"US")),offered,100));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            try { assertEquals(1,executor.submit(()->rule.take("g",Map.of(ANY,1)).get(ANY).size()).get(2,TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            assertEquals(1,refill.get(5,TimeUnit.SECONDS).size());
        }
    }
    @Test void commonQueryValuesRetainOrderAndDuplicatesUntilRuleNormalizes() {
        var values=List.of("z","a","z");
        assertEquals(values,new EligibilityQuery(Map.of("custom",values),3).query().get("custom"));
    }
}
