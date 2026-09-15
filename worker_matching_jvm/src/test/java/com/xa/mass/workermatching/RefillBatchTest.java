package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex.HeldCandidate;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex.TaskQuery;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.task.TaskItemWorkerSelector;
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

class RefillBatchTest {
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
    static final TaskItemWorkerSelector ANY=TaskItemWorkerSelector.parse(Map.of());

    @BeforeEach void setUp() {
        when(client.connect(StringCodec.UTF8)).thenReturn(connection);
        when(connection.isOpen()).thenReturn(true);
        when(connection.sync()).thenReturn(redis);
        when(redis.hmget(anyString(),any(String[].class))).thenAnswer(call->{
            String[] ids=(String[])call.getRawArguments()[1];
            return Arrays.stream(ids).map(id->bindings.containsKey(id)
                    ?KeyValue.just(id,bindings.get(id)):KeyValue.<String,String>empty(id)).toList();
        });
        storage=new RedisRuleStorage(client,new RedisKeyspace("test_refill_batch"),Map.of(),clock::get);
        rule=new CountingRule(storage); failingRule=new CountingRule(storage);
        catalog=new RedisWorkerMatchingCatalog(storage,
                Map.of("worker.default",new DefaultRuleHandler(storage,Map.of()),"test.pool",rule,"zz.fail",failingRule),
                Map.of("g1",Set.of("test.pool","zz.fail"),"g2",Set.of("test.pool")),Map.of());
    }
    @AfterEach void close() { catalog.close(); }

    void bind(String task,String group,List<EligibilityQuery> targets) {
        bindings.put(task,json.writeValueAsString(Map.of("workerGroupId",group,"ruleId","test.pool","refillTargets",targets)));
    }
    static EligibilityQuery pool(int count,String... values) {
        return new EligibilityQuery(Map.of("pool",List.of(values)),count);
    }
    static List<HeldCandidate> offer(String... ids) {
        return Arrays.stream(ids).map(id->new HeldCandidate(id,20,2000)).toList();
    }

    @Test void oneBatchMergesTaskTargetsAndRetainsItsClosedInput() {
        bind("a","g1",List.of(pool(1,"US")));
        bind("b","g1",List.of(pool(2,"US","US")));
        bind("c","g2",List.of(pool(1,"CN")));
        rule.facts.putAll(Map.of("w1","US","w2","US","w3","US","w4","CN"));
        var tasks=new LinkedHashMap<>(catalog.prepareTaskQueries(Map.of("a","g1","b","g1","c","g2")));
        assertTrue(rule.observedTargets.isEmpty(),"admission must not inspect eligibility");
        var batch=catalog.prepareRefill(tasks);
        assertEquals(Set.of("g1","g2"),batch.groupsNeedingRefill());
        assertEquals(2,rule.observedTargets.size());
        assertTrue(rule.observedTargets.contains(pool(2,"US")),"duplicate targets must merge using MAX");
        int preparedNormalizations=rule.normalizations;
        clearInvocations(redis);
        tasks.clear(); // No later Group may reconstruct its inputs from the caller's Map.
        assertEquals(2,batch.refill("g1",offer("w1","w2","w3")));
        assertEquals(1,batch.refill("g2",offer("w4")));
        assertEquals(2,rule.observedTargets.size());
        assertTrue(rule.normalizations>=preparedNormalizations);
        assertEquals(List.of(List.of("w1","w2","w3"),List.of("w4")),rule.snapshots);
        verifyNoInteractions(redis);
    }

    @Test void preparationHintIsNotAReservationAndAdmissionUsesCurrentStockAndProjection() {
        bind("a","g1",List.of(pool(1,"US")));
        rule.facts.putAll(Map.of("first","US","second","US"));
        var tasks=catalog.prepareTaskQueries(Map.of("a","g1"));
        var stale=catalog.prepareRefill(tasks);
        var winner=catalog.prepareRefill(tasks);
        assertEquals(1,winner.refill("g1",offer("first")));
        int snapshots=rule.snapshots.size();
        assertEquals(0,stale.refill("g1",offer("second")));
        assertEquals(snapshots,rule.snapshots.size());
        assertEquals(1,tasks.get("a").take(Map.of(ANY,1)).get(ANY).size());
        var full=catalog.prepareRefill(tasks);
        assertEquals(Set.of("g1"),full.groupsNeedingRefill());
        rule.facts.put("second","CN");
        assertEquals(0,full.refill("g1",offer("second")));
        assertTrue(tasks.get("a").take(Map.of(ANY,1)).get(ANY).isEmpty());
    }

