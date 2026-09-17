package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.WorkerMatching.HeldCandidate;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.workermatching.rules.*;
import com.xa.mass.workermatching.rules.CandidatePool.Selection;
import static com.xa.mass.workermatching.rules.CandidatePool.*;
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
    MatchingStorage storage;
    static final EligibilityQuery ANY=EligibilityQuery.parse(Map.of());

    @BeforeEach void setUp() {
        when(client.connect(StringCodec.UTF8)).thenReturn(connection);
        when(connection.isOpen()).thenReturn(true);
        when(connection.sync()).thenReturn(redis);
        storage=new MatchingStorage(client,new RedisKeyspace("test_named_pool"),clock::get);
        rule=new CountingRule(storage); failingRule=new CountingRule(storage);
        var defaultStock=new CandidatePool(storage);
        var defaults=new DefaultPoolPolicy(storage,defaultStock,Set.of());
        catalog=new RedisWorkerMatchingCatalog(storage,
                Map.of("default",defaults,"test.pool",rule,"zz.fail",failingRule),
                Map.of("worker.default",PoolQueryFunctions.defaults(defaultStock,Set.of()),"test.pool",rule.functions(),"zz.fail",failingRule.functions()),
                Map.of("g1",new MatchingGroup(Set.of("test.pool","zz.fail"),Set.of("test.pool","zz.fail")),"g2",new MatchingGroup(Set.of("test.pool"),Set.of("test.pool"))),Map.of());
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
        var targets=List.of(new RefillTarget("default", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 3));
        var held=offer("one","two","three"); assertEquals(3,catalog.refill("g1",targets,held));
        var identity=new WorkerQuery("worker.default",Map.of("workerId",List.of("one")));
        var any=new WorkerQuery("worker.default",Map.of());
        var requests=new LinkedHashMap<String,WorkerQuery>();
        requests.put("id-first",identity); requests.put("any-first",any); requests.put("id-again",identity);
        requests.put("any-next",any); requests.put("unfilled",any);
        var result=catalog.take("g1",requests);
        assertEquals(List.of("id-first","any-first","any-next"),List.copyOf(result.keySet()));
        assertEquals(held.stream().map(NamedPoolOperationsTest::candidate).toList(),List.copyOf(result.values()));
        assertTrue(catalog.take("g1",requests).isEmpty());
        assertEquals(1,catalog.refill("g1",targets,offer("four")));
        assertEquals("four",catalog.take("g1",Map.of("id-first",any)).get("id-first").workerId());
        verifyNoInteractions(redis);
    }

    @Test void oneHundredMessagesCanShareOneNormalizedQuery() {
        var held=new ArrayList<HeldCandidate>(); var requests=new LinkedHashMap<String,WorkerQuery>();
        for(int i=0;i<100;i++) { held.add(new HeldCandidate("worker"+i,20+i,2000));
            requests.put("message"+i,new WorkerQuery("worker.default",Map.of())); }
        assertEquals(100,catalog.refill("g1",List.of(new RefillTarget("default", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 100)),held));
        var result=catalog.take("g1",requests);
        assertEquals(List.copyOf(requests.keySet()),List.copyOf(result.keySet()));
        assertEquals(held.stream().map(NamedPoolOperationsTest::candidate).toList(),List.copyOf(result.values()));
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
    static WorkerCandidate candidate(HeldCandidate held) { return new WorkerCandidate(held.workerId(),held.score()); }
    static List<HeldCandidate> offer(String... ids) {
        return Arrays.stream(ids).map(id->new HeldCandidate(id,20,2000)).toList();
    }

    @Test void namedOperationsMergeDeclarationsWithoutTaskRegistration() {
        rule.facts.putAll(Map.of("w1","US","w2","US","w3","US","w4","CN"));
        var targets=Map.of("g1",List.of(pool(1,"US"), pool(2,"US","US")),
                "g2",List.of(pool(1,"CN")));
        assertTrue(rule.observedTargets.isEmpty());
        clearInvocations(redis);
        var needed=catalog.groupsNeedingRefill(targets);
        assertEquals(Set.of("g1","g2"),needed);
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
        var supply = List.of(new RefillTarget("default", ANY, 2));
        assertEquals(2, catalog.refill("g1", supply, offer("first", "second")));
        assertTrue(catalog.groupsNeedingRefill(Map.of()).isEmpty());
        var consumed = catalog.take("g1", Map.of("consumer", new WorkerQuery("worker.default", Map.of())));
        assertEquals("first", consumed.get("consumer").workerId());
        clock.set(2000);
        assertTrue(catalog.groupsNeedingRefill(Map.of()).isEmpty());
        assertTrue(catalog.take("g1", Map.of("late", new WorkerQuery("worker.default", Map.of()))).isEmpty());
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
        assertEquals(Set.of("g1"),catalog.groupsNeedingRefill(Map.of("g1",targets)));
        assertEquals(1,catalog.refill("g1",targets,offer("first")));
        int reads=rule.snapshots.size();
        assertEquals(0,catalog.refill("g1",targets,offer("second")));
        assertEquals(reads,rule.snapshots.size());
        assertTrue(catalog.groupsNeedingRefill(Map.of("g1",targets)).isEmpty());
        catalog.take("g1",Map.of("m",new WorkerQuery("test.pool",Map.of())));
        assertEquals(1,catalog.refill("g1",targets,offer("second")));
    }

    @Test void observationDoesNotAdvancePagesAndActualRefillAttemptsDo() {
        var targets=new ArrayList<RefillTarget>();
        for(int i=0;i<200;i++)targets.add(pool(1,String.format("p%03d",i)));
        var rules=List.copyOf(targets);
        catalog.groupsNeedingRefill(Map.of("g1",rules));
        catalog.groupsNeedingRefill(Map.of("g1",rules));
        assertEquals(100,rule.observedTargets.size());
        assertEquals(0,catalog.refill("g1",rules,offer("missing")));
        assertEquals(100,rule.observedTargets.size());
        catalog.groupsNeedingRefill(Map.of("g1",rules));
        assertEquals(200,rule.observedTargets.size());
    }

    @Test void invalidCoordinatesAndBatchBoundsFailBeforeQualification() {
        var targets=List.of(pool(1,"US"));
        assertThrows(IllegalArgumentException.class,()->catalog.refill("g1",targets,offer("w","w")));
        assertThrows(IllegalArgumentException.class,()->catalog.refill("g1",targets,offer("")));
        assertThrows(IllegalArgumentException.class,()->catalog.refill("g1",targets,
                java.util.stream.IntStream.range(0,101).mapToObj(i->new HeldCandidate("w"+i,20,2000)).toList()));
        assertThrows(NullPointerException.class,()->catalog.refill("g1",targets,Arrays.asList((HeldCandidate)null)));
        assertThrows(IllegalArgumentException.class,()->catalog.refill("g1",List.of(new RefillTarget("missing",ANY,1)),offer("w")));
        assertThrows(IllegalArgumentException.class,()->catalog.take("g2",Map.of("m",new WorkerQuery("zz.fail",Map.of()))));
        assertThrows(IllegalArgumentException.class,()->catalog.normalizeQuery("g1",new WorkerQuery("missing",Map.of())));
        assertTrue(catalog.groupsNeedingRefill(Map.of("g1",List.of())).isEmpty());
        assertThrows(IllegalArgumentException.class,()->catalog.groupsNeedingRefill(Map.of("g1",Collections.nCopies(10_001,pool(1,"US")))));
        var tooMany=new LinkedHashMap<String,List<RefillTarget>>();
        for(int i=0;i<101;i++)tooMany.put("g"+i,List.of(new RefillTarget("default", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 1)));
        assertThrows(IllegalArgumentException.class,()->catalog.groupsNeedingRefill(tooMany));
        tooMany.remove("g100");
        tooMany.put("g1",List.of(new RefillTarget("default", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 1), pool(1,"US")));
        assertEquals(100,catalog.groupsNeedingRefill(tooMany).size());
        assertTrue(rule.snapshots.isEmpty());
        assertTrue(takeItems(catalog,"g1","test.pool",Map.of(),1).isEmpty());
        verifyNoInteractions(redis);
    }

    @Test void declarationCeilingsAreAcceptedAndEquivalentTargetsUseMax() {
        var rules=Collections.nCopies(10_000,new RefillTarget("default", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 1));
        assertEquals(Set.of("g1"),catalog.groupsNeedingRefill(Map.of("g1",rules)));
        assertEquals(1,catalog.refill("g1",rules,offer("first","second")));
        var groups=new LinkedHashMap<String,List<RefillTarget>>();
        for(int i=0;i<100;i++)groups.put("group"+i,List.of(new RefillTarget("default", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 1)));
        assertEquals(100,catalog.groupsNeedingRefill(groups).size());
    }

    @Test void expiredOffersAreExcludedAndOriginalFenceSurvives() {
        rule.facts.putAll(Map.of("old","US","live","US"));
        var live=new HeldCandidate("live",21,2000);
        assertEquals(1,catalog.refill("g1",List.of(pool(2,"US")),
                List.of(new HeldCandidate("old",20,1000),live)));
        assertEquals(List.of(List.of("live")),rule.snapshots);
        assertEquals(candidate(live),takeItems(catalog,"g1","test.pool",Map.of(),1).getFirst());
    }

    @Test void qualificationConsumesOriginalDeadline() {
        rule.facts.put("w","US"); rule.beforeSnapshot=()->clock.set(2000);
        var targets=List.of(pool(1,"US"));
        assertEquals(0,catalog.refill("g1",targets,offer("w")));
        assertEquals(List.of(List.of("w")),rule.snapshots);
        assertTrue(takeItems(catalog,"g1","test.pool",Map.of(),1).isEmpty());
        assertEquals(Set.of("g1"),catalog.groupsNeedingRefill(Map.of("g1",targets)));
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

    @Test void acceptedIdsAreExcludedFromLaterRulesAndRuleAttemptsRotate() {
        rule.facts.putAll(Map.of("a","US","b","US"));
        failingRule.facts.putAll(rule.facts);
        var targets=List.of(pool(1,"US"), new RefillTarget("zz.fail",pool(1,"US").target(),1));
        assertEquals(2,catalog.refill("g1",targets,offer("a","b")));
        assertEquals(List.of(List.of("b")),failingRule.snapshots);
        assertEquals("a",takeItems(catalog,"g1","test.pool",Map.of(),1).getFirst().workerId());
        assertEquals("b",takeItems(catalog,"g1","zz.fail",Map.of(),1).getFirst().workerId());
        assertEquals(1,catalog.refill("g1",targets,offer("a")));
        assertEquals("a",takeItems(catalog,"g1","zz.fail",Map.of(),1).getFirst().workerId());
    }

    static final class CountingRule extends PoolMaintenance<String> {
        final CandidatePool stock;
        CountingRule(MatchingStorage storage) { this(storage,new CandidatePool(storage)); }
        CountingRule(MatchingStorage storage,CandidatePool stock) { super(storage,stock); this.stock=stock; }
        QueryFunctions functions() { return PoolQueryFunctions.create(stock,this::normalizeLocalInput,this::select); }
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
