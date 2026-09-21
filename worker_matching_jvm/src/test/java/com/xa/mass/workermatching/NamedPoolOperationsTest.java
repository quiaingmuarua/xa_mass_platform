package com.xa.mass.workermatching;

import com.xa.mass.workermatching.functions.AnyQueryFunction;
import com.xa.mass.workermatching.pool.CandidateBudget;
import com.xa.mass.workermatching.pool.CandidatePool;

import com.xa.mass.workermatching.storage.FactsIndexStore;

import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.workermatching.refill.AnyPoolPolicy;
import com.xa.mass.workermatching.refill.PoolMaintenance;

import com.xa.mass.workermatching.pool.CandidatePool.Selection;
import static com.xa.mass.workermatching.pool.CandidatePool.*;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NamedPoolOperationsTest {
    final CandidateBudget budget = new CandidateBudget();
    final RedisClient client=mock(RedisClient.class);
    @SuppressWarnings("unchecked") final StatefulRedisConnection<String,String> connection=mock(StatefulRedisConnection.class);
    @SuppressWarnings("unchecked") final RedisCommands<String,String> redis=mock(RedisCommands.class);
    CountingRule rule;
    CountingRule failingRule;
    final java.util.concurrent.atomic.AtomicLong clock=new java.util.concurrent.atomic.AtomicLong(1000);
    final JsonMapper json=JsonMapper.builder().build();
    RedisWorkerMatchingCatalog catalog;
    FactsIndexStore storage;
    static final EligibilityQuery ANY=EligibilityQuery.parse(Map.of());

    @BeforeEach void setUp() {
        when(client.connect(StringCodec.UTF8)).thenReturn(connection);
        when(connection.isOpen()).thenReturn(true);
        when(connection.sync()).thenReturn(redis);
        storage=new FactsIndexStore(client, new RedisKeyspace("test_named_pool"), Map.of());
        rule=new CountingRule(clock::get,budget); failingRule=new CountingRule(clock::get,budget);
        var defaultStock=new CandidatePool(clock::get, budget);
        var defaults=new AnyPoolPolicy(defaultStock);
        catalog=new RedisWorkerMatchingCatalog(storage, budget, Map.of("any", defaultStock, "test.pool", rule.stock, "zz.fail", failingRule.stock), clock::get, Map.of("any",defaults,"test.pool",rule,"zz.fail",failingRule), Map.of("worker.any",new AnyQueryFunction(defaultStock),"test.pool",rule.functions(),"zz.fail",failingRule.functions()), configuredGroups(), List.of("any", "test.pool", "zz.fail"), Set.of());
    }
    private Map<String,MatchingGroup> configuredGroups() {
        var groups=new LinkedHashMap<String,MatchingGroup>();
        for(int i=0;i<100;i++) {
            groups.put("g"+i,new MatchingGroup(Set.of("any"),Set.of("worker.any")));
            groups.put("group"+i,new MatchingGroup(Set.of("any"),Set.of("worker.any")));
        }
        groups.put("g1",new MatchingGroup(Set.of("any","test.pool","zz.fail"),Set.of("worker.any","test.pool","zz.fail")));
        groups.put("g2",new MatchingGroup(Set.of("any","test.pool"),Set.of("worker.any","test.pool")));
        return groups;
    }
    @AfterEach void close() { catalog.close(); }

    @Test void equivalentAndInterleavedQueriesAreGroupedThenCorrelatedInInputOrder() {
        rule.facts.putAll(Map.of("a","US","b","CN","c","CN"));
        catalog.refill("g1",List.of(pool(3,"US","CN")),offer("a","b","c"));
        var requests=new LinkedHashMap<String,WorkerQuery>();
        requests.put("first",new WorkerQuery("test.pool",Map.of("pool",List.of("US","CN","US"))));
        requests.put("narrow",new WorkerQuery("test.pool",Map.of("pool",List.of("CN"))));
        requests.put("equivalent",new WorkerQuery("test.pool",Map.of("pool",List.of("CN","US"))));
        var result=catalog.take("g1",requests);
        assertEquals(List.of("first","narrow","equivalent"),List.copyOf(result.keySet()));
        assertEquals(List.of("a","c","b"),result.values().stream().map(WorkerCandidate::workerId).toList());
        assertThrows(UnsupportedOperationException.class,result::clear);
        requests.clear(); assertEquals(3,result.size()); verifyNoInteractions(redis);
    }

    @Test void candidateRequiresIdentityButLeavesFenceInterpretationToScoreOwner() {
        assertThrows(IllegalArgumentException.class, () -> new WorkerCandidate(" ", 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerCandidate(null, 10));
        assertEquals(0, new WorkerCandidate("hint", 0).expectedScore());
        assertEquals(-17, new WorkerCandidate("opaque", -17).expectedScore());
    }

    @Test void invalidWholeBatchLeavesStockUntouchedAndEmptyDoesNotTouchRule() {
        rule.facts.put("worker","US");
        assertEquals(1,catalog.refill("g1",List.of(pool(1,"US")),offer("worker")));
        int before=rule.normalizations;
        assertTrue(catalog.take("g1",Map.of()).isEmpty()); assertEquals(before,rule.normalizations);
        assertThrows(IllegalArgumentException.class,()->catalog.take(" ",Map.of()));
        assertThrows(NullPointerException.class,()->catalog.take("g1",null));
        var any=new WorkerQuery("test.pool",Map.of());
        var requests=new LinkedHashMap<String,WorkerQuery>(); requests.put("valid",any);
        for(var bad:List.of(new WorkerQuery("missing",Map.of()),new WorkerQuery("test.pool",Map.of("unsupported",List.of("x"))))) {
            requests.put("late",bad);
            assertThrows(IllegalArgumentException.class,()->catalog.take("g1",requests));
        }
        requests.put("late",null); assertThrows(NullPointerException.class,()->catalog.take("g1",requests));
        requests.remove("late"); requests.put(" ",any);
        assertThrows(IllegalArgumentException.class,()->catalog.take("g1",requests));
        requests.remove(" "); requests.put(null,any);
        assertThrows(IllegalArgumentException.class,()->catalog.take("g1",requests)); requests.remove(null);
        for(int i=0;i<100;i++)requests.put("m"+i,any);
        assertThrows(IllegalArgumentException.class,()->catalog.take("g1",requests));
        assertThrows(IllegalArgumentException.class,()->catalog.take("other",Map.of("m",any)));
        assertEquals("worker",catalog.take("g1",Map.of("valid",any)).get("valid").workerId());
        verifyNoInteractions(redis);
    }

    @Test void overlappingQueriesShortagesAndRepeatedMessageIdsHaveOnlyCallLocalMeaning() {
        var targets=List.of(pool(3,"US","CN"));
        rule.facts.putAll(Map.of("one","US","two","CN","three","CN"));
        var held=offer("one","two","three"); assertEquals(3,catalog.refill("g1",targets,held));
        var identity=new WorkerQuery("test.pool",Map.of("pool",List.of("US")));
        var any=new WorkerQuery("test.pool",Map.of());
        var requests=new LinkedHashMap<String,WorkerQuery>();
        requests.put("id-first",identity); requests.put("any-first",any); requests.put("id-again",identity);
        requests.put("any-next",any); requests.put("unfilled",any);
        var result=catalog.take("g1",requests);
        assertEquals(List.of("id-first","any-first","any-next"),List.copyOf(result.keySet()));
        assertEquals(held.entrySet().stream().map(NamedPoolOperationsTest::candidate).toList(),List.copyOf(result.values()));
        assertTrue(catalog.take("g1",requests).isEmpty());
        rule.facts.put("four","CN");
        assertEquals(1,catalog.refill("g1",targets,offer("four")));
        assertEquals("four",catalog.take("g1",Map.of("id-first",any)).get("id-first").workerId());
        verifyNoInteractions(redis);
    }

    @Test void oneHundredMessagesCanShareOneNormalizedQuery() {
        var held=new LinkedHashMap<String, Long>(); var requests=new LinkedHashMap<String,WorkerQuery>();
        for(int i=0;i<100;i++) { held.put("worker"+i, (long) (20+i));
            requests.put("message"+i,new WorkerQuery("worker.any",Map.of())); }
        assertEquals(100,catalog.refill("g1",List.of(new RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 100)),held));
        var result=catalog.take("g1",requests);
        assertEquals(List.copyOf(requests.keySet()),List.copyOf(result.keySet()));
        assertEquals(held.entrySet().stream().map(NamedPoolOperationsTest::candidate).toList(),List.copyOf(result.values()));
        verifyNoInteractions(redis);
    }

    @Test void resolvesTargetsWithoutTaskIdentityRedisOrInventoryChanges() {
        var expected = pool(8,"US");
        var resolved = catalog.normalizeRefill("g1", List.of(pool(3,"US","US"),expected));
        assertEquals(List.of(expected), resolved);
        assertThrows(UnsupportedOperationException.class, resolved::clear);
        assertEquals(List.of(),catalog.normalizeRefill("g1", List.of()));
        assertTrue(catalog.normalizeRefill("g1",List.of()).isEmpty());
        assertThrows(IllegalArgumentException.class,()->catalog.normalizeRefill("other",List.of(pool(1,"US"))));
        assertThrows(IllegalArgumentException.class,()->catalog.normalizeRefill("g1", Collections.nCopies(101,expected)));
        assertTrue(takeItems(catalog,"g1","test.pool",Map.of(),1).isEmpty());
        verifyNoInteractions(redis);
    }

    static RefillTarget pool(int count,String... values) {
        return new RefillTarget("test.pool", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of("pool",List.of(values))), count);
    }
    static WorkerCandidate candidate(Map.Entry<String, Long> held) { return new WorkerCandidate(held.getKey(),held.getValue()); }
    static Map<String, Long> offer(String... ids) {
        return Arrays.stream(ids).collect(java.util.stream.Collectors.toMap(id -> id, id -> 20L, (a,b) -> {throw new IllegalArgumentException("duplicate");}, LinkedHashMap::new));
    }

    @Test void namedOperationsMergeDeclarationsWithoutTaskRegistration() {
        rule.facts.putAll(Map.of("w1","US","w2","US","w3","US","w4","CN"));
        var targets=Map.of("g1",List.of(pool(1,"US"), pool(2,"US","US")),
                "g2",List.of(pool(1,"CN")));
        assertTrue(rule.observedTargets.isEmpty());
        clearInvocations(redis);
        var needed=catalog.observeRefillDeficits(targets);
        assertEquals(Map.of("g1",2,"g2",1),needed);
        assertThrows(UnsupportedOperationException.class,needed::clear);
        assertEquals(2,catalog.refill("g1",targets.get("g1"),offer("w1","w2","w3")));
        assertEquals(1,catalog.refill("g2",targets.get("g2"),offer("w4")));
        assertEquals(List.of("w1","w2"),takeItems(catalog,"g1","test.pool",Map.of(),2)
                .stream().map(h -> h.workerId()).toList());
        assertEquals(List.of("w4"),takeItems(catalog,"g2","test.pool",Map.of(),1)
                .stream().map(h -> h.workerId()).toList());
        verifyNoInteractions(redis);
    }

    @Test void endingDemandRetainsSharedStockWhileEmptyRoundsStillExpireEntries() {
        var supply = List.of(new RefillTarget("any", ANY, 2));
        assertEquals(2, catalog.refill("g1", supply, offer("first", "second")));
        assertTrue(catalog.observeRefillDeficits(Map.of()).isEmpty());
        var consumed = catalog.take("g1", Map.of("consumer", new WorkerQuery("worker.any", Map.of())));
        assertEquals("first", consumed.get("consumer").workerId());
        clock.set(61000);
        assertTrue(catalog.observeRefillDeficits(Map.of()).isEmpty());
        assertTrue(catalog.take("g1", Map.of("late", new WorkerQuery("worker.any", Map.of()))).isEmpty());
        verifyNoInteractions(redis);
    }

    @Test void deficitCountsMergeEquivalentTargetsAndPoolsInInputGroupOrder() {
        var targets=new LinkedHashMap<String,List<RefillTarget>>();
        targets.put("g2",List.of(new RefillTarget("any",ANY,5)));
        targets.put("g0",List.of());
        targets.put("g1",List.of(pool(1,"US"),pool(3,"US","US"),
                new RefillTarget("zz.fail",pool(4,"US").target(),4)));
        var observed=catalog.observeRefillDeficits(targets);
        assertEquals(Map.of("g2",5,"g1",7),observed);
        assertEquals(List.of("g2","g1"),List.copyOf(observed.keySet()));
        assertThrows(UnsupportedOperationException.class,()->observed.put("g2",9));
        assertEquals(1,catalog.refill("g2",targets.get("g2"),offer("w")));
        assertEquals(5,observed.get("g2"));
        assertEquals(Map.of("g2",4,"g1",7),catalog.observeRefillDeficits(targets));
        verifyNoInteractions(redis);
    }

    @Test void deficitCountsRetainCapacityClippingAndEmptyInputExpiry() {
        var target=List.of(new RefillTarget("any",ANY,1000));
        for(int g=0;g<10;g++) {
            int count=g==9?999:1000;
            for(int offset=0;offset<count;offset+=100) {
                var offered=new LinkedHashMap<String,Long>();
                for(int i=offset;i<Math.min(count,offset+100);i++)offered.put("w"+i,20L);
                assertEquals(offered.size(),catalog.refill("group"+g,target,offered));
            }
        }
        assertEquals(1,budget.available());
        var requested=Map.of("g1",List.of(new RefillTarget("any",ANY,100)));
        assertEquals(Map.of("g1",1),catalog.observeRefillDeficits(requested));
        assertEquals(1,catalog.refill("g1",requested.get("g1"),offer("last")));
        assertTrue(catalog.observeRefillDeficits(requested).isEmpty());
        clock.set(61_000);
        assertTrue(catalog.observeRefillDeficits(Map.of()).isEmpty());
        assertEquals(10_000,budget.available());
        assertEquals(Map.of("g1",100),catalog.observeRefillDeficits(requested));
        verifyNoInteractions(redis);
    }

    @Test void namedRefillAndTakeNeedNoPreparation() {
        rule.facts.putAll(Map.of("a","US","b","US"));
        var targets=List.of(pool(1,"US"));
        assertEquals(1,catalog.refill("g1",targets,offer("a")));
        assertTrue(takeItems(catalog,"g2","test.pool",Map.of(),1).isEmpty());
        assertEquals(1,catalog.refill("g2",targets,offer("b")));
        assertEquals("a",takeItems(catalog,"g1","test.pool",Map.of(),1).getFirst().workerId());
        assertEquals("b",takeItems(catalog,"g2","test.pool",Map.of(),1).getFirst().workerId());
        verifyNoInteractions(redis);
    }

    @Test void observationIsNotAReservationAndAdmissionUsesCurrentStock() {
        rule.facts.putAll(Map.of("first","US","second","US"));
        var targets=List.of(pool(1,"US"));
        assertEquals(Map.of("g1",1),catalog.observeRefillDeficits(Map.of("g1",targets)));
        assertEquals(1,catalog.refill("g1",targets,offer("first")));
        int reads=rule.snapshots.size();
        assertEquals(0,catalog.refill("g1",targets,offer("second")));
        assertEquals(reads,rule.snapshots.size());
        assertTrue(catalog.observeRefillDeficits(Map.of("g1",targets)).isEmpty());
        catalog.take("g1",Map.of("m",new WorkerQuery("test.pool",Map.of())));
        assertEquals(1,catalog.refill("g1",targets,offer("second")));
    }

    @Test void observationDoesNotAdvancePagesAndActualRefillAttemptsDo() {
        var targets=new ArrayList<RefillTarget>();
        for(int i=0;i<200;i++)targets.add(pool(1,String.format("p%03d",i)));
        var rules=List.copyOf(targets);
        catalog.observeRefillDeficits(Map.of("g1",rules));
        catalog.observeRefillDeficits(Map.of("g1",rules));
        assertEquals(100,rule.observedTargets.size());
        assertEquals(0,catalog.refill("g1",rules,Map.of()));
        catalog.observeRefillDeficits(Map.of("g1",rules));
        assertEquals(100,rule.observedTargets.size());
        assertEquals(0,catalog.refill("g1",rules,offer("missing")));
        assertEquals(100,rule.observedTargets.size());
        catalog.observeRefillDeficits(Map.of("g1",rules));
        assertEquals(200,rule.observedTargets.size());
    }

    @Test void invalidCoordinatesAndBatchBoundsFailBeforeQualification() {
        var targets=List.of(pool(1,"US"));
        assertThrows(IllegalArgumentException.class,()->catalog.refill("g1",targets,offer("w","w")));
        assertThrows(IllegalArgumentException.class,()->catalog.refill("g1",targets,offer("")));
        assertThrows(IllegalArgumentException.class,()->catalog.refill("g1",targets,
                java.util.stream.IntStream.range(0,101).boxed().collect(java.util.stream.Collectors.toMap(i -> "w"+i, i -> 20L))));
        assertThrows(NullPointerException.class,()->catalog.refill("g1",targets,null));
        assertThrows(IllegalArgumentException.class,()->catalog.refill("g1",List.of(new RefillTarget("missing",ANY,1)),offer("w")));
        assertThrows(IllegalArgumentException.class,()->catalog.take("g2",Map.of("m",new WorkerQuery("zz.fail",Map.of()))));
        assertThrows(IllegalArgumentException.class,()->catalog.normalizeQuery("g1",new WorkerQuery("missing",Map.of())));
        assertTrue(catalog.observeRefillDeficits(Map.of("g1",List.of())).isEmpty());
        assertThrows(IllegalArgumentException.class,()->catalog.observeRefillDeficits(Map.of("g1",Collections.nCopies(10_001,pool(1,"US")))));
        var tooMany=new LinkedHashMap<String,List<RefillTarget>>();
        for(int i=0;i<101;i++)tooMany.put("g"+i,List.of(new RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 1)));
        assertThrows(IllegalArgumentException.class,()->catalog.observeRefillDeficits(tooMany));
        tooMany.remove("g100");
        tooMany.put("g1",List.of(new RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 1), pool(1,"US")));
        assertEquals(100,catalog.observeRefillDeficits(tooMany).size());
        assertTrue(rule.snapshots.isEmpty());
        assertTrue(takeItems(catalog,"g1","test.pool",Map.of(),1).isEmpty());
        verifyNoInteractions(redis);
    }

    @Test void declarationCeilingsAreAcceptedAndEquivalentTargetsUseMax() {
        var rules=Collections.nCopies(10_000,new RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 1));
        assertEquals(Map.of("g1",1),catalog.observeRefillDeficits(Map.of("g1",rules)));
        assertEquals(1,catalog.refill("g1",rules,offer("first","second")));
        var groups=new LinkedHashMap<String,List<RefillTarget>>();
        for(int i=0;i<100;i++)groups.put("group"+i,List.of(new RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 1)));
        assertEquals(100,catalog.observeRefillDeficits(groups).size());
    }

    @Test void qualificationReceivesGenerationsAndAdmissionStartsLocalTtl() {
        rule.facts.put("w","US"); rule.beforeSnapshot=()->clock.set(61_000);
        var targets=List.of(pool(1,"US"));
        assertEquals(1,catalog.refill("g1",targets,offer("w")));
        assertEquals(List.of(List.of("w")),rule.snapshots);
        clock.set(120_999);
        assertEquals(new WorkerCandidate("w",20),takeItems(catalog,"g1","test.pool",Map.of(),1).getFirst());
        assertEquals(Map.of("g1",1),catalog.observeRefillDeficits(Map.of("g1",targets)));
    }

    @Test void laterRuleFailurePreservesEarlierAdmission() {
        rule.facts.put("us","US");
        failingRule.beforeSnapshot=()->{throw new IllegalStateException("projection failed");};
        var targets=List.of(pool(1,"US"), new RefillTarget("zz.fail",pool(1,"CN").target(),1));
        assertThrows(IllegalStateException.class,()->catalog.refill("g1",targets,offer("us","cn")));
        assertEquals(List.of(List.of("us","cn")),rule.snapshots);
        assertEquals(List.of("us"),takeItems(catalog,"g1","test.pool",Map.of(),1).stream().map(h -> h.workerId()).toList());
        assertTrue(takeItems(catalog,"g1","zz.fail",Map.of(),1).isEmpty());
    }

    @Test void admittedCandidatesAreExcludedFromEveryLaterPoolQualification() {
        rule.facts.putAll(Map.of("a", "US", "b", "US"));
        failingRule.facts.putAll(rule.facts);
        var targets = List.of(pool(1, "US"), new RefillTarget("zz.fail", pool(1, "US").target(), 1));
        assertEquals(2, catalog.refill("g1", targets, offer("a", "b")));
        assertEquals(List.of(List.of("a", "b")), rule.snapshots);
        assertEquals(List.of(List.of("b")), failingRule.snapshots);
        assertEquals("a", takeItems(catalog, "g1", "test.pool", Map.of(), 1).getFirst().workerId());
        assertEquals("b", takeItems(catalog, "g1", "zz.fail", Map.of(), 1).getFirst().workerId());
        verifyNoInteractions(redis);
    }

    @Test void aRejectedCandidateCanEnterALaterPool() {
        rule.facts.put("w", "CN");
        failingRule.facts.put("w", "US");
        var targets = List.of(pool(1, "US"), new RefillTarget("zz.fail", pool(1, "US").target(), 1));
        assertEquals(1, catalog.refill("g1", targets, offer("w")));
        assertEquals(List.of(List.of("w")), rule.snapshots);
        assertEquals(List.of(List.of("w")), failingRule.snapshots);
        assertTrue(takeItems(catalog, "g1", "test.pool", Map.of(), 1).isEmpty());
        assertEquals("w", takeItems(catalog, "g1", "zz.fail", Map.of(), 1).getFirst().workerId());
    }

    @Test void laterDemandDoesNotCopyRetainedStockWithoutNewCandidateization() {
        rule.facts.put("w", "US");
        assertEquals(1, catalog.refill("g1", List.of(new RefillTarget("any", ANY, 1)), Map.of("w", 42L)));
        var later = List.of(pool(1, "US"));
        assertEquals(Map.of("g1", 1), catalog.observeRefillDeficits(Map.of("g1", later)));
        assertEquals(0, catalog.refill("g1", later, Map.of()));
        assertTrue(rule.snapshots.isEmpty());
        assertTrue(takeItems(catalog, "g1", "test.pool", Map.of(), 1).isEmpty());
        assertEquals(new WorkerCandidate("w", 42), takeItems(catalog, "g1", "worker.any", Map.of(), 1).getFirst());
    }

    @Test void compositionControlsRotationInsteadOfPoolNames() {
        rule.facts.put("w", "US");
        failingRule.facts.put("w", "US");
        var configured = new RedisWorkerMatchingCatalog(storage, budget,
                Map.of("a", rule.stock, "z", failingRule.stock), clock::get,
                Map.of("a", rule, "z", failingRule), Map.of(), Map.of("g", new MatchingGroup(Set.of("a", "z"), Set.of())),
                List.of("z", "a"), Set.of());
        var targets = List.of(new RefillTarget("a", ANY, 1), new RefillTarget("z", ANY, 1));
        assertEquals(1, configured.refill("g", targets, Map.of("w", 42L)));
        assertTrue(rule.snapshots.isEmpty());
        assertEquals(List.of(List.of("w")), failingRule.snapshots);
    }

    @Test void newCandidateBatchesKeepTheTotalBudgetAndPoolRotation() {
        var first = new LinkedHashMap<String, Long>();
        var second = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 200; i++) {
            String id = "w" + i;
            (i < 100 ? first : second).put(id, 20L);
            rule.facts.put(id, "US");
            failingRule.facts.put(id, "US");
        }
        var targets = List.of(pool(100, "US"), new RefillTarget("zz.fail", pool(100, "US").target(), 100));
        assertEquals(100, catalog.refill("g1", targets, first));
        assertTrue(failingRule.snapshots.isEmpty());
        assertEquals(100, catalog.refill("g1", targets, second));
        assertEquals(List.of(List.copyOf(first.keySet())), rule.snapshots);
        assertEquals(List.of(List.copyOf(second.keySet())), failingRule.snapshots);
        assertEquals(first.keySet(), new LinkedHashSet<>(takeItems(catalog, "g1", "test.pool", Map.of(), 100)
                .stream().map(WorkerCandidate::workerId).toList()));
        assertEquals(second.keySet(), new LinkedHashSet<>(takeItems(catalog, "g1", "zz.fail", Map.of(), 100)
                .stream().map(WorkerCandidate::workerId).toList()));
    }

    @Test void aFullPoolRequalifiesReplacementGenerationsAndRemovesNonmatchingOnes() {
        rule.facts.put("w", "US");
        var targets = List.of(pool(1,"US"));
        assertEquals(1, catalog.refill("g1", targets, Map.of("w", 20L)));
        assertTrue(catalog.observeRefillDeficits(Map.of("g1", targets)).isEmpty());
        assertEquals(1, catalog.refill("g1", targets, Map.of("w", 21L)));
        assertEquals(0, catalog.refill("g1", targets, Map.of("w", 21L)));
        rule.facts.put("w", "CN");
        assertEquals(0, catalog.refill("g1", targets, Map.of("w", 22L)));
        assertTrue(takeItems(catalog,"g1","test.pool",Map.of(),1).isEmpty());
    }

    static final class CountingRule extends PoolMaintenance<String> {
        final CandidatePool stock;
        CountingRule(java.util.function.LongSupplier clock, CandidateBudget budget) { this(clock,new CandidatePool(clock,budget)); }
        CountingRule(java.util.function.LongSupplier clock,CandidatePool stock) { super(stock); this.stock=stock; }
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
        final Map<String,String> facts=new HashMap<>();
        final Set<EligibilityQuery> observedTargets=new LinkedHashSet<>();
        final List<List<String>> snapshots=new ArrayList<>();
        int normalizations;
        Runnable beforeSnapshot=()->{};
        @Override protected EligibilityQuery normalize(String group,EligibilityQuery input) {
            var expression = input.query();
            normalizations++;
            if(!Set.of("pool").containsAll(expression.keySet()))throw new IllegalArgumentException("unsupported pool query");
            var result=new LinkedHashMap<String,List<String>>();
            expression.forEach((key,value)->result.put(key,((List<?>)value).stream()
                    .map(String.class::cast).distinct().sorted().toList()));
            return new EligibilityQuery(result);
        }
        @Override protected Selection target(String group,EligibilityQuery query) {
            observedTargets.add(query);
            return query.query().isEmpty() ? all() : range("pool",query.query().get("pool"));
        }
        protected Object normalizeLocalInput(String group,Object input) {
            return normalize(group,EligibilityQuery.parse((Map<?,?>)input)).query();
        }
        protected Selection select(String group,Object input) {
            var query=EligibilityQuery.parse((Map<?,?>)input);
            return query.query().isEmpty() ? all() : range("pool",query.query().get("pool"));
        }
        @Override protected Map<String,String> memberships(String group,String id,String pool) {
            return pool==null ? null : Map.of("pool",pool);
        }
        @Override protected Map<String,String> readQualifications(String group,List<String> ids) {
            beforeSnapshot.run(); snapshots.add(List.copyOf(ids));
            var result=new LinkedHashMap<String,String>();
            ids.forEach(id->{if(facts.containsKey(id))result.put(id,facts.get(id));});
            return result;
        }
    }

    /** Fixture for stock-volume proofs: submit distinct Items through the public correlation port. */
    private static List<WorkerCandidate> takeItems(com.xa.mass.kernel.assignment.WorkerMatching matching,
            String group,String rule,Object input,int count) {
        var requests=new LinkedHashMap<String,WorkerQuery>();
        for(int i=0;i<count;i++)requests.put("message-"+i,new WorkerQuery(rule,input));
        return List.copyOf(matching.take(group,requests).values());
    }
}