    @Test void foreignGroupsAreRejectedBeforeProjection() {
        bind("a","g1",List.of(pool(1,"US")));
        var batch=catalog.prepareRefill(catalog.prepareTaskQueries(Map.of("a","g1")));
        assertThrows(IllegalArgumentException.class,()->batch.refill("g2",offer("w")));
        assertTrue(rule.snapshots.isEmpty());
        assertThrows(UnsupportedOperationException.class,()->batch.groupsNeedingRefill().clear());
    }

    @Test void onlyVisitedTargetPagesReachRulesAndNextRoundAdvances() {
        var first=new ArrayList<EligibilityQuery>();var second=new ArrayList<EligibilityQuery>();
        for(int i=0;i<200;i++)(i<100?first:second).add(pool(1,String.format("p%03d",i)));
        bind("a","g1",first);bind("b","g1",second);
        var tasks=catalog.prepareTaskQueries(Map.of("a","g1","b","g1"));
        var batch=catalog.prepareRefill(tasks);
        assertEquals(100,rule.observedTargets.size());
        assertEquals(0,batch.refill("g1",offer("missing")));
        assertEquals(100,rule.observedTargets.size(),"the first refill uses the same bounded target page");
        catalog.prepareRefill(tasks);
        assertEquals(200,rule.observedTargets.size(),"the next round reaches the next bounded target page");
    }

    @Test void invalidBatchIsRejectedBeforeProjectionAndStockChanges() {
        bind("a","g1",List.of(pool(1,"US")));
        rule.facts.put("w","US");
        var tasks=catalog.prepareTaskQueries(Map.of("a","g1"));
        var batch=catalog.prepareRefill(tasks);
        assertThrows(IllegalArgumentException.class,()->batch.refill("g1",offer("w","w")));
        assertThrows(IllegalArgumentException.class,()->batch.refill("g1",offer("")));
        assertThrows(IllegalArgumentException.class,()->batch.refill("g1",java.util.stream.IntStream.range(0,101)
                .mapToObj(i->new HeldCandidate("w"+i,20,2000)).toList()));
        assertThrows(NullPointerException.class,()->batch.refill("g1",Arrays.asList((HeldCandidate)null)));
        assertTrue(rule.snapshots.isEmpty());
        assertTrue(tasks.get("a").take(Map.of(ANY,1)).get(ANY).isEmpty());
    }

    @Test void expiredOffersAreExcludedAndTheOriginalFenceAndDeadlineSurviveAdmission() {
        bind("a","g1",List.of(pool(2,"US")));
        rule.facts.putAll(Map.of("old","US","live","US"));
        var tasks=catalog.prepareTaskQueries(Map.of("a","g1"));
        var live=new HeldCandidate("live",21,2000);
        assertEquals(1,catalog.prepareRefill(tasks).refill("g1",List.of(new HeldCandidate("old",20,1000),live)));
        assertEquals(List.of(List.of("live")),rule.snapshots);
        assertSame(live,tasks.get("a").take(Map.of(ANY,1)).get(ANY).getFirst());
    }

    @Test void matchingTimeConsumesTheLeaseAndCannotRestartItsDeadline() {
        bind("a","g1",List.of(pool(1,"US")));
        rule.facts.put("w","US");
        rule.beforeSnapshot=()->clock.set(2000);
        var tasks=catalog.prepareTaskQueries(Map.of("a","g1"));
        assertEquals(0,catalog.prepareRefill(tasks).refill("g1",offer("w")));
        assertEquals(List.of(List.of("w")),rule.snapshots);
        assertTrue(tasks.get("a").take(Map.of(ANY,1)).get(ANY).isEmpty());
        assertEquals(Set.of("g1"),catalog.prepareRefill(tasks).groupsNeedingRefill());
    }

    @Test void laterHandlerFailurePreservesEarlierAdmission() {
        bind("a","g1",List.of(pool(1,"US")));
        bindings.put("b",json.writeValueAsString(Map.of("workerGroupId","g1","ruleId","zz.fail",
                "refillTargets",List.of(pool(1,"CN")))));
        rule.facts.put("us","US");
        failingRule.beforeSnapshot=()->{throw new IllegalStateException("projection failed");};
        var tasks=catalog.prepareTaskQueries(Map.of("a","g1","b","g1"));
        assertThrows(IllegalStateException.class,()->catalog.prepareRefill(tasks).refill("g1",offer("us","cn")));
        assertEquals(List.of(List.of("us","cn")),rule.snapshots);
        assertEquals(List.of("us"),tasks.get("a").take(Map.of(ANY,1)).get(ANY).stream().map(HeldCandidate::workerId).toList());
        assertTrue(tasks.get("b").take(Map.of(ANY,1)).get(ANY).isEmpty());
    }

