package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex.HeldCandidate;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex.TaskQuery;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import com.xa.mass.workermatching.rules.DefaultRuleHandler;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.*;
import java.util.function.Supplier;
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
    final CountingRule rule=new CountingRule();
    final JsonMapper json=JsonMapper.builder().build();
    RedisWorkerMatchingCatalog catalog;
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
        catalog=new RedisWorkerMatchingCatalog(client,new RedisKeyspace("test_refill_batch"),
                Map.of("worker.default",new DefaultRuleHandler(),"test.pool",rule),
                Map.of("g1",Set.of("test.pool"),"g2",Set.of("test.pool")),Map.of());
    }
    @AfterEach void close() { catalog.close(); }

    void bind(String task,String group,List<EligibilityQuery> targets) {
        bindings.put(task,json.writeValueAsString(Map.of("workerGroupId",group,"ruleId","test.pool","refillTargets",targets)));
    }
    static EligibilityQuery pool(int count,String... values) {
        return new EligibilityQuery(Map.of("pool",List.of(values)),count);
    }
    static Map<String,Long> offer(String... ids) {
        return Arrays.stream(ids).collect(java.util.stream.Collectors.toMap(id->id,id->10L,(a,b)->a,LinkedHashMap::new));
    }
    static List<HeldCandidate> acquire(List<String> ids) {
        return ids.stream().map(id->new HeldCandidate(id,20,System.currentTimeMillis()+1000)).toList();
    }

    @Test void oneBatchMergesTasksOnceAndReusesQueriesAcrossGroupAdmission() {
        bind("a","g1",List.of(pool(1,"US")));
        bind("b","g1",List.of(pool(2,"US","US")));
        bind("c","g2",List.of(pool(1,"CN")));
        rule.facts.putAll(Map.of("w1","US","w2","US","w3","US","w4","CN"));
        var tasks=new LinkedHashMap<>(catalog.prepareTaskQueries(Map.of("a","g1","b","g1","c","g2")));
        assertTrue(rule.compiled.isEmpty(),"HTTP/query preparation must not compile refill targets");
        var batch=catalog.prepareRefill(tasks);
        assertEquals(Set.of("g1","g2"),batch.groupsNeedingRefill());
        assertEquals(2,rule.compiled.size());
        assertTrue(rule.compiled.contains(pool(2,"US")),"duplicate targets must merge using MAX");
        int preparedNormalizations=rule.normalizations;
        clearInvocations(redis);
        tasks.clear(); // No later Group may reconstruct its inputs from the caller's Map.
        assertEquals(2,batch.refill("g1",offer("w1","w2","w3"),RefillBatchTest::acquire));
        assertEquals(1,batch.refill("g2",offer("w4"),RefillBatchTest::acquire));
        assertEquals(2,rule.compiled.size());
        assertEquals(preparedNormalizations,rule.normalizations);
        assertEquals(List.of(List.of("w1","w2","w3"),List.of("w4")),rule.snapshots);
        verifyNoInteractions(redis);
    }

    @Test void preparationHintIsNotAReservationAndAdmissionUsesCurrentStockAndProjection() {
        bind("a","g1",List.of(pool(1,"US")));
        rule.facts.putAll(Map.of("first","US","second","US"));
        var tasks=catalog.prepareTaskQueries(Map.of("a","g1"));
        var stale=catalog.prepareRefill(tasks);
        var winner=catalog.prepareRefill(tasks);
        assertEquals(1,winner.refill("g1",offer("first"),RefillBatchTest::acquire));
        int snapshots=rule.snapshots.size();
        assertEquals(0,stale.refill("g1",offer("second"),ids->{throw new AssertionError("stock is full");}));
        assertEquals(snapshots,rule.snapshots.size());
        assertEquals(1,tasks.get("a").take(Map.of(ANY,1)).get(ANY).size());
        var full=catalog.prepareRefill(tasks);
        assertEquals(Set.of("g1"),full.groupsNeedingRefill());
        rule.facts.put("second","CN");
        assertEquals(0,full.refill("g1",offer("second"),ids->{throw new AssertionError("projection changed");}));
        assertTrue(tasks.get("a").take(Map.of(ANY,1)).get(ANY).isEmpty());
    }

    @Test void foreignGroupsAreRejectedBeforeProjectionOrRenewal() {
        bind("a","g1",List.of(pool(1,"US")));
        var batch=catalog.prepareRefill(catalog.prepareTaskQueries(Map.of("a","g1")));
        assertThrows(IllegalArgumentException.class,()->batch.refill("g2",offer("w"),ids->{throw new AssertionError();}));
        assertTrue(rule.snapshots.isEmpty());
        assertThrows(UnsupportedOperationException.class,()->batch.groupsNeedingRefill().clear());
    }

    @Test void onlyVisitedQueryPagesCompileAndNextRoundDoesNotRetainTheCompilation() {
        var first=new ArrayList<EligibilityQuery>();var second=new ArrayList<EligibilityQuery>();
        for(int i=0;i<200;i++)(i<100?first:second).add(pool(1,String.format("p%03d",i)));
        bind("a","g1",first);bind("b","g1",second);
        var tasks=catalog.prepareTaskQueries(Map.of("a","g1","b","g1"));
        var batch=catalog.prepareRefill(tasks);
        assertEquals(100,rule.compiled.size());
        assertEquals(0,batch.refill("g1",offer("missing"),ids->{throw new AssertionError();}));
        assertEquals(100,rule.compiled.size(),"rechecking the selected page must reuse compilation");
        catalog.prepareRefill(tasks);
        assertEquals(200,rule.compiled.size(),"the next round compiles its own visited page");
    }

    static final class CountingRule implements RuleHandler {
        final Map<String,String> facts=new HashMap<>();
        final List<EligibilityQuery> compiled=new ArrayList<>();
        final List<List<String>> snapshots=new ArrayList<>();
        int normalizations;
        @Override public Bound bind(Supplier<RedisCommands<String,String>> commands,String base,Set<String> enabled) {
            return new Bound() {
                @Override public EligibilityQuery normalize(Map<String,?> expression,int count) {
                    normalizations++;
                    var result=new LinkedHashMap<String,List<String>>();
                    expression.forEach((key,value)->result.put(key,((List<?>)value).stream()
                            .map(String.class::cast).distinct().sorted().toList()));
                    return new EligibilityQuery(result,count);
                }
                @Override public Query compile(EligibilityQuery query) {
                    compiled.add(query);
                    return member->member.projection() instanceof String pool
                            && (query.query().isEmpty() || query.query().get("pool").contains(pool));
                }
                @Override public Map<String,Member> snapshot(List<String> ids) {
                    snapshots.add(List.copyOf(ids));
                    var result=new LinkedHashMap<String,Member>();
                    ids.forEach(id->{if(facts.containsKey(id))result.put(id,new Member(id,facts.get(id)));});
                    return result;
                }
            };
        }
    }
}
