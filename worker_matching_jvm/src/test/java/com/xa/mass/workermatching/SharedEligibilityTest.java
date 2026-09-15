package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex.HeldCandidate;
import com.xa.mass.workermatching.rules.DefaultRuleHandler;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedEligibilityTest {
    final AtomicLong clock=new AtomicLong(1000);
    final SharedEligibilityInventory stock=new SharedEligibilityInventory(clock::get);
    final SharedEligibilityInventory.Scope scope=new SharedEligibilityInventory.Scope("g","test.pool");
    final TestBound rule=new TestBound();
    final SharedEligibility index=new SharedEligibility(stock,scope,rule);
    final class TestBound implements RuleHandler.Bound {
        Map<String,RuleHandler.Member> current=Map.of();
        int snapshots, evaluations;
        boolean fail,foreign;
        List<String> readIds=List.of();
        public EligibilityQuery normalize(Map<String,?> expression,int count) {
            @SuppressWarnings("unchecked") var query=(Map<String,List<String>>)expression;
            return new EligibilityQuery(query,count);
        }
        public RuleHandler.Query compile(EligibilityQuery query) {
            return member->{
                assertFalse(Thread.holdsLock(stock),"Rule work must stay outside inventory locks");
                evaluations++;
                return member.projection() instanceof String pool &&
                        (query.query().isEmpty() || query.query().get("pool").contains(pool));
            };
        }
        public Map<String,RuleHandler.Member> snapshot(List<String> ids) {
            assertFalse(Thread.holdsLock(stock));snapshots++;readIds=List.copyOf(ids);
            if(fail)throw new IllegalStateException("projection unavailable");
            if(foreign)return Map.of("outside",new RuleHandler.Member("outside","US"));
            var found=new LinkedHashMap<String,RuleHandler.Member>();
            ids.forEach(id->{if(current.containsKey(id))found.put(id,current.get(id));});return found;
        }
    }
    static EligibilityQuery pools(int count,String... values) { return new EligibilityQuery(Map.of("pool",List.of(values)),count); }
    void populate(int size,String pool) {
        stock.add(scope,IntStream.range(0,size).mapToObj(i->new SharedEligibilityInventory.Entry(
                new HeldCandidate("w"+i,100+i,6000),pool)).toList());
    }
    List<String> offered(int count,String pool) {
        var current=new LinkedHashMap<String,RuleHandler.Member>();
        for(int i=0;i<count;i++)current.put("w"+i,new RuleHandler.Member("w"+i,pool));rule.current=current;
        return IntStream.range(0,count).mapToObj(i->"w"+i).toList();
    }
    List<RuleHandler.Member> select(List<EligibilityQuery> queries,List<String> offered) {
        return index.select(index.compile(queries),offered,100);
    }
    void commit(List<RuleHandler.Member> entries) {
        stock.add(scope,entries.stream().map(e->new SharedEligibilityInventory.Entry(
                new HeldCandidate(e.workerId(),1100,6000),e.projection())).toList());
    }
    SharedEligibility defaults() {
        return new SharedEligibility(stock,new SharedEligibilityInventory.Scope("g","worker.default"),
                new DefaultRuleHandler().bind(()->{throw new AssertionError("facts read");},"index",Set.of()));
    }
    @Test void fullTargetsAndTakeNeedNoProjectionRead() {
        populate(20,"US");var targets=List.of(pools(10,"US"),pools(20,"US","CN"));
        assertEquals(List.of(0,0),new ArrayList<>(index.deficits(index.compile(targets)).values()));
        assertTrue(select(targets,offered(1,"US")).isEmpty());
        var query=pools(1,"CN","US");assertEquals(1,index.take(List.of(query)).get(query).size());assertEquals(0,rule.snapshots);
    }
    @Test void overlappingQueriesShareOneProjectionAndPlansStayInvisibleUntilAdmission() {
        var us=pools(10,"US");var either=pools(10,"US","CN");
        var selected=select(List.of(us,either),offered(10,"US"));
        assertEquals(10,selected.size());assertEquals(1,rule.snapshots);
        assertTrue(index.take(List.of(either)).get(either).isEmpty());
        commit(selected);
        var taken=index.take(List.of(either)).get(either);
        assertEquals(10,taken.size());assertTrue(taken.stream().allMatch(c->c.score()>=1100));
        assertTrue(index.take(List.of(us)).get(us).isEmpty());
    }
    @Test void currentProjectionCanRejectTheIssuedBatchWithoutFindingAnotherWorker() {
        var offered=offered(1,"CN");
        var current=new LinkedHashMap<>(rule.current);current.put("better",new RuleHandler.Member("better","US"));rule.current=current;
        var query=pools(1,"US");assertTrue(select(List.of(query),offered).isEmpty());assertEquals(List.of("w0"),rule.readIds);
        rule.fail=true;assertThrows(IllegalStateException.class,()->select(List.of(query),offered));
        assertTrue(index.take(List.of(query)).get(query).isEmpty());
        rule.fail=false;rule.foreign=true;assertThrows(IllegalStateException.class,()->select(List.of(query),offered));
    }
    @Test void hundredQueriesShareOneBoundedProjectionRead() {
        var targets=IntStream.range(0,100).mapToObj(i->pools(1,"p"+i)).toList();
        var offered=offered(100,"unused");var current=new LinkedHashMap<String,RuleHandler.Member>();
        for(int i=0;i<100;i++)current.put("w"+i,new RuleHandler.Member("w"+i,"p"+i));rule.current=current;
        assertEquals(100,select(targets,offered).size());assertEquals(1,rule.snapshots);assertEquals(100,rule.readIds.size());
    }
    @Test void constrainedTargetsGetAdmissionRoomBeforeAny() {
        var offered=offered(2,"US");rule.current=Map.of("w0",new RuleHandler.Member("w0","CN"),"w1",new RuleHandler.Member("w1","US"));
        var selected=index.select(index.compile(List.of(new EligibilityQuery(Map.of(),1),pools(1,"US"))),offered,1);
        assertEquals(List.of("w1"),selected.stream().map(RuleHandler.Member::workerId).toList());
    }
    @Test void concurrentTakesDeliverEachObservedEntryAtMostOnce() throws Exception {
        populate(100,"US");var query=pools(100,"US");
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->index.take(List.of(query)).get(query));
            var b=executor.submit(()->index.take(List.of(query)).get(query));
            var all=new ArrayList<>(a.get());all.addAll(b.get());
            assertEquals(100,all.size());assertEquals(100,all.stream().map(HeldCandidate::workerId).distinct().count());
        }
        populate(10,"US");clock.set(6000);assertTrue(index.take(List.of(query)).get(query).isEmpty());
    }
    @Test void replacementCannotBeConsumedByAnOldInventorySnapshot() {
        populate(1,"US");var observed=stock.snapshot(scope).getFirst();clock.set(6000);
        var replacement=new SharedEligibilityInventory.Entry(new HeldCandidate("w0",900,9000),"US");
        stock.add(scope,List.of(replacement));var query=pools(1,"US");
        assertTrue(stock.take(scope,Map.of(query,List.of(observed))).get(query).isEmpty());assertEquals(List.of(replacement),stock.snapshot(scope));
    }
    @Test void physicalCapacityIsSharedAndBoundedAcrossEligibilities() {
        var entries=IntStream.range(0,1000).mapToObj(i->new SharedEligibilityInventory.Entry(new HeldCandidate("w"+i,1,6000),null)).toList();
        for(int i=0;i<10;i++)assertEquals(1000,stock.add(new SharedEligibilityInventory.Scope("g","r"+i),entries));
        assertEquals(0,stock.room(scope));clock.set(6000);assertEquals(0,stock.room(scope));
        stock.expireAll();assertEquals(1000,stock.room(scope));
        for(int i=0;i<100;i++)stock.add(new SharedEligibilityInventory.Scope("g","r"+i),List.of(new SharedEligibilityInventory.Entry(new HeldCandidate("w",1,9000),null)));
        assertEquals(0,stock.room(scope));
    }
    @Test void finiteIdentityTargetsFilterOnlyTheBatchAndSaturateAtTheUniqueIds() {
        var defaults=defaults();var query=new EligibilityQuery(Map.of("workerId",List.of("a","b","a")),100);
        assertTrue(defaults.select(defaults.compile(List.of(query)),List.of("outside"),100).isEmpty());
        var entries=defaults.select(defaults.compile(List.of(query)),List.of("a","b"),100);
        assertEquals(2,entries.size());
        stock.add(new SharedEligibilityInventory.Scope("g","worker.default"),entries.stream().map(e->new SharedEligibilityInventory.Entry(
                new HeldCandidate(e.workerId(),90,6000),null)).toList());
        assertEquals(0,defaults.deficits(defaults.compile(List.of(query))).get(query));assertEquals(2,defaults.take(List.of(query)).get(query).size());
    }
    @Test void commonQueryStructurePreservesOrderedAndDuplicateRuleParameters() {
        var values=List.of("z","a","z");assertEquals(values,new EligibilityQuery(Map.of("custom",values),3).query().get("custom"));
    }

    @Test void admissionCountsDoNotRematchPreviouslySelectedWorkers() {
        var offered=offered(100,"US");
        assertEquals(100,select(List.of(new EligibilityQuery(Map.of(),100)),offered).size());
        assertEquals(100,rule.evaluations);
        rule.evaluations=0;
        assertEquals(1,select(List.of(new EligibilityQuery(Map.of(),1)),offered).size());
        assertEquals(1,rule.evaluations,"a small ANY deficit must not evaluate the unused offered identities");
        var overlapping=IntStream.range(0,100).mapToObj(i->pools(100,"US","p"+i)).toList();
        rule.evaluations=0;
        assertEquals(100,select(overlapping,offered).size());
        assertEquals(10_000,rule.evaluations,"each supplied projection/query pair is evaluated once");
    }

    @Test void incrementalCountsPreserveConstrainedAndOverlappingAdmissionOrder() {
        var random=new Random(7351);
        var countries=List.of("US","CN","GB");
        for(int round=0;round<60;round++) {
            var offered=offered(20,"US");
            var projections=new LinkedHashMap<String,RuleHandler.Member>();
            offered.forEach(h->projections.put(h,new RuleHandler.Member(h,countries.get(random.nextInt(3)))));
            rule.current=projections;
            var requests=List.of(pools(1+random.nextInt(10),"US"),pools(1+random.nextInt(10),"US","CN"),
                    pools(1+random.nextInt(10),"GB"),new EligibilityQuery(Map.of(),1+random.nextInt(20)));
            var queries=index.compile(requests);
            int budget=1+random.nextInt(20);
            var expected=new LinkedHashMap<String,RuleHandler.Member>();
            for(boolean any:List.of(false,true))for(var candidate:offered) {
                if(expected.size()==budget)break;
                var member=projections.get(candidate);
                if(expected.containsKey(candidate))continue;
                for(var query:queries.entrySet()) {
                    if(query.getKey().query().isEmpty()==any && query.getValue().matches(member)
                            && expected.values().stream().filter(query.getValue()::matches).count()<query.getKey().count()) {
                        expected.put(candidate,member);break;
                    }
                }
            }
            assertEquals(List.copyOf(expected.keySet()),index.select(queries,offered,budget).stream()
                    .map(RuleHandler.Member::workerId).toList());
        }
    }

    @Test void localCapacityAndDiagnosticsDoNotSweepUnrelatedExpiredStock() {
        var other=new SharedEligibilityInventory.Scope("other","rule");
        stock.add(other,List.of(new SharedEligibilityInventory.Entry(new HeldCandidate("old",1,2000),null)));
        clock.set(2000);
        assertEquals(9999,stock.availableCapacity());
        assertEquals(1000,stock.room(scope));
        assertTrue(stock.diagnostics().contains("resident=1"));
        stock.expireAll();
        assertEquals(10000,stock.availableCapacity());
        assertTrue(stock.diagnostics().contains("expiredUnused=1"));
        stock.add(scope,List.of(new SharedEligibilityInventory.Entry(new HeldCandidate("current",2,3000),"US")));
        var seen=stock.snapshot(scope).getFirst();clock.set(3000);
        assertTrue(stock.take(scope,Map.of(pools(1,"US"),List.of(seen))).get(pools(1,"US")).isEmpty());
    }
}
