package com.xa.mass.server.assembly.matching;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.*;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex.TaskQuery;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex.HeldCandidate;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.redis.RedisWorkerScoreCore;
import com.xa.mass.workermatching.RefillTarget;
import com.xa.mass.workermatching.RuleHandler;
import com.xa.mass.workermatching.rules.*;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.server.testsupport.BucketRuleHandler;
import com.xa.mass.workermatching.RedisWorkerMatchingCatalog;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import com.xa.mass.workermatching.WorkerMatchingCatalog.MutationStatus;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.event.command.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;

@Tag("redis-owner")
class RedisWorkerMatchingCatalogIntegrationTest {
    private RedisTestScope testScope;
    private RedisKeyspace keyspace;
    private RedisClient redisClient;
    private StatefulRedisConnection<String,String> connection;
    private RedisCommands<String,String> redis;
    private RedisWorkerMatchingCatalog catalog;
    private RedisWorkerScoreCore scores;
    private final List<String> commandTypes=new CopyOnWriteArrayList<>();
    private final List<String> refillStages=new ArrayList<>();
    private java.util.function.Consumer<List<String>> beforeQualification=ids->{};
    private java.util.function.Consumer<List<String>> afterAdmission=ids->{};
    private static Map<String,RuleHandler> handlers(RedisRuleStorage storage,Map<String,Set<String>> groups) {
        return Map.of("worker.default",new DefaultRuleHandler(storage,groups),"worker.country",new CountryRuleHandler(storage),
                "worker.messaging.available",new MessagingRuleHandler(storage),"proof.worker.facts",new ProofFactsRuleHandler(storage));
    }
    private static final EligibilityQuery ANY=EligibilityQuery.parse(Map.of());
    private static final EligibilityQuery CN=country("CN");
    private static final EligibilityQuery US=country("US");
    @BeforeEach void setUp() {
        testScope=RedisTestScope.create("rule_owner"); keyspace=testScope.keyspace();
        redisClient=RedisClient.create(REDIS_URL);
        redisClient.addListener(new CommandListener() {
            @Override public void commandStarted(CommandStartedEvent event) { commandTypes.add(event.getCommand().getType().toString()); }
        });
        connection=redisClient.connect(); redis=connection.sync();
        scores=new RedisWorkerScoreCore(redisClient,keyspace);
        catalog=createCatalog(Map.of("g",Set.of("worker.country","worker.messaging.available","proof.worker.facts")),Map.of());
    }
    private RedisWorkerMatchingCatalog createCatalog(Map<String,Set<String>> groups,
            Map<String,Map<String,List<RefillTarget>>> defaults) {
        var storage=new RedisRuleStorage(redisClient,keyspace);
        var traced=new LinkedHashMap<>(handlers(storage,groups)); traced.replaceAll((id,handler)->trace(handler));
        return new RedisWorkerMatchingCatalog(storage,traced,groups,defaults);
    }
    private RuleHandler trace(RuleHandler handler) {
        return new RuleHandler() {
            public EligibilityQuery normalizeQuery(String group,EligibilityQuery query) { return handler.normalizeQuery(group,query); }
            public Map<EligibilityQuery,Integer> deficits(String group,Map<EligibilityQuery,Integer> targets) { return handler.deficits(group,targets); }
            public List<String> refill(String group,Map<EligibilityQuery,Integer> targets,List<HeldCandidate> offered,int maxAccepted) {
                var ids=offered.stream().map(HeldCandidate::workerId).toList();
                refillStages.add("qualification"); beforeQualification.accept(ids);
                var result=handler.refill(group,targets,offered,maxAccepted); afterAdmission.accept(ids); return result;
            }
            public Map<EligibilityQuery,List<HeldCandidate>> take(String group,Map<EligibilityQuery,Integer> limits) {
                return handler.take(group,limits);
            }
        };
    }
    @AfterEach void tearDown() {
        if(catalog!=null)catalog.close();
        if(scores!=null)scores.close();
        if(redis!=null)testScope.cleanup(redis);
        if(connection!=null)connection.close();
        if(redisClient!=null)redisClient.shutdown();
    }
    private static EligibilityQuery country(String country) {
        return EligibilityQuery.parse(Map.of("worker.country",List.of(country)));
    }
    private TaskQuery bound(String task,String rule) {
        assertThat(catalog.bindTaskRule(task,"g",rule, null).status()).isIn(MutationStatus.APPLIED,MutationStatus.UNCHANGED);
        return catalog.prepareTaskQueries(Map.of(task,"g")).get(task);
    }
    private static Map<String,String> messageFacts(String country,String phone) {
        return Map.of("country",country,"phone",phone,"messaging.enabled","true");
    }
    private String bindingsKey() { return keyspace.base()+":matching:task:rules"; }
    private String indexKey() { return keyspace.base()+":matching:worker:index:Zw:country"; }
    private void useBucketRule(boolean failSnapshot) {
        catalog.close();
        var groups=Map.of("g",Set.of("worker.country",BucketRuleHandler.ID));
        var storage=new RedisRuleStorage(redisClient,keyspace,Map.of(BucketRuleHandler.ID,BucketRuleHandler.indexes()),System::currentTimeMillis);
        var rules=new LinkedHashMap<>(handlers(storage,groups)); rules.put(BucketRuleHandler.ID,new BucketRuleHandler(storage,failSnapshot));
        catalog=new RedisWorkerMatchingCatalog(storage,rules,groups,Map.of());
    }
    @Test void independentBucketLayoutSharesStockAndRetainsBatchCommandBudgets() {
        useBucketRule(false);
        var facts=new LinkedHashMap<String,Map<String,String>>();
        for(int i=0;i<100;i++)facts.put("w"+i,Map.of("country","CN","testBucket",i%2==0?"red":"blue"));
        commandTypes.clear();catalog.upsertWorkerFactsBatch("g",facts);assertThat(commandTypes).containsExactly("EVAL");
        hot("g",List.copyOf(facts.keySet()));
        var target=new RefillTarget(Map.of("test.bucket",List.of("blue","red")),100);
        for(String task:List.of("a","b"))assertThat(catalog.bindTaskRule(task,"g",BucketRuleHandler.ID,List.of(target)).status()).isEqualTo(MutationStatus.APPLIED);
        commandTypes.clear();var prepared=catalog.prepareTaskQueries(Map.of("a","g","b","g"));assertThat(commandTypes).containsExactly("HMGET");
        commandTypes.clear();assertThat(refillPrepared(prepared)).isEqualTo(100);
        assertThat(commandTypes).containsExactly("EVAL","EVAL","HMGET");
        commandTypes.clear();assertThat(refillPrepared(prepared)).isZero();
        var selector=EligibilityQuery.parse(Map.of("test.bucket",List.of("red","blue")));
        var first=prepared.get("a").take(Map.of(selector,40)).get(selector);
        var second=prepared.get("b").take(Map.of(selector,100)).get(selector);
        assertThat(first).hasSize(40);assertThat(second).hasSize(60);assertThat(commandTypes).isEmpty();
        var ids=new HashSet<String>();first.forEach(h->assertThat(ids.add(h.workerId())).isTrue());second.forEach(h->assertThat(ids.add(h.workerId())).isTrue());
    }
    @Test void bucketFactsMutationDirtyFenceAndLaterRefillConverge() throws Exception {
        useBucketRule(false);catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("testBucket","red")));hot("g",List.of("w"));
        catalog.bindTaskRule("bucket","g",BucketRuleHandler.ID,List.of(new RefillTarget(Map.of("test.bucket",List.of("red","blue")),1)));
        var prepared=prepare("bucket");assertThat(refillPrepared(prepared)).isEqualTo(1);
        var held=prepared.get("bucket").take(Map.of(ANY,1)).get(ANY).getFirst();
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("testBucket","blue")));
        scores.markCurrentLeasesDirty("g",List.of("w"));
        assertThat(scores.confirmActiveHotScoreLeases("g",Map.of("w",held.score()),System.currentTimeMillis()+5000).get("w").status())
                .isNotEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED);
        Thread.sleep(Math.max(0,held.expiresAtMillis()-System.currentTimeMillis()+150));
        assertThat(refillPrepared(prepared)).isEqualTo(1);
        var blue=EligibilityQuery.parse(Map.of("test.bucket",List.of("blue")));
        assertThat(prepared.get("bucket").take(Map.of(blue,1)).get(blue)).extracting(HeldCandidate::workerId).containsExactly("w");
        catalog.patchWorkerPlatformProperties("g","w",Map.of("testEnabled","no"));
        assertThat(redis.hget(keyspace.base()+":matching:worker:index:Zw:test_buckets","w")).isNull();
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("testBucket","red")));
        assertThat(catalog.loadWorkerFacts("g",List.of("w")).get("w").platformProperties()).containsEntry("testEnabled","no");
        catalog.patchWorkerPlatformProperties("g","w",Map.of("testEnabled","yes"));
        assertThat(redis.hget(keyspace.base()+":matching:worker:index:Zw:test_buckets","w")).contains("red");
    }
    @Test void bucketCorruptionFailsBeforeFactsOrAnotherIndexWrite() {
        useBucketRule(false);catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("testBucket","red","country","CN")));
        String key=keyspace.base()+":matching:worker:index:Zw:test_buckets";
        String set=redis.scan(io.lettuce.core.ScanCursor.INITIAL,new io.lettuce.core.ScanArgs().match(key+":bucket:*").limit(100)).getKeys().getFirst();
        redis.unlink(set);redis.set(set,"corrupt"); Double country=redis.zscore(indexKey(),"w");
        assertThatThrownBy(()->catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("testBucket","blue","country","US")))).isInstanceOf(RuntimeException.class);
        assertThat(catalog.loadWorkerFacts("g",List.of("w")).get("w").workerProperties()).containsEntry("testBucket","red");
        assertThat(redis.zscore(indexKey(),"w")).isEqualTo(country);
        redis.unlink(set);redis.hset(key,"w","{}");
        assertThatThrownBy(()->catalog.patchWorkerPlatformProperties("g","w",Map.of("testEnabled","no"))).isInstanceOf(RuntimeException.class);
        assertThat(catalog.loadWorkerFacts("g",List.of("w")).get("w").platformProperties()).isEmpty();
    }
    @Test void bucketProjectionFailureLeavesTheLeaseWithoutStockOrRecoveryRead() {
        useBucketRule(true);catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("testBucket","red")));hot("g",List.of("w"));
        var query=bound("bucket",BucketRuleHandler.ID);
        var held=acquire("g",offer("g",100));
        var batch=catalog.prepareRefill(prepare("bucket"));
        commandTypes.clear();
        assertThatThrownBy(()->batch.refill("g",held)).isInstanceOf(IllegalStateException.class);
        assertThat(commandTypes).isEmpty();
        commandTypes.clear();assertThat(query.take(Map.of(ANY,1)).get(ANY)).isEmpty();assertThat(commandTypes).isEmpty();
        assertThat(scores.getScoreStates("g",List.of("w")).get("w").score()).isEqualTo(held.getFirst().score());
    }
    @Test void rebuildUsesOnlyTheEnabledHandlersDeclaredRootAndDescendants() {
        useBucketRule(false);
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("testBucket","red","country","CN")));
        String root=keyspace.base()+":matching:worker:index:Zw:test_buckets";
        redis.hset(root,"stale","{}"); redis.sadd(root+":orphan","stale");
        redis.set(root+"_neighbor","kept"); redis.zadd(indexKey(),1,"other-rule-member");
        catalog.close();
        var groups=Map.of("g",Set.of(BucketRuleHandler.ID));
        var storage=new RedisRuleStorage(redisClient,keyspace,Map.of(BucketRuleHandler.ID,BucketRuleHandler.indexes()),System::currentTimeMillis);
        var rules=new LinkedHashMap<>(handlers(storage,groups)); rules.put(BucketRuleHandler.ID,new BucketRuleHandler(storage));
        catalog=new RedisWorkerMatchingCatalog(storage,rules,groups,Map.of());
        catalog.rebuildIndexes();
        assertThat(redis.hkeys(root)).containsExactly("w");
        assertThat(redis.exists(root+":orphan")).isZero();
        assertThat(redis.get(root+"_neighbor")).isEqualTo("kept");
        assertThat(redis.zscore(indexKey(),"other-rule-member")).isEqualTo(1);
        hot("g",List.of("w")); var query=bound("rebuilt",BucketRuleHandler.ID); refill("rebuilt");
        assertThat(query.take(Map.of(ANY,1)).get(ANY)).extracting(HeldCandidate::workerId).containsExactly("w");
    }
    @Test
    void boundedBatchReplacesWholeMapsAndPreservesIndependentPlatformProperties() {
        catalog.upsertWorkerFactsBatch("g", Map.of("w1", Map.of("old", "1", "omitted", "value")));
        catalog.patchWorkerPlatformProperties("g", "w1", Map.of("policy", "retained"));
        Map<String, Map<String, String>> batch = new LinkedHashMap<>();
        batch.put("w1", Map.of("network.type", "cellular", "empty", ""));
        batch.put("w2", Map.of());
        assertThat(catalog.upsertWorkerFactsBatch("g", batch).values())
                .allMatch(result -> result.status() == MutationStatus.APPLIED);
        var facts = catalog.loadWorkerFacts("g", List.of("w1", "w2"));
        assertThat(facts.get("w1").workerProperties()).isEqualTo(batch.get("w1"));
        assertThat(facts.get("w1").platformProperties()).isEqualTo(Map.of("policy", "retained"));
        assertThat(facts.get("w2").workerProperties()).isEmpty();
        Map<String, String> reversed = new LinkedHashMap<>();
        reversed.put("empty", "");
        reversed.put("network.type", "cellular");
        assertThat(catalog.upsertWorkerFactsBatch("g", Map.of("w1", reversed, "w2", Map.of())).values())
                .allMatch(result -> result.status() == MutationStatus.UNCHANGED);
        assertThat(catalog.upsertWorkerFactsBatch("g", Map.of("w1", Map.of())).get("w1").status())
                .isEqualTo(MutationStatus.APPLIED);
        assertThat(catalog.loadWorkerFacts("g", List.of("w1")).get("w1").workerProperties()).isEmpty();
    }

    @Test
    void storedLegacyFactsRemainReadableUntilACompleteObservationReplacesThem() {
        // Historical storage fixture only; production has no legacy single-Worker writer.
        redis.hset(keyspace.base() + ":matching:worker:facts:g", "w1",
                "{\"legacy-number\":87,\"clientWorkerKey\":\"old-key\"}");
        assertThat(catalog.loadWorkerFacts("g", List.of("w1")).get("w1").workerProperties())
                .isEqualTo(Map.of("legacy-number", 87L, "clientWorkerKey", "old-key"));
        catalog.patchWorkerPlatformProperties("g", "w1", Map.of("policy", "retained"));
        catalog.upsertWorkerFactsBatch("g", Map.of("w1", Map.of("current", "observed")));
        var current = catalog.loadWorkerFacts("g", List.of("w1")).get("w1");
        assertThat(current.workerProperties()).isEqualTo(Map.of("current", "observed"));
        assertThat(current.platformProperties()).isEqualTo(Map.of("policy", "retained"));
    }

    @Test
    void firstEmptyObservationCreatesFactsAndEnablesIndependentPlatformPatch() {
        assertThat(catalog.loadWorkerFacts("g", List.of("w"))).containsEntry("w", null);
        assertThat(catalog.patchWorkerPlatformProperties("g", "w", Map.of("pool", "a")).status())
                .isEqualTo(MutationStatus.NOT_FOUND);
        assertThat(catalog.upsertWorkerFactsBatch("g", Map.of("w", Map.of())).get("w").status())
                .isEqualTo(MutationStatus.APPLIED);
        assertThat(catalog.loadWorkerFacts("g", List.of("w")).get("w").workerProperties()).isEmpty();
        assertThat(catalog.patchWorkerPlatformProperties("g", "w", Map.of("pool", "a")).status())
                .isEqualTo(MutationStatus.APPLIED);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void runtimeBatchRejectsNonStringValuesAndBoundsBeforeWriting() {
        Map<String, Map<String, String>> batch = new LinkedHashMap<>();
        batch.put("valid", Map.of("key", "value"));
        batch.put("numeric", (Map) Map.of("key", 87));
        batch.put("nested", (Map) Map.of("key", Map.of()));
        batch.put("null-value", Collections.singletonMap("key", null));
        batch.put("blank-key", Map.of(" ", "value"));
        var results = catalog.upsertWorkerFactsBatch("g", batch);
        assertThat(results.get("valid").status()).isEqualTo(MutationStatus.APPLIED);
        assertThat(results.entrySet()).filteredOn(entry -> !entry.getKey().equals("valid"))
                .allMatch(entry -> entry.getValue().status() == MutationStatus.INVALID);
        assertThat(redis.hlen(keyspace.base() + ":matching:worker:facts:g")).isEqualTo(1);
        assertThatThrownBy(() -> catalog.upsertWorkerFactsBatch("g", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, Map<String, String>> maximum = new LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            maximum.put("worker-" + i, Map.of("number", Integer.toString(i)));
        }
        assertThat(catalog.upsertWorkerFactsBatch("g", maximum)).hasSize(100);
        maximum.put("overflow", Map.of());
        assertThatThrownBy(() -> catalog.upsertWorkerFactsBatch("g", maximum))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(redis.hexists(keyspace.base() + ":matching:worker:facts:g", "overflow")).isFalse();
    }

    @Test
    void concurrentObservationBatchesNeverExposeHalfAWorkerMap() throws Exception {
        Map<String, String> live = Map.of("a", "live", "b", "live");
        Map<String, String> other = Map.of("a", "other", "b", "other");
        catalog.upsertWorkerFactsBatch("g", Map.of("w", live));
        CountDownLatch start = new CountDownLatch(1);
        try (var competing = createCatalog( Map.of("g",Set.of("worker.country","worker.messaging.available","proof.worker.facts")), Map.of());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 100; i++) {
                    catalog.upsertWorkerFactsBatch("g", Map.of("w", live));
                }
                return null;
            });
            var second = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 100; i++) {
                    competing.upsertWorkerFactsBatch("g", Map.of("w", other));
                }
                return null;
            });
            start.countDown();
            for (int i = 0; i < 100; i++) {
                assertThat(catalog.loadWorkerFacts("g", List.of("w")).get("w").workerProperties())
                        .isIn(live, other);
            }
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void workerRefreshReplacesWorkerFactsAndPreservesPlatformFacts() {
        assertThat(catalog.upsertWorkerFactsBatch("group-1",
                Map.of("worker-1", Map.of("region", "cn", "capacity", "1")))
                .get("worker-1").status()).isEqualTo(MutationStatus.APPLIED);
        assertThat(catalog.patchWorkerPlatformProperties(
                "group-1",
                "worker-1",
                Map.of("battery", 90, "network", "wifi")
        ).status()).isEqualTo(MutationStatus.APPLIED);

        assertThat(catalog.upsertWorkerFactsBatch("group-1",
                Map.of("worker-1", Map.of("region", "us", "capacity", "2")))
                .get("worker-1").status()).isEqualTo(MutationStatus.APPLIED);
        var refreshed = catalog.loadWorkerFacts(
                "group-1",
                List.of("worker-1", "missing")
        );

        assertThat(refreshed.get("worker-1").workerProperties())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "region", "us",
                        "capacity", "2"
                ));
        assertThat(refreshed.get("worker-1").platformProperties())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "battery", 90L,
                        "network", "wifi"
                ));
        assertThat(refreshed).containsEntry("missing", null);

        assertThat(catalog.patchWorkerPlatformProperties(
                "group-1",
                "worker-1",
                java.util.Collections.singletonMap("battery", null)
        ).status()).isEqualTo(MutationStatus.APPLIED);
        assertThat(catalog.loadWorkerFacts(
                "group-1",
                List.of("worker-1")
        ).get("worker-1").platformProperties())
                .containsExactlyEntriesOf(Map.of("network", "wifi"));
        assertThat(catalog.patchWorkerPlatformProperties(
                "group-1",
                "missing",
                Map.of("battery", 10)
        ).status()).isEqualTo(MutationStatus.NOT_FOUND);
    }


    private void hot(String group,List<String> ids) {
        scores.initializeRegisteredScores(group,ids);
        var evidence=new LinkedHashMap<String,Long>();
        ids.forEach(id -> evidence.put(id,System.currentTimeMillis()-1000));
        scores.applyServiceabilityEvidence(group,evidence,WorkerScoreCore.WorkerScorePolarity.HOT_ACQUIRE);
    }
    // The fixture supplies a closed Kernel-issued batch. Matching cannot choose identities.
    private Map<String,Long> offer(String group,int limit) {
        refillStages.add("observe");
        return scores.observeDueHotScoreCandidates(group,null,limit);
    }
    private List<HeldCandidate> acquire(String group,Map<String,Long> offered) {
        refillStages.add("acquire");
        long until=System.currentTimeMillis()+1000;
        return scores.acquireObservedHotScoreLeases(group,offered,until).entrySet().stream()
                .filter(e -> e.getValue().status()==WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED)
                .map(e -> new HeldCandidate(e.getKey(),e.getValue().score(),until)).toList();
    }
    private int refillPrepared(RedisWorkerMatchingCatalog owner,String group,Map<String,TaskQuery> tasks,int limit) {
        var batch=owner.prepareRefill(tasks);
        if(!batch.groupsNeedingRefill().contains(group))return 0;
        var offered=offer(group,limit);
        var held=acquire(group,offered);
        return held.isEmpty()?0:batch.refill(group,held);
    }
    private int refillPrepared(Map<String,TaskQuery> tasks) { return refillPrepared(catalog,"g",tasks,100); }
    private static RefillTarget target(int count,String country) {
        return new RefillTarget(Map.of("worker.country",List.of(country)),count);
    }
    private Map<String,TaskQuery> prepare(String... tasks) {
        var groups=new LinkedHashMap<String,String>(); for(String task:tasks)groups.put(task,"g");
        return catalog.prepareTaskQueries(groups);
    }
    private int refill(String... tasks) { return refillPrepared(prepare(tasks)); }

    @Test void explicitDefaultBindingDoesNotRequireFactsAndMissingBindingsNeverRelaxQueries() {
        var query=bound("default",WorkerMatchingCatalog.DEFAULT_RULE_ID);
        hot("g",List.of("no-facts"));
        assertThat(query.take(Map.of(ANY,1)).get(ANY)).isEmpty();
        assertThat(refill("default")).isEqualTo(1);
        assertThat(query.take(Map.of(ANY,1)).get(ANY)).extracting(HeldCandidate::workerId).containsExactly("no-facts");
        assertThat(catalog.loadWorkerFacts("g",List.of("no-facts"))).containsEntry("no-facts",null);
        assertThat(catalog.prepareTaskQueries(Map.of("missing","g"))).containsEntry("missing",null);
        assertThat(catalog.prepareTaskQueries(Map.of("default","wrong-group"))).containsEntry("default",null);
        redis.hset(bindingsKey(),"default","{bad");
        assertThat(prepare("default")).containsEntry("default",null);
        assertThat(catalog.bindTaskRule("default","g",WorkerMatchingCatalog.DEFAULT_RULE_ID,null).status()).isEqualTo(MutationStatus.CONFLICT);
    }
    @Test void namedRulesRejectIdsAndAnyUsesOnlyTheirHeldMembership() {
        catalog.upsertWorkerFactsBatch("g",Map.of("cn",Map.of("country","CN"),"us",Map.of("country","US"),"empty",Map.of()));
        hot("g",List.of("cn","us","empty"));
        var query=bound("country","worker.country");
        assertThat(refill("country")).isEqualTo(2);
        var ids=EligibilityQuery.parse(Map.of("workerId",List.of("cn","empty")));
        assertThatThrownBy(()->query.normalize(ids)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->query.take(Map.of(ids,1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(catalog.bindTaskRule("invalid-ids","g","worker.country",List.of(new RefillTarget(Map.of("workerId",List.of("cn")),1))).status())
                .isEqualTo(MutationStatus.INVALID);
        assertThat(redis.hexists(bindingsKey(),"invalid-ids")).isFalse();
        redis.hset(bindingsKey(),"old-ids","{\"workerGroupId\":\"g\",\"ruleId\":\"worker.country\",\"refillTargets\":[{\"query\":{\"workerId\":[\"cn\"]},\"count\":1}]}");
        assertThat(catalog.loadTaskBindings(List.of("old-ids"))).containsEntry("old-ids",null);
        assertThat(query.take(Map.of(CN,1)).get(CN)).extracting(HeldCandidate::workerId).containsExactly("cn");
        assertThat(query.take(Map.of(ANY,100)).get(ANY)).extracting(HeldCandidate::workerId).containsExactly("us");
    }
    @Test void sharedTargetsUseMaximumAndOtherTasksCanConsumeTheSameStock() {
        var facts=new LinkedHashMap<String,Map<String,String>>();
        for(int i=0;i<30;i++)facts.put("w"+i,Map.of("country","US"));
        catalog.upsertWorkerFactsBatch("g",facts); hot("g",List.copyOf(facts.keySet()));
        catalog.bindTaskRule("seed","g","worker.country",List.of(target(15,"US")));
        assertThat(refillPrepared(catalog,"g",prepare("seed"),15)).isEqualTo(15);
        catalog.bindTaskRule("a","g","worker.country",List.of(target(10,"US")));
        catalog.bindTaskRule("b","g","worker.country",List.of(target(20,"US")));
        assertThat(refill("a","b")).isEqualTo(5);
        var views=prepare("a","b"); commandTypes.clear();
        assertThat(refillPrepared(views)).isZero();
        assertThat(views.get("a").take(Map.of(US,20)).get(US)).hasSize(20);
        assertThat(views.get("b").take(Map.of(US,1)).get(US)).isEmpty();
        assertThat(commandTypes).isEmpty();
    }
    @Test void hundredTaskBindingsResolveWithOneHmgetAndNoFactsRead() {
        var groups=new LinkedHashMap<String,String>();
        for(int i=0;i<100;i++) { String id="t"+i; groups.put(id,"g"); bound(id,"worker.country"); }
        commandTypes.clear();
        assertThat(catalog.prepareTaskQueries(groups)).hasSize(100).doesNotContainValue(null);
        assertThat(commandTypes).containsExactly("HMGET");
    }
    @Test void hundredWorkersUseBoundedRefillCommandsAndZeroIoForConsumption() {
        var facts=new LinkedHashMap<String,Map<String,String>>();
        for(int i=0;i<100;i++)facts.put("w"+i,Map.of("country","CN"));
        bound("task","worker.country"); commandTypes.clear();
        assertThat(catalog.upsertWorkerFactsBatch("g",facts)).hasSize(100);
        assertThat(commandTypes).containsExactly("EVAL");
        hot("g",List.copyOf(facts.keySet()));
        var prepared=prepare("task"); commandTypes.clear();refillStages.clear();
        assertThat(refillPrepared(prepared)).isEqualTo(100);
        // Observation and first acquisition both precede the supplied-ID projection.
        assertThat(commandTypes).containsExactly("EVAL","EVAL","EVAL");
        assertThat(refillStages).containsExactly("observe","acquire","qualification");
        commandTypes.clear();
        assertThat(prepared.get("task").take(Map.of(CN,100)).get(CN)).hasSize(100);
        assertThat(commandTypes).isEmpty();
    }
    @Test void hundredDistinctTargetsUseOneObservationProjectionAndAcquisitionBatch() {
        var facts=new LinkedHashMap<String,Map<String,String>>();
        var targets=new ArrayList<RefillTarget>();
        for(int i=0;i<100;i++) {
            String code=""+(char)('A'+i/26)+(char)('A'+i%26);
            facts.put("w"+i,Map.of("country",code)); targets.add(target(1,code));
        }
        catalog.upsertWorkerFactsBatch("g",facts); hot("g",List.copyOf(facts.keySet()));
        catalog.bindTaskRule("task","g","worker.country",targets);
        var prepared=prepare("task"); commandTypes.clear();
        assertThat(refillPrepared(prepared)).isEqualTo(100);
        assertThat(commandTypes).containsExactly("EVAL","EVAL","EVAL");
        commandTypes.clear();
        assertThat(prepared.get("task").take(Map.of(ANY,100)).get(ANY)).hasSize(100);
        assertThat(commandTypes).isEmpty();
    }

    @Test void sparsePhoneTargetsReadCurrentFactsAfterTheCandidateLease() {
        catalog.upsertWorkerFactsBatch("g",Map.of("target",messageFacts("CN","rare")));
        hot("g",List.of("target"));
        var q=new RefillTarget(Map.of("worker.country",List.of("CN"),"worker.phone",List.of("rare")),1);
        catalog.bindTaskRule("messages","g","worker.messaging.available",List.of(q));
        var prepared=prepare("messages");
        var held=acquire("g",offer("g",100));
        catalog.upsertWorkerFactsBatch("g",Map.of("target",messageFacts("US","new-phone")));
        assertThat(catalog.prepareRefill(prepared).refill("g",held)).isZero();
        assertThat(prepared.get("messages").take(Map.of(ANY,1)).get(ANY)).isEmpty();
        assertThat(scores.getScoreStates("g",List.of("target")).get("target").score()).isEqualTo(held.getFirst().score());
    }

    @Test void offeredBatchIsClosedEvenWhenTheIndexContainsBetterWorkers() {
        catalog.upsertWorkerFactsBatch("g",Map.of("a",Map.of("country","US"),"b",Map.of("country","CN")));
        hot("g",List.of("a","b"));
        catalog.bindTaskRule("task","g","worker.country",List.of(target(1,"CN")));
        var prepared=prepare("task");
        var observed=offer("g",1);
        assertThat(observed.keySet()).containsExactly("a");
        var held=acquire("g",observed);
        commandTypes.clear();
        assertThat(catalog.prepareRefill(prepared).refill("g",held)).isZero();
        assertThat(commandTypes).containsExactly("EVAL"); // Supplied-ID projection only.
        assertThat(prepared.get("task").take(Map.of(ANY,1)).get(ANY)).isEmpty();
        assertThat(scores.observeDueHotScores("g",List.of("b"),null)).containsKey("b");
    }

    @Test void unmatchedWorkerIsAlreadyHeldAtProjectionAndBecomesDueWithoutRelease() throws Exception {
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","US")));
        hot("g",List.of("w"));
        catalog.bindTaskRule("task","g","worker.country",List.of(target(1,"CN")));
        var prepared=prepare("task");
        var held=acquire("g",offer("g",100));
        beforeQualification=ids->{
            assertThat(ids).containsExactly("w");
            var state=scores.getScoreStates("g",ids).get("w");
            assertThat(state.score()).isEqualTo(held.getFirst().score());
            assertThat(state.dirty()).isZero();
            assertThat(scores.observeDueHotScores("g",ids,null)).isEmpty();
        };
        assertThat(catalog.prepareRefill(prepared).refill("g",held)).isZero();
        assertThat(prepared.get("task").take(Map.of(ANY,1)).get(ANY)).isEmpty();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        Map<String,Long> due=Map.of();
        while(due.isEmpty() && System.nanoTime()<deadline) {
            due=scores.observeDueHotScores("g",List.of("w"),null);
            if(due.isEmpty())Thread.sleep(20);
        }
        assertThat(due).containsEntry("w",held.getFirst().score());
    }

    @Test void eligibilitiesShareOnePreviouslyAcquiredGroupBatchWithoutRenewingFences() {
        catalog.upsertWorkerFactsBatch("g",Map.of("cn",Map.of("country","CN"),"us",Map.of("country","US")));
        hot("g",List.of("cn","us"));
        catalog.bindTaskRule("country","g","worker.country",List.of(target(1,"CN")));
        catalog.bindTaskRule("default","g","worker.default",List.of(target(1,"US")));
        var prepared=prepare("country","default");var observed=offer("g",100);
        commandTypes.clear();refillStages.clear();
        var offered=acquire("g",observed);
        var original=new HashMap<String,HeldCandidate>();offered.forEach(held->original.put(held.workerId(),held));
        assertThat(catalog.prepareRefill(prepared).refill("g",offered)).isEqualTo(2);
        assertThat(commandTypes).containsExactly("EVAL","EVAL","EVAL");
        assertThat(refillStages).containsExactly("acquire","qualification","qualification");
        var delivered=new HashSet<String>();
        prepared.values().forEach(view->view.take(Map.of(ANY,100)).get(ANY).forEach(held->{
            assertThat(delivered.add(held.workerId())).isTrue();
            assertThat(held).isSameAs(original.get(held.workerId()));
            assertThat(held.score()).isNotEqualTo(observed.get(held.workerId()));
        }));
        assertThat(delivered).containsExactlyInAnyOrder("cn","us");
    }

    @Test void headAcquisitionReachesARareMatchWithoutSkippingUnmatchedWorkers() {
        var ids=IntStream.range(0,250).mapToObj(i->"w-%03d".formatted(i)).toList();
        long observed=(System.currentTimeMillis()/100-100)*WorkerScoreCore.SLOT_FACTOR+1;
        for(int start=0;start<ids.size();start+=100) {
            var facts=new LinkedHashMap<String,Map<String,String>>();
            for(String id:ids.subList(start,Math.min(start+100,ids.size()))) {
                facts.put(id,Map.of("country",id.equals("w-249")?"CN":"US"));
                redis.zadd(keyspace.base()+":worker:score:g",observed,id);
            }
            catalog.upsertWorkerFactsBatch("g",facts);
        }
        catalog.bindTaskRule("rare","g","worker.country",List.of(target(1,"CN")));
        var prepared=prepare("rare");
        int added=0;var visited=new ArrayList<String>();
        for(int round=0;round<3 && added==0;round++) {
            var batch=catalog.prepareRefill(prepared);
            var observedBatch=scores.observeDueHotScoreCandidates("g",null,100);
            visited.addAll(observedBatch.keySet());
            var held=acquire("g",observedBatch);
            added+=batch.refill("g",held);
        }
        assertThat(visited).containsExactlyElementsOf(ids);
        assertThat(added).isEqualTo(1);
        assertThat(prepared.get("rare").take(Map.of(CN,1)).get(CN))
                .extracting(HeldCandidate::workerId).containsExactly("w-249");
        assertThat(scores.getScoreStates("g",List.of("w-000")).get("w-000").score()).isNotEqualTo(observed);
    }

    @Test void discardedAcquisitionResponseLeavesNoStockAndCannotRescueTheOldFence() {
        hot("g",List.of("w"));bound("task","worker.default");
        var prepared=prepare("task");var observed=offer("g",100);
        // The owner committed, but its returned batch never reaches Matching.
        assertThat(acquire("g",observed)).hasSize(1);
        commandTypes.clear();assertThat(prepared.get("task").take(Map.of(ANY,1)).get(ANY)).isEmpty();
        assertThat(commandTypes).isEmpty();
        assertThat(scores.confirmActiveHotScoreLeases("g",Map.of("w",observed.get("w")),System.currentTimeMillis()+5000).get("w").status())
                .isEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.STALE);
        assertThat(refillPrepared(prepared)).isZero();
    }

    @Test void factsChangedBeforeProjectionAreReadAfterDirtyClearingAcquisition() {
        hot("g",List.of("w"));
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","CN")));
        scores.markCurrentLeasesDirty("g",List.of("w"));
        catalog.bindTaskRule("task","g","worker.country",List.of(target(1,"CN")));
        var prepared=prepare("task");
        var held=acquire("g",offer("g",100));
        beforeQualification=ids->{
            assertThat(scores.getScoreStates("g",ids).get("w").dirty()).isZero();
            catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","US")));
            assertThat(scores.markCurrentLeasesDirty("g",ids).get("w").status())
                    .isEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED);
        };
        assertThat(catalog.prepareRefill(prepared).refill("g",held)).isZero();
        assertThat(prepared.get("task").take(Map.of(CN,1)).get(CN)).isEmpty();
    }

    @Test void invalidationAfterAdmissionRejectsTheOriginalCandidateFence() {
        hot("g",List.of("w"));
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","CN")));
        scores.markCurrentLeasesDirty("g",List.of("w"));
        catalog.bindTaskRule("task","g","worker.country",List.of(target(1,"CN")));
        var prepared=prepare("task");
        var offered=acquire("g",offer("g",100));
        afterAdmission=ids->{
            catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","US")));
            assertThat(scores.markCurrentLeasesDirty("g",ids).get("w").status())
                    .isEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED);
        };
        assertThat(catalog.prepareRefill(prepared).refill("g",offered)).isEqualTo(1);
        var held=prepared.get("task").take(Map.of(CN,1)).get(CN).getFirst();
        assertThat(held).isSameAs(offered.getFirst());
        assertThat(scores.confirmActiveHotScoreLeases("g",Map.of("w",held.score()),System.currentTimeMillis()+5000)
                .get("w").status()).isEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.STALE);
    }

    @Test void factsDirtyingAnObservationBeforeLeaseCanStillRejectAcquisition() {
        hot("g",List.of("w"));
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","CN")));
        catalog.bindTaskRule("task","g","worker.country",List.of(target(1,"CN")));
        var prepared=prepare("task");var observed=offer("g",100);
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","US")));
        scores.markCurrentLeasesDirty("g",List.of("w"));
        assertThat(acquire("g",observed)).isEmpty();
        assertThat(prepared.get("task").take(Map.of(CN,1)).get(CN)).isEmpty();
    }

    @Test void dirtyStockCannotConfirmAndAnInitialFenceConfirmsOnlyOnce() throws Exception {
        hot("g",List.of("w")); bound("task",WorkerMatchingCatalog.DEFAULT_RULE_ID);
        refill("task"); scores.markCurrentLeasesDirty("g",List.of("w"));
        var held=prepare("task").get("task").take(Map.of(ANY,1)).get(ANY).getFirst();
        var rejected=scores.confirmActiveHotScoreLeases("g",Map.of("w",held.score()),System.currentTimeMillis()+5000);
        assertThat(rejected.get("w").status()).isNotEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED);
        // No compensation: the rejected initial hold expires, then refill obtains a new clean fence.
        Thread.sleep(Math.max(0,held.expiresAtMillis()-System.currentTimeMillis()+150));
        assertThat(refill("task")).isEqualTo(1);
        long clean=prepare("task").get("task").take(Map.of(ANY,1)).get(ANY).getFirst().score();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var operations=IntStream.range(0,20).mapToObj(i -> executor.submit(() -> scores.confirmActiveHotScoreLeases(
                    "g",Map.of("w",clean),System.currentTimeMillis()+5000).get("w").status())).toList();
            int applied=0; for(var operation:operations)if(operation.get()==WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED)applied++;
            assertThat(applied).isEqualTo(1);
        }
    }
    @Test void freshCatalogCannotConsumePreviousProcessStockOrClaimItsHold() {
        hot("g",List.of("w")); bound("task",WorkerMatchingCatalog.DEFAULT_RULE_ID); refill("task");
        try(var restarted=createCatalog(Map.of(),Map.of())) {
            var views=restarted.prepareTaskQueries(Map.of("task","g"));
            assertThat(views.get("task").take(Map.of(ANY,1)).get(ANY)).isEmpty();
            assertThat(refillPrepared(restarted,"g",views,100)).isZero();
        }
    }
    @Test void competingEligibilitiesRotateAcrossReturningCompatibleCapacity() throws Exception {
        catalog.upsertWorkerFactsBatch("g",Map.of("w",messageFacts("CN","phone")));
        hot("g",List.of("w"));
        bound("default","worker.default"); bound("country","worker.country"); bound("messaging","worker.messaging.available");
        var prepared=prepare("default","country","messaging");
        var winners=new ArrayList<String>();
        for(int round=0;round<6;round++) {
            assertThat(refillPrepared(prepared)).isEqualTo(1);
            for(var task:prepared.entrySet()) {
                var candidates=task.getValue().take(Map.of(ANY,1)).get(ANY);
                if(candidates.isEmpty())continue;
                winners.add(task.getKey());
                var confirmed=scores.confirmActiveHotScoreLeases("g",Map.of("w",candidates.getFirst().score()),
                        System.currentTimeMillis()+5000).get("w");
                assertThat(confirmed.status()).isEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED);
                assertThat(scores.releaseCompletedHotScoreHolds("g",Map.of("w",confirmed.score()),System.currentTimeMillis()+200)
                        .get("w").status()).isEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED);
            }
            Thread.sleep(350); // Cross the future release slot without racing Redis TIME at a slot boundary.
        }
        assertThat(winners.subList(0,3)).containsExactlyInAnyOrder("default","country","messaging");
        assertThat(winners.subList(3,6)).containsExactlyInAnyOrder("default","country","messaging");
    }

    @Test void defaultTargetsAreResolvedIntoCreateOnlyBindings() {
        try(var configured=createCatalog(Map.of("g",Set.of("worker.country")),
                Map.of("g",Map.of("worker.default",List.of(target(10,"US")))))) {
            assertThat(configured.bindTaskRule("configured","g","worker.default",null).status()).isEqualTo(MutationStatus.APPLIED);
        }
        assertThat(catalog.loadTaskBindings(List.of("configured")).get("configured").refillTargets()).containsExactly(target(10,"US"));
        assertThat(catalog.bindTaskRule("configured","g","worker.default",null).status()).isEqualTo(MutationStatus.CONFLICT);
        assertThat(catalog.bindTaskRule("override","g","worker.default",List.of(target(15,"CN"))).status()).isEqualTo(MutationStatus.APPLIED);
    }
    @Test void concurrentBindingsKeepExactlyOneWinnerWithoutDefinitions() throws Exception {
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var results=new ArrayList<Future<MutationStatus>>();
            for(int i=0;i<40;i++) { String rule=i%2==0 ? "worker.country" : "worker.messaging.available";
                results.add(executor.submit(()->catalog.bindTaskRule("shared","g",rule, null).status())); }
            int applied=0; for(var result:results)if(result.get()==MutationStatus.APPLIED)applied++;
            assertThat(applied).isEqualTo(1);
        }
        assertThat(catalog.loadTaskBindings(List.of("shared")).get("shared")).isNotNull();
        assertThat(redis.exists(keyspace.base()+":matching:candidate:rules")).isZero();
    }
    @Test void concurrentWorkerAndPlatformUpdatesCannotOverwriteEachOthersFacts() throws Exception {
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","CN")));
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->{ for(int i=0;i<100;i++) catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","CN","sequence",""+i))); });
            var b=executor.submit(()->{ for(int i=0;i<100;i++) catalog.patchWorkerPlatformProperties("g","w",Map.of("sequence",""+i)); });
            a.get(10,TimeUnit.SECONDS); b.get(10,TimeUnit.SECONDS);
        }
        var facts=catalog.loadWorkerFacts("g",List.of("w")).get("w");
        assertThat(facts.workerProperties()).containsEntry("sequence","99");
        assertThat(facts.platformProperties()).containsEntry("sequence","99");
    }
    @Test void corruptFactsAndIndexCannotBeSilentlyOverwritten() {
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","CN")));
        hot("g",List.of("w"));
        redis.zadd(indexKey(),-1,"w");
        assertThatThrownBy(()->catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","US")))) .isInstanceOf(RuntimeException.class);
        assertThat(catalog.loadWorkerFacts("g",List.of("w")).get("w").workerProperties()).containsEntry("country","CN");
        assertThatThrownBy(()->refillPrepared(Map.of("task",bound("task","worker.country")))).isInstanceOf(RuntimeException.class);
    }
    @Test void corruptPartitionMetadataRejectsProjectionWithoutChangingTheIndex() {
        catalog.upsertWorkerFactsBatch("g", Map.of("w", messageFacts("CN", "phone")));
        String key = keyspace.base() + ":matching:worker:index:Zw:messaging";
        hot("g",List.of("w"));
        redis.hset(key + ":partitions", "w", "{}");
        Double score = redis.zscore(key, "w");
        assertThatThrownBy(() -> catalog.upsertWorkerFactsBatch("g", Map.of("w", messageFacts("US", "new"))))
                .isInstanceOf(RuntimeException.class);
        assertThat(catalog.loadWorkerFacts("g", List.of("w")).get("w").workerProperties())
                .containsEntry("country", "CN");
        assertThatThrownBy(() -> refillPrepared(Map.of("messaging",bound("messaging","worker.messaging.available"))))
                .isInstanceOf(RuntimeException.class);
        assertThat(redis.zscore(key, "w")).isEqualTo(score);
    }
    @Test void rebuildingAGroupWithGlobAndSeparatorCharactersDoesNotClearAnotherGroup() {
        String special = "g:*[x]";
        try (var selected = createCatalog(
                Map.of(special, Set.of("worker.country")), Map.of());
             var neighbor = createCatalog(
                Map.of(special + ":other", Set.of("worker.country")), Map.of())) {
            selected.upsertWorkerFactsBatch(special, Map.of("a", Map.of("country", "CN")));
            neighbor.upsertWorkerFactsBatch(special + ":other", Map.of("b", Map.of("country", "US")));
            selected.rebuildIndexes();
            neighbor.bindTaskRule("neighbor", special + ":other", "worker.country", null);
            var query = neighbor.prepareTaskQueries(Map.of("neighbor", special + ":other")).get("neighbor");
            hot(special+":other",List.of("b"));
            refillPrepared(neighbor,special+":other",Map.of("neighbor",query),100);
            assertThat(query.take(Map.of(ANY, 1)).get(ANY)).extracting(HeldCandidate::workerId).containsExactly("b");
        }
    }
    @Test void startupRebuildUsesRetainedFactsAndOnlyThisGroupsIndexes() {
        catalog.upsertWorkerFactsBatch("g",Map.of("w",messageFacts("CN","phone")));
        var query=bound("message","worker.messaging.available");
        redis.set(keyspace.base()+":unrelated","kept");
        catalog.rebuildIndexes();
        hot("g",List.of("w")); refill("message");
        assertThat(query.take(Map.of(CN,1)).get(CN)).extracting(HeldCandidate::workerId).containsExactly("w");
        assertThat(redis.get(keyspace.base()+":unrelated")).isEqualTo("kept");
    }
}
