package com.xa.mass.workermatching.rules;

import com.xa.mass.kernel.assignment.*;
import com.xa.mass.kernel.assignment.WorkerMatching.*;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.workermatching.*;
import io.lettuce.core.*;
import io.lettuce.core.api.*;
import io.lettuce.core.api.sync.*;
import io.lettuce.core.codec.StringCodec;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CountryPoolPolicyTest {
    final RedisClient client=mock(RedisClient.class);
    @SuppressWarnings("unchecked") final StatefulRedisConnection<String,String> connection=mock(StatefulRedisConnection.class);
    @SuppressWarnings("unchecked") final RedisCommands<String,String> redis=mock(RedisCommands.class);
    final AtomicLong clock=new AtomicLong(1000);
    final MatchingStorage storage=new MatchingStorage(client,new RedisKeyspace("test_country_buckets"),clock::get);
    final CandidatePool pool=new CandidatePool(storage);
    final CountryPoolPolicy policy=new CountryPoolPolicy(storage,pool);
    final Map<String,String> facts=new HashMap<>();
    final List<List<String>> reads=new ArrayList<>();
    final EligibilityQuery any=new EligibilityQuery(Map.of());
    @BeforeEach void setup() {
        when(client.connect(StringCodec.UTF8)).thenReturn(connection);when(connection.sync()).thenReturn(redis);
        when(redis.hmget(anyString(),any(String[].class))).thenAnswer(call->{
            assertEquals(storage.workerFactsKey("g"),call.getArgument(0));
            var ids=new ArrayList<String>();var result=new ArrayList<KeyValue<String,String>>();
            for(int i=1;i<call.getArguments().length;i++) {
                String id=call.getArgument(i);ids.add(id);
                result.add(facts.containsKey(id)?KeyValue.just(id,facts.get(id)):KeyValue.empty(id));
            }
            reads.add(List.copyOf(ids));return result;
        });
    }
    @AfterEach void close() { storage.close(); }
    EligibilityQuery country(String... values) { return new EligibilityQuery(Map.of("worker.country",List.of(values))); }
    List<HeldCandidate> offers(String... ids) {
        return Arrays.stream(ids).map(id->new HeldCandidate(id,20,2000)).toList();
    }
    void facts(String id,String country) { facts.put(id,"{\"country\":\""+country+"\"}"); }
    @Test void unionTargetsOverlapWithoutBecomingPerCountryQuotasAndConstrainedPrecedesAny() {
        facts("cn","CN");facts("us","US");facts("gb","GB");facts("jp","JP");
        var targets=new LinkedHashMap<EligibilityQuery,Integer>();
        targets.put(any,1);targets.put(country("CN","US","CN"),1);targets.put(country("US","GB"),1);
        assertEquals(List.of("us"),policy.refill("g",targets,offers("jp","us","cn","gb"),100));
        assertTrue(policy.deficits("g",targets).values().stream().allMatch(n->n==0));
        assertEquals(1,pool.viewBuckets("g"));assertEquals(9999,storage.availableCapacity());
        assertEquals(List.of(List.of("jp","us","cn","gb")),reads);
        clearInvocations(redis);
        var selected=PoolQueryFunctions.country(pool).execute().apply("g",Map.of("m",List.of("US","US")));
        assertEquals(new WorkerCandidate("us",20),selected.get("m"));verifyNoInteractions(redis);
        assertEquals(0,pool.viewBuckets("g"));assertEquals(10000,storage.availableCapacity());
    }
    @Test void equivalentCountryTargetsMergeMaxAndCountOneUnion() {
        try(var catalog=new RedisWorkerMatchingCatalog(storage,Map.of("country",policy),
                Map.of("worker.country",PoolQueryFunctions.country(pool)),
                Map.of("g",new MatchingGroup(Set.of("country"),Set.of("worker.country"))),Map.of())) {
            var target=new RefillTarget("country",country("CN","US"),3);
            var declarations=catalog.normalizeRefill("g",List.of(
                    new RefillTarget("country",country("US","CN","US"),1),target));
            assertEquals(List.of(target),declarations);
            verifyNoInteractions(redis);
            facts("us","US");facts("cn","CN");facts("gb","GB");
            assertEquals(2,catalog.refill("g",declarations,offers("us","cn","gb")));
            assertEquals(Map.of(target.target(),1),policy.deficits("g",Map.of(target.target(),3)));
            assertEquals(9998,storage.availableCapacity());
        }
    }
    @Test void fullTargetsUseOneCountSnapshotAndOneFactsReadWithoutCountryPaging() {
        var targets=new ArrayList<RefillTarget>();
        for(int i=0;i<200;i++) targets.add(new RefillTarget("country",country(""+(char)('A'+i/26)+(char)('A'+i%26)),1));
        facts("late","HR"); // coordinate 199, beyond a 100-target first page
        var traced=spy(policy);
        try(var catalog=new RedisWorkerMatchingCatalog(storage,Map.of("country",traced),
                Map.of("worker.country",PoolQueryFunctions.country(pool)),
                Map.of("g",new MatchingGroup(Set.of("country"),Set.of("worker.country"))),Map.of())) {
            assertEquals(Set.of("g"),catalog.groupsNeedingRefill(Map.of("g",targets)));
            verify(traced).deficits(eq("g"),argThat(map->map.size()==200));
            assertEquals(1,catalog.refill("g",targets,offers("late")));
            verify(traced).refill(eq("g"),argThat(map->map.size()==200),anyList(),eq(100));
            assertEquals(1,reads.size());
            long before=pool.visits().countBuckets();
            policy.deficits("g",targets.stream().collect(java.util.stream.Collectors.toMap(RefillTarget::target,RefillTarget::count)));
            assertEquals(1,pool.visits().countBuckets()-before);
            assertEquals(0,pool.visits().selectedEntries());
        }
    }
    @Test void invalidOrMissingCountrySkipsButCorruptFactsFailBeforeAdmission() {
        facts("valid","CN");facts("lowercase","cn");facts.put("number","{\"country\":42}");facts.put("empty","{}");
        facts.put("corrupt","[]");
        assertThrows(IllegalArgumentException.class,()->policy.refill("g",Map.of(any,100),offers("valid","corrupt"),100));
        assertEquals(10000,storage.availableCapacity());assertEquals(0,pool.viewBuckets("g"));
        assertEquals(List.of("valid"),policy.refill("g",Map.of(any,100),offers("missing","empty","number","lowercase","valid"),100));
        clock.set(2000); assertTrue(PoolQueryFunctions.country(pool).execute().apply("g",Map.of("m",Map.of())).isEmpty());
        assertEquals(0,pool.viewBuckets("g"));assertEquals(10000,storage.availableCapacity());
    }
    @Test void readFailureAndTimeSpentReadingCannotCreateEntriesOrRenewDeadline() {
        when(redis.hmget(anyString(),any(String[].class))).thenThrow(new IllegalStateException("read failed"));
        assertThrows(IllegalStateException.class,()->policy.refill("g",Map.of(any,1),offers("w"),1));
        assertEquals(10000,storage.availableCapacity());
        doAnswer(call->{clock.set(2000);return List.of(KeyValue.just("w","{\"country\":\"CN\"}"));})
                .when(redis).hmget(anyString(),any(String[].class));
        assertTrue(policy.refill("g",Map.of(any,1),offers("w"),1).isEmpty());
        assertEquals(10000,storage.availableCapacity());
    }
    @Test void multiCountryTakeKeepsAdmissionOrderAndDoesNotVisitUnrelatedEntries() {
        facts("a","US");facts("b","CN");facts("c","JP");facts("d","CN");
        policy.refill("g",Map.of(any,4),offers("a","b","c","d"),4);
        var requests=new LinkedHashMap<String,Object>();requests.put("first",List.of("CN","US"));requests.put("second",List.of("US","CN"));
        var taken=PoolQueryFunctions.country(pool).execute().apply("g",requests);
        assertEquals(List.of("a","b"),taken.values().stream().map(WorkerCandidate::workerId).toList());
        assertEquals(2,pool.visits().selectedEntries());
        assertTrue(PoolQueryFunctions.country(pool).execute().apply("other",requests).isEmpty());
        assertEquals(2,pool.viewBuckets("g"));
    }
}
