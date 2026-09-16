package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex.HeldCandidate;
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
    final Map<String,String> bindings=new LinkedHashMap<>();
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
        when(redis.hmget(anyString(),any(String[].class))).thenAnswer(call->{
            String[] ids=(String[])call.getRawArguments()[1];
            return Arrays.stream(ids).map(id->bindings.containsKey(id)
                    ?KeyValue.just(id,bindings.get(id)):KeyValue.<String,String>empty(id)).toList();
        });
        storage=new RedisRuleStorage(client,new RedisKeyspace("test_named_pool"),Map.of(),clock::get);
        rule=new CountingRule(storage); failingRule=new CountingRule(storage);
        catalog=new RedisWorkerMatchingCatalog(storage,
                Map.of("worker.default",new DefaultRuleHandler(storage,Map.of()),"test.pool",rule,"zz.fail",failingRule),
                Map.of("g1",Set.of("test.pool","zz.fail"),"g2",Set.of("test.pool")),Map.of());
    }
    @AfterEach void close() { catalog.close(); }

    void bind(String task,String group,List<RefillTarget> targets) {
        bindings.put(task,json.writeValueAsString(Map.of("workerGroupId",group,"ruleId","test.pool","refillTargets",targets)));
    }
    static RefillTarget pool(int count,String... values) {
        return new RefillTarget(Map.of("pool",List.of(values)),count);
    }
    static List<HeldCandidate> offer(String... ids) {
        return Arrays.stream(ids).map(id->new HeldCandidate(id,20,2000)).toList();
    }

    Map<String,Map<String,List<RefillTarget>>> targets(String... taskIds) {
        var result=new LinkedHashMap<String,Map<String,List<RefillTarget>>>();
        catalog.loadTaskBindings(List.of(taskIds)).values().forEach(binding->{
            if(binding!=null)result.computeIfAbsent(binding.workerGroupId(),k->new LinkedHashMap<>())
                    .computeIfAbsent(binding.ruleId(),k->new ArrayList<>()).addAll(binding.refillTargets());
        });
        return result;
    }

    @Test void namedOperationsMergeSharedTaskTargetsWithoutBindingReads() {
        bind("a","g1",List.of(pool(1,"US")));
        bind("b","g1",List.of(pool(2,"US","US")));
        bind("c","g2",List.of(pool(1,"CN")));
        rule.facts.putAll(Map.of("w1","US","w2","US","w3","US","w4","CN"));
        var targets=targets("a","b","c");
        assertTrue(rule.observedTargets.isEmpty());
        clearInvocations(redis);
        var needed=catalog.groupsNeedingRefill(targets);
        assertEquals(Set.of("g1","g2"),needed);
        assertThrows(UnsupportedOperationException.class,needed::clear);
        assertEquals(2,catalog.refill("g1",targets.get("g1"),offer("w1","w2","w3")));
        assertEquals(1,catalog.refill("g2",targets.get("g2"),offer("w4")));
        assertEquals(List.of("w1","w2"),catalog.take("g1","test.pool",Map.of(ANY,2)).get(ANY)
                .stream().map(HeldCandidate::workerId).toList());
        assertEquals(List.of("w4"),catalog.take("g2","test.pool",Map.of(ANY,1)).get(ANY)
                .stream().map(HeldCandidate::workerId).toList());
        verifyNoInteractions(redis);
    }

    @Test void namedRefillAndTakeNeedNeitherTaskBindingNorPreparation() {
        rule.facts.putAll(Map.of("a","US","b","US"));
        var targets=Map.of("test.pool",List.of(pool(1,"US")));
        assertEquals(1,catalog.refill("g1",targets,offer("a")));
        assertTrue(catalog.take("g2","test.pool",Map.of(ANY,1)).get(ANY).isEmpty());
        assertEquals(1,catalog.refill("g2",targets,offer("b")));
        assertEquals("a",catalog.take("g1","test.pool",Map.of(ANY,1)).get(ANY).getFirst().workerId());
        assertEquals("b",catalog.take("g2","test.pool",Map.of(ANY,1)).get(ANY).getFirst().workerId());
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
        catalog.take("g1","test.pool",Map.of(ANY,1));
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
        assertThrows(IllegalArgumentException.class,()->catalog.take("g2","zz.fail",Map.of(ANY,1)));
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
        assertTrue(catalog.take("g1","test.pool",Map.of(ANY,1)).get(ANY).isEmpty());
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
        assertSame(live,catalog.take("g1","test.pool",Map.of(ANY,1)).get(ANY).getFirst());
    }

    @Test void qualificationConsumesOriginalDeadline() {
        rule.facts.put("w","US"); rule.beforeSnapshot=()->clock.set(2000);
        var targets=Map.of("test.pool",List.of(pool(1,"US")));
        assertEquals(0,catalog.refill("g1",targets,offer("w")));
        assertEquals(List.of(List.of("w")),rule.snapshots);
        assertTrue(catalog.take("g1","test.pool",Map.of(ANY,1)).get(ANY).isEmpty());
        assertEquals(Set.of("g1"),catalog.groupsNeedingRefill(Map.of("g1",targets)));
    }

    @Test void laterRuleFailurePreservesEarlierAdmission() {
        rule.facts.put("us","US");
        failingRule.beforeSnapshot=()->{throw new IllegalStateException("projection failed");};
        var targets=Map.of("test.pool",List.of(pool(1,"US")),"zz.fail",List.of(pool(1,"CN")));
        assertThrows(IllegalStateException.class,()->catalog.refill("g1",targets,offer("us","cn")));
        assertEquals(List.of(List.of("us","cn")),rule.snapshots);
        assertEquals(List.of("us"),catalog.take("g1","test.pool",Map.of(ANY,1)).get(ANY).stream().map(HeldCandidate::workerId).toList());
        assertTrue(catalog.take("g1","zz.fail",Map.of(ANY,1)).get(ANY).isEmpty());
    }

    @Test void acceptedIdsAreExcludedFromLaterRulesAndRuleAttemptsRotate() {
        rule.facts.putAll(Map.of("a","US","b","US"));
        failingRule.facts.putAll(rule.facts);
        var targets=Map.of("test.pool",List.of(pool(1,"US")),"zz.fail",List.of(pool(1,"US")));
        assertEquals(2,catalog.refill("g1",targets,offer("a","b")));
        assertEquals(List.of(List.of("b")),failingRule.snapshots);
        assertEquals("a",catalog.take("g1","test.pool",Map.of(ANY,1)).get(ANY).getFirst().workerId());
        assertEquals("b",catalog.take("g1","zz.fail",Map.of(ANY,1)).get(ANY).getFirst().workerId());
        assertEquals(1,catalog.refill("g1",targets,offer("a")));
        assertEquals("a",catalog.take("g1","zz.fail",Map.of(ANY,1)).get(ANY).getFirst().workerId());
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
            assertEquals(held,other.take("g1","map",Map.of(ANY,2)).get(ANY));
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
            if(offered.size()>100 || maxAccepted<0 || maxAccepted>100 || offered.stream().map(HeldCandidate::workerId).distinct().count()!=offered.size())
                throw new IllegalArgumentException();
            int limit=Math.min(maxAccepted,missing.values().stream().mapToInt(Integer::intValue).max().orElse(0));
            var pool=groups.computeIfAbsent(group,k->new LinkedHashMap<>());var accepted=new ArrayList<String>();
            for(var candidate:offered)if(accepted.size()<limit && pool.putIfAbsent(candidate.workerId(),candidate)==null)accepted.add(candidate.workerId());
            return List.copyOf(accepted);
        }
        public synchronized Map<EligibilityQuery,List<HeldCandidate>> take(String group,Map<EligibilityQuery,Integer> limits) {
            if(limits.size()>100 || limits.values().stream().anyMatch(n->n<1 || n>100) || limits.values().stream().mapToInt(Integer::intValue).sum()>100)
                throw new IllegalArgumentException();
            limits.keySet().forEach(selector->normalizeQuery(group,selector));
            var result=new LinkedHashMap<EligibilityQuery,List<HeldCandidate>>();
            var pool=groups.getOrDefault(group,new LinkedHashMap<>());
            limits.forEach((selector,count)->{
                var taken=new ArrayList<HeldCandidate>();var iterator=pool.values().iterator();
                while(iterator.hasNext() && taken.size()<count) { taken.add(iterator.next());iterator.remove(); }
                result.put(selector,List.copyOf(taken));
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
}