    @Test void catalogWorksWithAnIndependentMapRuleWithoutRedisOrProjectionProtocols() {
        RuleHandler mapRule=new MapRule();
        var target=new EligibilityQuery(Map.of(),2);
        bindings.put("map",json.writeValueAsString(Map.of("workerGroupId","g1","ruleId","map",
                "refillTargets",List.of(target))));
        try(var other=new RedisWorkerMatchingCatalog(storage,
                Map.of("worker.default",new DefaultRuleHandler(storage,Map.of()),"map",mapRule),
                Map.of("g1",Set.of("map")),Map.of())) {
            var tasks=other.prepareTaskQueries(Map.of("map","g1"));
            tasks.get("map").validate(ANY); clearInvocations(redis);
            assertEquals(Set.of("g1"),other.prepareRefill(tasks).groupsNeedingRefill());
            var held=offer("first","second");
            assertEquals(2,other.prepareRefill(tasks).refill("g1",held));
            assertEquals(held,tasks.get("map").take(Map.of(ANY,2)).get(ANY));
            verifyNoInteractions(redis);
        }
    }
    /** Deliberately independent representation: no local-candidate base, index or storage resource. */
    static final class MapRule implements RuleHandler {
        final Map<String,LinkedHashMap<String,HeldCandidate>> groups=new HashMap<>();
        public EligibilityQuery normalizeTarget(String group,EligibilityQuery target) {
            if(group==null || group.isBlank() || !target.query().isEmpty())throw new IllegalArgumentException();
            return target;
        }
        public void validateSelector(String group,TaskItemWorkerSelector selector) {
            normalizeTarget(group,new EligibilityQuery(Map.of(),1));
            if(!selector.isAny())throw new IllegalArgumentException();
        }
        public synchronized Map<EligibilityQuery,Integer> deficits(String group,List<EligibilityQuery> targets) {
            if(targets.size()>100)throw new IllegalArgumentException();
            targets.forEach(q->normalizeTarget(group,q));
            var result=new LinkedHashMap<EligibilityQuery,Integer>();
            targets.forEach(q->result.put(q,Math.max(0,q.count()-groups.getOrDefault(group,new LinkedHashMap<>()).size())));
            return Map.copyOf(result);
        }
        public synchronized List<String> refill(String group,List<EligibilityQuery> targets,List<HeldCandidate> offered,int maxAccepted) {
            var missing=deficits(group,targets);
            if(offered.size()>100 || maxAccepted<0 || maxAccepted>100 || offered.stream().map(HeldCandidate::workerId).distinct().count()!=offered.size())
                throw new IllegalArgumentException();
            int limit=Math.min(maxAccepted,missing.values().stream().mapToInt(Integer::intValue).max().orElse(0));
            var pool=groups.computeIfAbsent(group,k->new LinkedHashMap<>());var accepted=new ArrayList<String>();
            for(var candidate:offered)if(accepted.size()<limit && pool.putIfAbsent(candidate.workerId(),candidate)==null)accepted.add(candidate.workerId());
            return List.copyOf(accepted);
        }
        public synchronized Map<TaskItemWorkerSelector,List<HeldCandidate>> take(String group,Map<TaskItemWorkerSelector,Integer> limits) {
            if(limits.size()>100 || limits.values().stream().anyMatch(n->n<1 || n>100) || limits.values().stream().mapToInt(Integer::intValue).sum()>100)
                throw new IllegalArgumentException();
            limits.keySet().forEach(selector->validateSelector(group,selector));
            var result=new LinkedHashMap<TaskItemWorkerSelector,List<HeldCandidate>>();
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
        @Override protected EligibilityQuery normalize(String group,Map<String,?> expression,int count,boolean selector) {
            normalizations++;
            if(!Set.of("pool").containsAll(expression.keySet()))throw new IllegalArgumentException("unsupported pool query");
            var result=new LinkedHashMap<String,List<String>>();
            expression.forEach((key,value)->result.put(key,((List<?>)value).stream()
                    .map(String.class::cast).distinct().sorted().toList()));
            return new EligibilityQuery(result,count);
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
