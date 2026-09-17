package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.WorkerMatching.HeldCandidate;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.workermatching.rules.*;
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
    final RedisClient client=mock(RedisClient.class);
    @SuppressWarnings("unchecked") final StatefulRedisConnection<String,String> connection=mock(StatefulRedisConnection.class);
    @SuppressWarnings("unchecked") final RedisCommands<String,String> redis=mock(RedisCommands.class);
    CountingRule rule;
    CountingRule failingRule;
    final java.util.concurrent.atomic.AtomicLong clock=new java.util.concurrent.atomic.AtomicLong(1000);
    final JsonMapper json=JsonMapper.builder().build();
    RedisWorkerMatchingCatalog catalog;
    RedisRuleStorage storage;
    static final EligibilityQuery ANY=EligibilityQuery.parse(Map.of());

    @BeforeEach void setUp() {
        when(client.connect(StringCodec.UTF8)).thenReturn(connection);
        when(connection.isOpen()).thenReturn(true);
        when(connection.sync()).thenReturn(redis);
        storage=new RedisRuleStorage(client,new RedisKeyspace("test_named_pool"),Map.of(),clock::get);
        rule=new CountingRule(storage); failingRule=new CountingRule(storage);
        catalog=new RedisWorkerMatchingCatalog(storage,
                Map.of("worker.default",new DefaultRuleHandler(storage,Map.of()),"test.pool",rule,"zz.fail",failingRule),
                Map.of("g1",Set.of("test.pool","zz.fail"),"g2",Set.of("test.pool")),Map.of());
    }
    @AfterEach void close() { catalog.close(); }

    @Test void equivalentAndInterleavedQueriesAreGroupedOnceThenCorrelatedInInputOrder() {
        var handler=mock(RuleHandler.class);
        var raw=pool(1,"US","CN","US").query();
        var normalized=pool(1,"CN","US").query();
        var narrow=pool(1,"CN").query();
        when(handler.normalizeQuery("g1",raw)).thenReturn(normalized);
        when(handler.normalizeQuery("g1",normalized)).thenReturn(normalized);
        when(handler.normalizeQuery("g1",narrow)).thenReturn(narrow);
        var supplied=List.of(new WorkerCandidate("first", 0),
                new WorkerCandidate("second", 42), new WorkerCandidate("third", 44));
        when(handler.take(eq("g1"),anyMap())).thenAnswer(call->{
            Map<EligibilityQuery,Integer> limits=call.getArgument(1);
            assertEquals(List.of(normalized,narrow),List.copyOf(limits.keySet()));
            assertEquals(Map.of(normalized,3,narrow,1),limits);
            return Map.of(normalized,List.of(supplied.get(0),supplied.get(1)),narrow,List.of(supplied.get(2)));
        });
        try(var matching=new RedisWorkerMatchingCatalog(storage,Map.of("worker.default",handler),Map.of(),Map.of())) {
            var requests=new LinkedHashMap<String,EligibilityQuery>();
            requests.put("a",raw); requests.put("b",narrow); requests.put("c",normalized); requests.put("d",raw);
            var result=matching.take("g1","worker.default",requests);
            assertEquals(List.of("a","b","c"),List.copyOf(result.keySet()));
            assertSame(supplied.get(0),result.get("a"));
            assertSame(supplied.get(2),result.get("b"));
            assertSame(supplied.get(1),result.get("c"));
            assertThrows(UnsupportedOperationException.class,result::clear);
            requests.clear();
            assertEquals(3,result.size());
            verify(handler,times(1)).take(eq("g1"),anyMap());
            verifyNoInteractions(redis);
        }
    }

    @Test void candidateRequiresIdentityButLeavesFenceInterpretationToScoreOwner() {
        assertThrows(IllegalArgumentException.class, () -> new WorkerCandidate(" ", 0));
        assertThrows(IllegalArgumentException.class, () -> new WorkerCandidate(null, 10));
        assertEquals(0, new WorkerCandidate("hint", 0).expectedScore());
        assertEquals(-17, new WorkerCandidate("opaque", -17).expectedScore());
    }

    @Test void invalidWholeBatchLeavesStockUntouchedAndEmptyDoesNotTouchRule() {
        rule.facts.put("worker","US");
        assertEquals(1,catalog.refill("g1",Map.of("test.pool",List.of(pool(1,"US"))),offer("worker")));
        int before=rule.normalizations;
        assertTrue(catalog.take("g1","test.pool",Map.of()).isEmpty());
        assertEquals(before,rule.normalizations);
        assertThrows(IllegalArgumentException.class,()->catalog.take(" ","test.pool",Map.of()));
        assertThrows(IllegalArgumentException.class,()->catalog.take("g1"," ",Map.of()));
        assertThrows(IllegalArgumentException.class,()->catalog.take("g1","missing",Map.of()));
        assertThrows(IllegalArgumentException.class,()->catalog.take("other","test.pool",Map.of()));
        assertThrows(NullPointerException.class,()->catalog.take("g1","test.pool",null));
        var requests=new LinkedHashMap<String,EligibilityQuery>();
        requests.put("valid",ANY);
        requests.put("late",EligibilityQuery.parse(Map.of("unsupported",List.of("x"))));
        assertThrows(IllegalArgumentException.class,()->catalog.take("g1","test.pool",requests));
        requests.put("late",null);
        assertThrows(NullPointerException.class,()->catalog.take("g1","test.pool",requests));
        requests.remove("late"); requests.put(" ",ANY);
        assertThrows(IllegalArgumentException.class,()->catalog.take("g1","test.pool",requests));
        requests.remove(" "); requests.put(null,ANY);
        assertThrows(IllegalArgumentException.class,()->catalog.take("g1","test.pool",requests));
        requests.remove(null);
        for(int i=0;i<100;i++)requests.put("m"+i,ANY);
        assertThrows(IllegalArgumentException.class,()->catalog.take("g1","test.pool",requests));
        var remaining=catalog.take("g1","test.pool",Map.of("valid",ANY));
        assertEquals("worker",remaining.get("valid").workerId());
        verifyNoInteractions(redis);
    }

    @Test void overlappingQueriesShortagesAndRepeatedMessageIdsHaveOnlyCallLocalMeaning() {
        var targets=Map.of("worker.default",List.of(new RefillTarget(Map.of(),3)));
        var held=offer("one","two","three");
        assertEquals(3,catalog.refill("g1",targets,held));
        var identity=EligibilityQuery.parse(Map.of("workerId",List.of("one")));
        var requests=new LinkedHashMap<String,EligibilityQuery>();
        requests.put("id-first",identity); requests.put("any-first",ANY);
        requests.put("id-again",identity); requests.put("any-next",ANY); requests.put("unfilled",ANY);
        var result=catalog.take("g1","worker.default",requests);
        assertEquals(List.of("id-first","any-first","any-next"),List.copyOf(result.keySet()));
        assertEquals(held.stream().map(NamedPoolOperationsTest::candidate).toList(),List.copyOf(result.values()));
        assertTrue(catalog.take("g1","worker.default",requests).isEmpty());
        assertEquals(1,catalog.refill("g1",targets,offer("four")));
        assertEquals("four",catalog.take("g1","worker.default",Map.of("id-first",ANY)).get("id-first").workerId());
        verifyNoInteractions(redis);
    }

    @Test void oneHundredMessagesCanShareOneNormalizedQuery() {
        var held=new ArrayList<HeldCandidate>();
        var requests=new LinkedHashMap<String,EligibilityQuery>();
        for(int i=0;i<100;i++) {
            held.add(new HeldCandidate("worker"+i,20+i,2000));
            requests.put("message"+i,ANY);
        }
        assertEquals(100,catalog.refill("g1",Map.of("worker.default",List.of(new RefillTarget(Map.of(),100))),held));
        var result=catalog.take("g1","worker.default",requests);
        assertEquals(List.copyOf(requests.keySet()),List.copyOf(result.keySet()));
        assertEquals(held.stream().map(NamedPoolOperationsTest::candidate).toList(),List.copyOf(result.values()));
        verifyNoInteractions(redis);
    }

    @Test void resolvesTargetsWithoutTaskIdentityRedisOrInventoryChanges() {
        var expected = pool(8,"US");
        var resolved = catalog.resolveRefillTargets("g1", "test.pool",
                List.of(pool(3,"US","US"),expected));
        assertEquals(List.of(expected), resolved);
        assertThrows(UnsupportedOperationException.class, resolved::clear);
        assertEquals(List.of(new RefillTarget(Map.of(),100)),catalog.resolveRefillTargets("g1","test.pool",null));
        assertThrows(IllegalArgumentException.class,()->catalog.resolveRefillTargets("g1","test.pool",List.of()));
        assertThrows(IllegalArgumentException.class,()->catalog.resolveRefillTargets("g1","unknown",null));
        assertThrows(IllegalArgumentException.class,()->catalog.resolveRefillTargets("other","test.pool",null));
        assertThrows(IllegalArgumentException.class,()->catalog.resolveRefillTargets("g1","test.pool",Collections.nCopies(101,expected)));
        assertTrue(takeItems(catalog,"g1","test.pool",ANY,1).isEmpty());
        verifyNoInteractions(redis);
    }

    static RefillTarget pool(int count,String... values) {
        return new RefillTarget(Map.of("pool",List.of(values)),count);
    }
    static WorkerCandidate candidate(HeldCandidate held) { return new WorkerCandidate(held.workerId(),held.score()); }
    static List<HeldCandidate> offer(String... ids) {
        return Arrays.stream(ids).map(id->new HeldCandidate(id,20,2000)).toList();
    }

    @Test void namedOperationsMergeDeclarationsWithoutTaskRegistration() {
        rule.facts.putAll(Map.of("w1","US","w2","US","w3","US","w4","CN"));
        var targets=Map.of("g1",Map.of("test.pool",List.of(pool(1,"US"),pool(2,"US","US"))),
                "g2",Map.of("test.pool",List.of(pool(1,"CN"))));
        assertTrue(rule.observedTargets.isEmpty());
        clearInvocations(redis);
        var needed=catalog.groupsNeedingRefill(targets);
        assertEquals(Set.of("g1","g2"),needed);
        assertThrows(UnsupportedOperationException.class,needed::clear);
        assertEquals(2,catalog.refill("g1",targets.get("g1"),offer("w1","w2","w3")));
        assertEquals(1,catalog.refill("g2",targets.get("g2"),offer("w4")));
        assertEquals(List.of("w1","w2"),takeItems(catalog,"g1","test.pool",ANY,2)
                .stream().map(h -> h.workerId()).toList());
        assertEquals(List.of("w4"),takeItems(catalog,"g2","test.pool",ANY,1)
                .stream().map(h -> h.workerId()).toList());
        verifyNoInteractions(redis);
    }

    @Test void namedRefillAndTakeNeedNoPreparation() {
        rule.facts.putAll(Map.of("a","US","b","US"));
        var targets=Map.of("test.pool",List.of(pool(1,"US")));
        assertEquals(1,catalog.refill("g1",targets,offer("a")));
        assertTrue(takeItems(catalog,"g2","test.pool",ANY,1).isEmpty());
        assertEquals(1,catalog.refill("g2",targets,offer("b")));
        assertEquals("a",takeItems(catalog,"g1","test.pool",ANY,1).getFirst().workerId());
        assertEquals("b",takeItems(catalog,"g2","test.pool",ANY,1).getFirst().workerId());
        verifyNoInteractions(redis);
    }

    @Test void observationIsNotAReservationAndAdmissionUsesCurrentStock() {
        rule.facts.putAll(Map.of("first","US","second","US"));
        var targets=Map.of("test.pool",List.of(pool(1,"US")));
        assertEquals(Set.of("g1"),catalog.groupsNeedingRefill(Map.of("g1",targets)));
        assertEquals(1,catalog.refill("g1",targets,offer("first")));
        int reads=rule.snapshots.size();
        assertEquals(0,catalog.refill("g1",targets,offer("second")));
        assertEquals(reads,rule.snapshots.size());
        assertTrue(catalog.groupsNeedingRefill(Map.of("g1",targets)).isEmpty());
        catalog.take("g1","test.pool",Map.of("m",ANY));
        assertEquals(1,catalog.refill("g1",targets,offer("second")));
    }

    @Test void observationDoesNotAdvancePagesAndActualRefillAttemptsDo() {
        var targets=new ArrayList<RefillTarget>();
        for(int i=0;i<200;i++)targets.add(pool(1,String.format("p%03d",i)));
        var rules=Map.of("test.pool",List.copyOf(targets));
        catalog.groupsNeedingRefill(Map.of("g1",rules));
        catalog.groupsNeedingRefill(Map.of("g1",rules));
        assertEquals(100,rule.observedTargets.size());
        assertEquals(0,catalog.refill("g1",rules,offer("missing")));
        assertEquals(100,rule.observedTargets.size());
        catalog.groupsNeedingRefill(Map.of("g1",rules));
        assertEquals(200,rule.observedTargets.size());
    }

    @Test void invalidCoordinatesAndBatchBoundsFailBeforeQualification() {
        var targets=Map.of("test.pool",List.of(pool(1,"US")));
        assertThrows(IllegalArgumentException.class,()->catalog.refill("g1",targets,offer("w","w")));
        assertThrows(IllegalArgumentException.class,()->catalog.refill("g1",targets,offer("")));
        assertThrows(IllegalArgumentException.class,()->catalog.refill("g1",targets,
                java.util.stream.IntStream.range(0,101).mapToObj(i->new HeldCandidate("w"+i,20,2000)).toList()));
        assertThrows(NullPointerException.class,()->catalog.refill("g1",targets,Arrays.asList((HeldCandidate)null)));
        assertThrows(IllegalArgumentException.class,()->catalog.refill("g1",Map.of("missing",List.of(pool(1,"US"))),offer("w")));
        assertThrows(IllegalArgumentException.class,()->catalog.take("g2","zz.fail",Map.of("m",ANY)));
        assertThrows(IllegalArgumentException.class,()->catalog.normalizeQuery("g1","missing",ANY));
        assertThrows(IllegalArgumentException.class,()->catalog.groupsNeedingRefill(Map.of("g1",Map.of("test.pool",List.of()))));
        assertThrows(IllegalArgumentException.class,()->catalog.groupsNeedingRefill(Map.of("g1",Map.of("test.pool",Collections.nCopies(10_001,pool(1,"US"))))));
        var tooMany=new LinkedHashMap<String,Map<String,List<RefillTarget>>>();
        for(int i=0;i<101;i++)tooMany.put("g"+i,Map.of("worker.default",List.of(new RefillTarget(Map.of(),1))));
        assertThrows(IllegalArgumentException.class,()->catalog.groupsNeedingRefill(tooMany));
        tooMany.remove("g100");
        tooMany.put("g1",Map.of("worker.default",List.of(new RefillTarget(Map.of(),1)),
                "test.pool",List.of(pool(1,"US"))));
        assertThrows(IllegalArgumentException.class,()->catalog.groupsNeedingRefill(tooMany));
        assertTrue(rule.snapshots.isEmpty());
        assertTrue(takeItems(catalog,"g1","test.pool",ANY,1).isEmpty());
        verifyNoInteractions(redis);
    }

    @Test void declarationCeilingsAreAcceptedAndEquivalentTargetsUseMax() {
        var rules=Map.of("worker.default",Collections.nCopies(10_000,new RefillTarget(Map.of(),1)));
        assertEquals(Set.of("g1"),catalog.groupsNeedingRefill(Map.of("g1",rules)));
        assertEquals(1,catalog.refill("g1",rules,offer("first","second")));
        var groups=new LinkedHashMap<String,Map<String,List<RefillTarget>>>();
        for(int i=0;i<100;i++)groups.put("group"+i,Map.of("worker.default",List.of(new RefillTarget(Map.of(),1))));
        assertEquals(100,catalog.groupsNeedingRefill(groups).size());
    }

    @Test void expiredOffersAreExcludedAndOriginalFenceSurvives() {
        rule.facts.putAll(Map.of("old","US","live","US"));
        var live=new HeldCandidate("live",21,2000);
        assertEquals(1,catalog.refill("g1",Map.of("test.pool",List.of(pool(2,"US"))),
                List.of(new HeldCandidate("old",20,1000),live)));
        assertEquals(List.of(List.of("live")),rule.snapshots);
        assertEquals(candidate(live),takeItems(catalog,"g1","test.pool",ANY,1).getFirst());
    }

    @Test void qualificationConsumesOriginalDeadline() {
        rule.facts.put("w","US"); rule.beforeSnapshot=()->clock.set(2000);
        var targets=Map.of("test.pool",List.of(pool(1,"US")));
        assertEquals(0,catalog.refill("g1",targets,offer("w")));
        assertEquals(List.of(List.of("w")),rule.snapshots);
        assertTrue(takeItems(catalog,"g1","test.pool",ANY,1).isEmpty());
        assertEquals(Set.of("g1"),catalog.groupsNeedingRefill(Map.of("g1",targets)));
    }

    @Test void laterRuleFailurePreservesEarlierAdmission() {
        rule.facts.put("us","US");
        failingRule.beforeSnapshot=()->{throw new IllegalStateException("projection failed");};
        var targets=Map.of("test.pool",List.of(pool(1,"US")),"zz.fail",List.of(pool(1,"CN")));
        assertThrows(IllegalStateException.class,()->catalog.refill("g1",targets,offer("us","cn")));
        assertEquals(List.of(List.of("us","cn")),rule.snapshots);
        assertEquals(List.of("us"),takeItems(catalog,"g1","test.pool",ANY,1).stream().map(h -> h.workerId()).toList());
        assertTrue(takeItems(catalog,"g1","zz.fail",ANY,1).isEmpty());
    }

    @Test void acceptedIdsAreExcludedFromLaterRulesAndRuleAttemptsRotate() {
        rule.facts.putAll(Map.of("a","US","b","US"));
        failingRule.facts.putAll(rule.facts);
        var targets=Map.of("test.pool",List.of(pool(1,"US")),"zz.fail",List.of(pool(1,"US")));
        assertEquals(2,catalog.refill("g1",targets,offer("a","b")));
        assertEquals(List.of(List.of("b")),failingRule.snapshots);
        assertEquals("a",takeItems(catalog,"g1","test.pool",ANY,1).getFirst().workerId());
        assertEquals("b",takeItems(catalog,"g1","zz.fail",ANY,1).getFirst().workerId());
        assertEquals(1,catalog.refill("g1",targets,offer("a")));
        assertEquals("a",takeItems(catalog,"g1","zz.fail",ANY,1).getFirst().workerId());
    }

    @Test void catalogWorksWithIndependentMapRuleWithoutBindingsOrRedis() {
        RuleHandler mapRule=new MapRule();
        try(var other=new RedisWorkerMatchingCatalog(storage,
                Map.of("worker.default",new DefaultRuleHandler(storage,Map.of()),"map",mapRule),
                Map.of("g1",Set.of("map")),Map.of())) {
            var targets=Map.of("map",List.of(new RefillTarget(Map.of(),2)));
            assertEquals(ANY,other.normalizeQuery("g1","map",ANY));
            assertEquals(Set.of("g1"),other.groupsNeedingRefill(Map.of("g1",targets)));
            var held=offer("first","second");
            assertEquals(2,other.refill("g1",targets,held));
            assertEquals(held.stream().map(NamedPoolOperationsTest::candidate).toList(),takeItems(other,"g1","map",ANY,2));
            verifyNoInteractions(redis);
        }
    }
    /** Deliberately independent representation: no local-candidate base, index or storage resource. */
    static final class MapRule implements RuleHandler {
        final Map<String,LinkedHashMap<String,HeldCandidate>> groups=new HashMap<>();
        public EligibilityQuery normalizeQuery(String group,EligibilityQuery query) {
            if(group==null || group.isBlank() || !query.query().isEmpty())throw new IllegalArgumentException();
            return query;
        }
        public synchronized Map<EligibilityQuery,Integer> deficits(String group,Map<EligibilityQuery,Integer> targets) {
            if(targets.size()>100)throw new IllegalArgumentException();
            var result=new LinkedHashMap<EligibilityQuery,Integer>();
            targets.forEach((q,count)->{
                normalizeQuery(group,q);
                if(count==null || count<1 || count>1000)throw new IllegalArgumentException();
                result.put(q,Math.max(0,count-groups.getOrDefault(group,new LinkedHashMap<>()).size()));
            });
            return Collections.unmodifiableMap(result);
        }
        public synchronized List<String> refill(String group,Map<EligibilityQuery,Integer> targets,List<HeldCandidate> offered,int maxAccepted) {
            var missing=deficits(group,targets);
            if(offered.size()>100 || maxAccepted<0 || maxAccepted>100 || offered.stream().map(h -> h.workerId()).distinct().count()!=offered.size())
                throw new IllegalArgumentException();
            int limit=Math.min(maxAccepted,missing.values().stream().mapToInt(Integer::intValue).max().orElse(0));
            var pool=groups.computeIfAbsent(group,k->new LinkedHashMap<>());var accepted=new ArrayList<String>();
            for(var candidate:offered)if(accepted.size()<limit && pool.putIfAbsent(candidate.workerId(),candidate)==null)accepted.add(candidate.workerId());
            return List.copyOf(accepted);
        }
        public synchronized Map<EligibilityQuery,List<WorkerCandidate>> take(String group,Map<EligibilityQuery,Integer> limits) {
            if(limits.size()>100 || limits.values().stream().anyMatch(n->n<1 || n>100) || limits.values().stream().mapToInt(Integer::intValue).sum()>100)
                throw new IllegalArgumentException();
            limits.keySet().forEach(selector->normalizeQuery(group,selector));
            var result=new LinkedHashMap<EligibilityQuery,List<WorkerCandidate>>();
            var pool=groups.getOrDefault(group,new LinkedHashMap<>());
            limits.forEach((selector,count)->{
                var taken=new ArrayList<HeldCandidate>();var iterator=pool.values().iterator();
                while(iterator.hasNext() && taken.size()<count) { taken.add(iterator.next());iterator.remove(); }
                result.put(selector,taken.stream().map(NamedPoolOperationsTest::candidate).toList());
            });
            return Collections.unmodifiableMap(result);
        }
    }

    static final class CountingRule extends LocalCandidateRule<String> {
        CountingRule(RedisRuleStorage storage) { super(storage); }
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
        @Override protected java.util.function.BiPredicate<String,String> predicate(String group,EligibilityQuery query) {
            observedTargets.add(query);
            return (id,pool)->pool!=null && (query.query().isEmpty() || query.query().get("pool").contains(pool));
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
            String group,String rule,EligibilityQuery query,int count) {
        var requests=new LinkedHashMap<String,EligibilityQuery>();
        for(int i=0;i<count;i++)requests.put("message-"+i,query);
        return List.copyOf(matching.take(group,rule,requests).values());
    }
}
