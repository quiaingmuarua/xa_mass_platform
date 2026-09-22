package com.xa.mass.server.assembly.matching;

import static com.xa.mass.kernel.score.redis.WorkerScoreRedisFixture.*;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.*;
import com.xa.mass.kernel.assignment.WorkerMatching;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.redis.RedisWorkerScoreCore;
import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.workermatching.*;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;

import com.xa.mass.workermatching.storage.FactsIndexStore;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.server.testsupport.BucketPoolFixture;
import com.xa.mass.workermatching.RedisWorkerMatchingCatalog;
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
    private final Map<RedisWorkerMatchingCatalog,FactsIndexStore> stores=new IdentityHashMap<>();
    private RedisWorkerScoreCore scores;
    private final List<String> commandTypes=new CopyOnWriteArrayList<>();
    private final List<String> refillStages=new ArrayList<>();
    private java.util.function.Consumer<List<String>> beforeQualification=ids->{};
    private java.util.function.Consumer<List<String>> afterAdmission=ids->{};
    private static String poolName(String function) {
        return switch(function) { case "worker.any" -> "any"; case "worker.country" -> "country";
            case "worker.messaging.available" -> "messaging"; case "proof.worker.facts" -> "proof-facts"; default -> function; };
    }
    private static Map<String,MatchingGroup> groups(Map<String,Set<String>> enabled) {
        var result=new LinkedHashMap<String,MatchingGroup>();
        enabled.forEach((group,names)->result.put(group,new MatchingGroup(
                names.stream().map(RedisWorkerMatchingCatalogIntegrationTest::poolName).collect(java.util.stream.Collectors.toSet()),names)));
        return result;
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
        catalog=createCatalog(Map.of("g",Set.of("worker.any","worker.country","worker.messaging.available","proof.worker.facts")));
    }
    private RedisWorkerMatchingCatalog createCatalog(Map<String,Set<String>> enabled) {
        return createCatalog(enabled, System::currentTimeMillis);
    }
    private RedisWorkerMatchingCatalog createCatalog(Map<String,Set<String>> enabled, java.util.function.LongSupplier clock) {
        var groups=groups(enabled);
        var storage=new FactsIndexStore(redisClient,keyspace,MatchingComposition.indexedProperties(groups));
        var composition=new MatchingComposition(storage,groups,clock);
        var traced=new LinkedHashMap<String,PoolRefillPolicy>(); composition.policies().forEach((id,policy)->traced.put(id,trace(policy)));
        var result=new RedisWorkerMatchingCatalog(storage,composition.budget(),composition.pools(),
                clock,traced,composition.functions(),groups, composition.poolOrder(), composition.globalFunctions());
        stores.put(result,storage);
        return result;
    }
    private RedisWorkerMatchingCatalog bucketCatalog(Map<String,Set<String>> enabled,boolean fail) {
        var groups=groups(enabled);
        var storage=new FactsIndexStore(redisClient,keyspace,MatchingComposition.indexedProperties(groups));
        var composition=new MatchingComposition(storage,groups,System::currentTimeMillis);
        var stock=new WorkerCandidatePool(System::currentTimeMillis,composition.budget());
        var bucket=new BucketPoolFixture(System::currentTimeMillis,storage,stock,fail);
        var pools=new LinkedHashMap<>(composition.pools()); pools.put(BucketPoolFixture.ID,stock);
        var policies=new LinkedHashMap<>(composition.policies()); policies.put(BucketPoolFixture.ID,bucket);
        var functions=new LinkedHashMap<>(composition.functions()); functions.put(BucketPoolFixture.ID,bucket.functions());
        var result=new RedisWorkerMatchingCatalog(storage,composition.budget(),pools,
                System::currentTimeMillis,policies,functions,groups, java.util.stream.Stream.concat(composition.poolOrder().stream(), java.util.stream.Stream.of(BucketPoolFixture.ID)).toList(), composition.globalFunctions());
        stores.put(result,storage);
        return result;
    }
    private PoolRefillPolicy trace(PoolRefillPolicy handler) {
        return new PoolRefillPolicy() {
            public TargetBatching targetBatching() { return handler.targetBatching(); }
            public EligibilityQuery normalizeQuery(String group,EligibilityQuery query) { return handler.normalizeQuery(group,query); }
            public Map<EligibilityQuery,Integer> deficits(String group,Map<EligibilityQuery,Integer> targets) { return handler.deficits(group,targets); }
            public List<String> refill(String group,Map<EligibilityQuery,Integer> targets,Map<String, Long> offered,int maxAccepted) {
                var ids=List.copyOf(offered.keySet());
                refillStages.add("qualification"); beforeQualification.accept(ids);
                var result=handler.refill(group,targets,offered,maxAccepted); afterAdmission.accept(ids); return result;
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
    private final Map<String,String> itemFunctions = new LinkedHashMap<>();
    private String function(TaskDescriptor task) { return itemFunctions.get(task.taskId()); }
    private final Map<String,TaskDescriptor> declarations = new LinkedHashMap<>();
    private TaskDescriptor declare(String task,String rule) {
        return declareTask(task,"g",rule,null);
    }
    private TaskDescriptor declareTask(String task,String group,String rule,List<RefillTarget> targets) {
        var descriptor = new TaskDescriptor(task, "test-project", group, TaskIdleDisposition.CLOSE_WHEN_IDLE, Map.of("priority","0","maxRetryTimes","1"), catalog.normalizeRefill(group, (targets==null?List.of(new RefillTarget(poolName(rule),ANY,100)):targets.stream().map(t->new RefillTarget(poolName(rule),t.target(),t.count())).toList())), null, java.util.Map.of());
        declarations.put(task,descriptor); itemFunctions.put(task,rule);
        return descriptor;
    }
    private static Map<String,String> messageFacts(String country,String phone) {
        return Map.of("country",country,"phone",phone,"messaging.enabled","true");
    }
    private String indexKey() { return keyspace.base()+":matching:worker:index:Zw:country"; }
    private void useBucketRule(boolean failSnapshot) {
        catalog.close();
        var groups=Map.of("g",Set.of("worker.country",BucketPoolFixture.ID));
        catalog=bucketCatalog(groups,failSnapshot);
    }
    @Test void countryTakeNormalizesAndCorrelatesWithoutRedisOrPartialConsumptionOnBadInput() {
        catalog.upsertWorkerFactsBatch("g",Map.of("cn",Map.of("country","CN"),"us",Map.of("country","US")));
        hot("g",List.of("cn","us"));
        declare("countries","worker.country");
        assertThat(refill("countries")).isEqualTo(2);
        var requests=new LinkedHashMap<String,WorkerQuery>();
        requests.put("first",new WorkerQuery("worker.country",List.of("CN","CN")));
        requests.put("second",new WorkerQuery("worker.country",List.of("US")));
        requests.put("equivalent",new WorkerQuery("worker.country",List.of("CN")));
        requests.put("late-invalid",new WorkerQuery("worker.country",Map.of("workerId",List.of("us"))));
        commandTypes.clear();
        assertThatThrownBy(()->catalog.take("g",requests)).isInstanceOf(IllegalArgumentException.class);
        assertThat(commandTypes).isEmpty();
        requests.remove("late-invalid");
        var result=catalog.take("g",requests);
        assertThat(result.keySet()).containsExactly("first","second");
        assertThat(result.values()).extracting(h -> h.workerId()).containsExactly("cn","us");
        assertThat(commandTypes).isEmpty();
        assertThatThrownBy(result::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(catalog.take("g",requests)).isEmpty();
    }
    @Test void independentBucketQualificationSharesStockWithOneAtomicFactsRead() {
        useBucketRule(false);
        var facts=new LinkedHashMap<String,Map<String,String>>();
        for(int i=0;i<100;i++)facts.put("w"+i,Map.of("country","CN","testBucket",i%2==0?"red":"blue"));
        commandTypes.clear();catalog.upsertWorkerFactsBatch("g",facts);assertThat(commandTypes).containsExactly("EVAL");
        hot("g",List.copyOf(facts.keySet()));
        var target=new RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of("test.bucket",List.of("blue","red"))), 100);
        for(String task:List.of("a","b"))declareTask(task,"g",BucketPoolFixture.ID,List.of(target));
        commandTypes.clear();var prepared=declarations("a","b");assertThat(commandTypes).isEmpty();
        commandTypes.clear();assertThat(refillDeclarations(prepared)).isEqualTo(100);
        assertThat(commandTypes).containsExactly("EVAL","EVAL","EVAL_RO");
        commandTypes.clear();assertThat(refillDeclarations(prepared)).isZero();
        var selector=Map.of("test.bucket",List.of("red","blue"));
        var first=takeItems(catalog,prepared.get("a").workerGroupId(),function(prepared.get("a")),selector,40);
        var second=takeItems(catalog,prepared.get("b").workerGroupId(),function(prepared.get("b")),selector,100);
        assertThat(first).hasSize(40);assertThat(second).hasSize(60);assertThat(commandTypes).isEmpty();
        var ids=new HashSet<String>();first.forEach(h->assertThat(ids.add(h.workerId())).isTrue());second.forEach(h->assertThat(ids.add(h.workerId())).isTrue());
    }
    @Test void messagingCountryAndAnyQueriesUseOnlyExistingStockViews() {
        catalog.upsertWorkerFactsBatch("g",Map.of("cn",messageFacts("CN","same"),
                "us",messageFacts("US","same"),"other",messageFacts("CN","other")));
        hot("g",List.of("cn","us","other")); declare("messages","worker.messaging.available");
        assertThat(refill("messages")).isEqualTo(3);
        var requests=new LinkedHashMap<String,WorkerQuery>();
        requests.put("country",new WorkerQuery("worker.messaging.available",Map.of("country",List.of("CN"))));
        requests.put("us",new WorkerQuery("worker.messaging.available",Map.of("country",List.of("US"))));
        requests.put("any",new WorkerQuery("worker.messaging.available",Map.of()));
        commandTypes.clear();
        assertThat(catalog.take("g",requests).values()).extracting(WorkerCandidate::workerId).containsExactly("cn","us","other");
        assertThat(commandTypes).isEmpty();
        assertThat(catalog.take("g",requests)).isEmpty();
        assertThat(commandTypes).isEmpty();
    }
    @Test void bucketFactsMutationInvalidatesGenerationAndLaterRefillConverges() throws Exception {
        useBucketRule(false);catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("testBucket","red")));hot("g",List.of("w"));
        declareTask("bucket","g",BucketPoolFixture.ID,List.of(new RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of("test.bucket",List.of("red","blue"))), 1)));
        var prepared=declarations("bucket");assertThat(refillDeclarations(prepared)).isEqualTo(1);
        var held=takeItems(catalog,prepared.get("bucket").workerGroupId(),function(prepared.get("bucket")),Map.of(),1).getFirst();
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("testBucket","blue")));
        scores.advancePastScoreTimesToNow("g",List.of("w"));
        assertThat(scores.acquireObservedHotScoreLeases("g", Map.of("w",held.expectedScore()), System.currentTimeMillis()+5000).get("w").status())
                .isNotEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED);
        awaitDue("g");
        assertThat(refillDeclarations(prepared)).isEqualTo(1);
        var blue=Map.of("test.bucket",List.of("blue"));
        assertThat(takeItems(catalog,prepared.get("bucket").workerGroupId(),function(prepared.get("bucket")),blue,1)).extracting(h -> h.workerId()).containsExactly("w");
        catalog.patchWorkerPlatformProperties("g","w",Map.of("testEnabled","no"));
        assertThat(catalog.refill("g", List.of(new RefillTarget(BucketPoolFixture.ID, ANY, 1)), Map.of("w", 42L))).isZero();
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("testBucket","red")));
        assertThat(catalog.loadWorkerFacts("g",List.of("w")).get("w").platformProperties()).containsEntry("testEnabled","no");
        catalog.patchWorkerPlatformProperties("g","w",Map.of("testEnabled","yes"));
        assertThat(catalog.refill("g", List.of(new RefillTarget(BucketPoolFixture.ID, ANY, 1)), Map.of("w", 43L))).isEqualTo(1);
        assertThat(takeItems(catalog,"g",BucketPoolFixture.ID,Map.of("test.bucket",List.of("red")),1)).extracting(WorkerCandidate::workerId).containsExactly("w");
    }
    @Test void corruptBucketFactsFailBeforeAnyAdmission() {
        useBucketRule(false);
        catalog.upsertWorkerFactsBatch("g",Map.of("a",Map.of("testBucket","red"),"b",Map.of("testBucket","blue")));
        redis.hset(keyspace.base()+":matching:worker:facts:g","b","[]");
        var offered = new LinkedHashMap<String,Long>(); offered.put("a",42L); offered.put("b",43L);
        assertThatThrownBy(() -> catalog.refill("g",List.of(new RefillTarget(BucketPoolFixture.ID,ANY,2)),offered))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(takeItems(catalog,"g",BucketPoolFixture.ID,Map.of(),100)).isEmpty();
    }
    @Test void bucketQualificationFailureLeavesTheGenerationWithoutStockOrRecoveryRead() {
        useBucketRule(true);catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("testBucket","red")));hot("g",List.of("w"));
        var query=declare("bucket",BucketPoolFixture.ID);
        var held=candidateize("g",offer("g",100));
        var targets=targets(declarations("bucket"));
        commandTypes.clear();
        assertThatThrownBy(()->catalog.refill("g",targets.get("g"),held)).isInstanceOf(IllegalStateException.class);
        assertThat(commandTypes).isEmpty();
        commandTypes.clear();assertThat(takeItems(catalog,query.workerGroupId(),function(query),Map.of(),1)).isEmpty();assertThat(commandTypes).isEmpty();
        assertThat(readScores(redis, keyspace, "g",List.of("w")).get("w")).isEqualTo(held.values().iterator().next());
    }
    @Test void bucketQualificationAndRestartIgnoreRetiredProjections() {
        useBucketRule(false);
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("testBucket","red","country","CN")));
        String root=keyspace.base()+":matching:worker:index:Zw:test_buckets";
        redis.set(root,"retired"); redis.set(root+":orphan","retired-child");
        catalog.close(); commandTypes.clear();
        catalog=bucketCatalog(Map.of("g",Set.of(BucketPoolFixture.ID)),false);
        assertThat(commandTypes).isEmpty();
        hot("g",List.of("w")); var query=declare("retained",BucketPoolFixture.ID); refill("retained");
        assertThat(takeItems(catalog,query.workerGroupId(),function(query),Map.of(),1)).extracting(WorkerCandidate::workerId).containsExactly("w");
        assertThat(redis.get(root)).isEqualTo("retired");
        assertThat(redis.get(root+":orphan")).isEqualTo("retired-child");
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
        try (var competing = createCatalog(Map.of("g",Set.of("worker.any","worker.country","worker.messaging.available","proof.worker.facts")));
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
        scores.rewriteCurrentPolarityWithinTimeFence(group,evidence,WorkerScoreCore.WorkerScorePolarity.HOT_ACQUIRE, System.currentTimeMillis()-2000);
    }
    // The fixture supplies a closed Kernel-issued batch. Matching cannot choose identities.
    private Map<String,Long> offer(String group,int limit) {
        refillStages.add("observe");
        return scores.observeDueHotScoreCandidates(group,null,limit);
    }
    private Map<String, Long> candidateize(String group,Map<String,Long> offered) {
        refillStages.add("candidateize");
        return scores.candidateizeObservedHotScores(group,offered).entrySet().stream()
                .filter(e -> e.getValue().status()==WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED)
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().score(), (a,b) -> a, LinkedHashMap::new));
    }
    private void recycleTestCandidates(String group) {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                !scores.observeHotCandidateScoresBefore(group, null, System.currentTimeMillis() - 10, 100).isEmpty());
        var old = scores.observeHotCandidateScoresBefore(group, null, System.currentTimeMillis() - 10, 100);
        assertThat(scores.recycleObservedHotCandidates(group, old).values()).allSatisfy(result ->
                assertThat(result.status()).isEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED));
        awaitDue(group);
    }
    private void awaitDue(String group) {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(() ->
                !scores.observeDueHotScoreCandidates(group, null, 100).isEmpty());
    }

    private static Map<String,List<RefillTarget>> targets(Map<String,TaskDescriptor> bindings) {
        var result=new LinkedHashMap<String,List<RefillTarget>>();
        bindings.values().forEach(task->{if(task!=null) result.computeIfAbsent(task.workerGroupId(),k->new ArrayList<>()).addAll(task.refill());});
        return result;
    }
    private int refillDeclarations(RedisWorkerMatchingCatalog owner,String group,Map<String,TaskDescriptor> tasks,int limit) {
        var targets=targets(tasks);
        if(!owner.observeRefillDeficits(targets).containsKey(group))return 0;
        var offered=offer(group,limit);
        var held=candidateize(group,offered);
        return held.isEmpty()?0:owner.refill(group,targets.get(group),held);
    }
    private int refillDeclarations(Map<String,TaskDescriptor> tasks) { return refillDeclarations(catalog,"g",tasks,100); }
    private static List<RefillTarget> withPool(String name,List<RefillTarget> targets) {
        return targets.stream().map(t->new RefillTarget(name,t.target(),t.count())).toList();
    }
    private static RefillTarget target(int count,String country) {
        return new RefillTarget("country", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of("worker.country",List.of(country))), count);
    }
    private Map<String,TaskDescriptor> declarations(String... tasks) {
        var result = new LinkedHashMap<String,TaskDescriptor>();
        for(String task:tasks)result.put(task, declarations.get(task));
        return result;
    }
    private int refill(String... tasks) { return refillDeclarations(declarations(tasks)); }

    @Test void explicitAnyPoolDoesNotRequireFacts() {
        var query=declare("default","worker.any");
        hot("g",List.of("no-facts"));
        assertThat(takeItems(catalog,query.workerGroupId(),function(query),Map.of(),1)).isEmpty();
        assertThat(refill("default")).isEqualTo(1);
        assertThat(takeItems(catalog,query.workerGroupId(),function(query),Map.of(),1)).extracting(h -> h.workerId()).containsExactly("no-facts");
        assertThat(catalog.loadWorkerFacts("g",List.of("no-facts"))).containsEntry("no-facts",null);
    }
    @Test void namedRulesRejectIdsAndAnyUsesOnlyTheirCandidateMembership() {
        catalog.upsertWorkerFactsBatch("g",Map.of("cn",Map.of("country","CN"),"us",Map.of("country","US"),"empty",Map.of()));
        hot("g",List.of("cn","us","empty"));
        var query=declare("country","worker.country");
        assertThat(refill("country")).isEqualTo(2);
        var ids=new WorkerQuery(function(query),Map.of("workerId",List.of("cn","empty")));
        assertThatThrownBy(()->catalog.normalizeQuery(query.workerGroupId(),ids)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->catalog.take(query.workerGroupId(),Map.of("m",ids))).isInstanceOf(IllegalArgumentException.class);
        commandTypes.clear();
        assertThatThrownBy(()->catalog.normalizeRefill("g", withPool("country",List.of(new RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of("workerId",List.of("cn"))), 1)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(commandTypes).isEmpty();
        assertThat(takeItems(catalog,query.workerGroupId(),function(query),List.of("CN"),1)).extracting(h -> h.workerId()).containsExactly("cn");
        assertThat(takeItems(catalog,query.workerGroupId(),function(query),Map.of(),100)).extracting(h -> h.workerId()).containsExactly("us");
    }
    @Test void sharedTargetsUseMaximumForShortageWithoutCappingQualifiedOffers() {
        var facts=new LinkedHashMap<String,Map<String,String>>();
        for(int i=0;i<30;i++)facts.put("w"+i,Map.of("country","US"));
        catalog.upsertWorkerFactsBatch("g",facts); hot("g",List.copyOf(facts.keySet()));
        declareTask("seed","g","worker.country",List.of(target(15,"US")));
        assertThat(refillDeclarations(catalog,"g",declarations("seed"),15)).isEqualTo(15);
        declareTask("a","g","worker.country",List.of(target(10,"US")));
        declareTask("b","g","worker.country",List.of(target(20,"US")));
        var views=declarations("a","b");
        assertThat(catalog.observeRefillDeficits(targets(views))).containsEntry("g",5);
        // This Owner fixture deliberately supplies the remaining 15, independently of Pacer's read limit.
        assertThat(refill("a","b")).isEqualTo(15);
        commandTypes.clear();
        assertThat(refillDeclarations(views)).isZero();
        assertThat(takeItems(catalog,views.get("a").workerGroupId(),function(views.get("a")),List.of("US"),20)).hasSize(20);
        assertThat(takeItems(catalog,views.get("b").workerGroupId(),function(views.get("b")),List.of("US"),10)).hasSize(10);
        assertThat(takeItems(catalog,views.get("b").workerGroupId(),function(views.get("b")),List.of("US"),1)).isEmpty();
        assertThat(commandTypes).isEmpty();
    }
    @Test void targetResolutionRequiresNoRedisReadOrTaskRegistration() {
        commandTypes.clear();
        for(int i=0;i<100;i++)assertThat(catalog.normalizeRefill("g", List.of(new RefillTarget("country",ANY,100))))
                .containsExactly(new RefillTarget("country", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 100));
        assertThat(commandTypes).isEmpty();
    }
    @Test void hundredWorkersUseBoundedRefillCommandsAndZeroIoForConsumption() {
        var facts=new LinkedHashMap<String,Map<String,String>>();
        for(int i=0;i<100;i++)facts.put("w"+i,Map.of("country","CN"));
        declare("task","worker.country"); commandTypes.clear();
        assertThat(catalog.upsertWorkerFactsBatch("g",facts)).hasSize(100);
        assertThat(commandTypes).containsExactly("EVAL");
        hot("g",List.copyOf(facts.keySet()));
        var prepared=declarations("task"); commandTypes.clear();refillStages.clear();
        assertThat(refillDeclarations(prepared)).isEqualTo(100);
        // Observation and first acquisition both precede the supplied-ID projection.
        assertThat(commandTypes).containsExactly("EVAL","EVAL","HMGET");
        assertThat(refillStages).containsExactly("observe","candidateize","qualification");
        commandTypes.clear();
        assertThat(takeItems(catalog,prepared.get("task").workerGroupId(),function(prepared.get("task")),List.of("CN"),100)).hasSize(100);
        assertThat(commandTypes).isEmpty();
    }
    @Test void hundredDistinctTargetsUseOneObservationProjectionAndCandidateizationBatch() {
        var facts=new LinkedHashMap<String,Map<String,String>>();
        var targets=new ArrayList<RefillTarget>();
        for(int i=0;i<100;i++) {
            String code=""+(char)('A'+i/26)+(char)('A'+i%26);
            facts.put("w"+i,Map.of("country",code)); targets.add(target(1,code));
        }
        catalog.upsertWorkerFactsBatch("g",facts); hot("g",List.copyOf(facts.keySet()));
        declareTask("task","g","worker.country",targets);
        var prepared=declarations("task"); commandTypes.clear();
        assertThat(refillDeclarations(prepared)).isEqualTo(100);
        assertThat(commandTypes).containsExactly("EVAL","EVAL","HMGET");
        commandTypes.clear();
        assertThat(takeItems(catalog,prepared.get("task").workerGroupId(),function(prepared.get("task")),Map.of(),100)).hasSize(100);
        assertThat(commandTypes).isEmpty();
    }

    @Test void messagingCountryTargetReadsCurrentFactsAfterCandidateization() {
        catalog.upsertWorkerFactsBatch("g",Map.of("target",messageFacts("CN","rare")));
        hot("g",List.of("target"));
        var q=new RefillTarget("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of("worker.country",List.of("CN"))), 1);
        declareTask("messages","g","worker.messaging.available",List.of(q));
        var prepared=declarations("messages");
        var held=candidateize("g",offer("g",100));
        catalog.upsertWorkerFactsBatch("g",Map.of("target",messageFacts("US","new-phone")));
        assertThat(catalog.refill("g",targets(prepared).get("g"),held)).isZero();
        assertThat(takeItems(catalog,prepared.get("messages").workerGroupId(),function(prepared.get("messages")),Map.of(),1)).isEmpty();
        assertThat(readScores(redis, keyspace, "g",List.of("target")).get("target")).isEqualTo(held.values().iterator().next());
    }

    @Test void offeredBatchIsClosedEvenWhenTheIndexContainsBetterWorkers() {
        catalog.upsertWorkerFactsBatch("g",Map.of("a",Map.of("country","US"),"b",Map.of("country","CN")));
        hot("g",List.of("a","b"));
        declareTask("task","g","worker.country",List.of(target(1,"CN")));
        var prepared=declarations("task");
        var observed=offer("g",1);
        assertThat(observed.keySet()).containsExactly("a");
        var held=candidateize("g",observed);
        commandTypes.clear();
        assertThat(catalog.refill("g",targets(prepared).get("g"),held)).isZero();
        assertThat(commandTypes).containsExactly("HMGET"); // Supplied Worker Facts only.
        assertThat(takeItems(catalog,prepared.get("task").workerGroupId(),function(prepared.get("task")),Map.of(),1)).isEmpty();
        assertThat(scores.observeDueHotScoreCandidates("g", null, 100)).containsKey("b");
    }

    @Test void unmatchedCandidateStaysOutOfOrdinaryHeadUntilRecycled() throws Exception {
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","US")));
        hot("g",List.of("w"));
        declareTask("task","g","worker.country",List.of(target(1,"CN")));
        var prepared=declarations("task");
        var held=candidateize("g",offer("g",100));
        beforeQualification=ids->{
            assertThat(ids).containsExactly("w");
            var state=readScores(redis, keyspace, "g",ids).get("w");
            assertThat(state).isEqualTo(held.values().iterator().next());
            assertThat(mark(state)).isEqualTo(1);
            assertThat(scores.observeDueHotScoreCandidates("g", null, 100)).isEmpty();
        };
        assertThat(catalog.refill("g",targets(prepared).get("g"),held)).isZero();
        assertThat(takeItems(catalog,prepared.get("task").workerGroupId(),function(prepared.get("task")),Map.of(),1)).isEmpty();
        recycleTestCandidates("g");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        Map<String,Long> due=Map.of();
        while(due.isEmpty() && System.nanoTime()<deadline) {
            due=scores.observeDueHotScoreCandidates("g", null, 100);
            if(due.isEmpty())Thread.sleep(20);
        }
        assertThat(due).containsKey("w");
        assertThat(due.get("w")).isNotEqualTo(held.get("w"));
    }

    @Test void eligibilitiesPartitionOneCandidateizedGroupBatchWithoutChangingGeneration() {
        catalog.upsertWorkerFactsBatch("g",Map.of("cn",Map.of("country","CN"),"us",Map.of("country","US")));
        hot("g",List.of("cn","us"));
        declareTask("country","g","worker.country",List.of(target(1,"CN")));
        declareTask("default","g","worker.any",List.of(new RefillTarget("any",ANY,1)));
        var prepared=declarations("country","default");var observed=offer("g",100);
        commandTypes.clear();refillStages.clear();
        var offered=candidateize("g",observed);
        var original=Map.copyOf(offered);
        assertThat(catalog.refill("g",targets(prepared).get("g"),offered)).isEqualTo(2);
        assertThat(commandTypes).containsExactly("EVAL","HMGET");
        assertThat(refillStages).containsExactly("candidateize","qualification","qualification");
        var delivered=new HashSet<String>();
        prepared.values().forEach(view->takeItems(catalog,view.workerGroupId(),function(view),Map.of(),100).forEach(held->{
            assertThat(delivered.add(held.workerId())).isTrue();
            assertThat(held).isEqualTo(new WorkerCandidate(held.workerId(), original.get(held.workerId())));
            assertThat(held.expectedScore()).isNotEqualTo(observed.get(held.workerId()));
        }));
        assertThat(delivered).containsExactlyInAnyOrder("cn", "us");
    }

    @Test void headCandidateizationReachesARareMatchWithoutSkippingUnmatchedWorkers() {
        var ids=IntStream.range(0,250).mapToObj(i->"w-%03d".formatted(i)).toList();
        long observed=dueOrdinaryScore(System.currentTimeMillis());
        for(int start=0;start<ids.size();start+=100) {
            var facts=new LinkedHashMap<String,Map<String,String>>();
            for(String id:ids.subList(start,Math.min(start+100,ids.size()))) {
                facts.put(id,Map.of("country",id.equals("w-249")?"CN":"US"));
                redis.zadd(keyspace.base()+":worker:score:g",observed,id);
            }
            catalog.upsertWorkerFactsBatch("g",facts);
        }
        declareTask("rare","g","worker.country",List.of(target(1,"CN")));
        var prepared=declarations("rare");
        int added=0;var visited=new ArrayList<String>();
        for(int round=0;round<3 && added==0;round++) {
            var targets=targets(prepared);
            catalog.observeRefillDeficits(targets);
            var observedBatch=scores.observeDueHotScoreCandidates("g",null,100);
            visited.addAll(observedBatch.keySet());
            var held=candidateize("g",observedBatch);
            added+=catalog.refill("g",targets.get("g"),held);
        }
        assertThat(visited).containsExactlyElementsOf(ids);
        assertThat(added).isEqualTo(1);
        assertThat(takeItems(catalog,prepared.get("rare").workerGroupId(),function(prepared.get("rare")),List.of("CN"),1))
                .extracting(h -> h.workerId()).containsExactly("w-249");
        assertThat(readScores(redis, keyspace, "g",List.of("w-000")).get("w-000")).isNotEqualTo(observed);
    }

    @Test void discardedCandidateizationResponseLeavesNoStockAndCannotRescueTheOldFence() {
        hot("g",List.of("w"));declare("task","worker.any");
        var prepared=declarations("task");var observed=offer("g",100);
        // The owner committed, but its returned batch never reaches Matching.
        assertThat(candidateize("g",observed)).hasSize(1);
        commandTypes.clear();assertThat(takeItems(catalog,prepared.get("task").workerGroupId(),function(prepared.get("task")),Map.of(),1)).isEmpty();
        assertThat(commandTypes).isEmpty();
        assertThat(scores.acquireObservedHotScoreLeases("g", Map.of("w",observed.get("w")), System.currentTimeMillis()+5000).get("w").status())
                .isEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.STALE);
        assertThat(refillDeclarations(prepared)).isZero();
    }

    @Test void factsChangedBeforeProjectionAreReadAfterCandidateization() {
        hot("g",List.of("w"));
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","CN")));
        scores.advancePastScoreTimesToNow("g",List.of("w"));
        awaitDue("g");
        declareTask("task","g","worker.country",List.of(target(1,"CN")));
        var prepared=declarations("task");
        var held=candidateize("g",offer("g",100));
        beforeQualification=ids->{
            assertThat(mark(readScores(redis, keyspace, "g",ids).get("w"))).isEqualTo(1);
            catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","US")));
            assertThat(scores.advancePastScoreTimesToNow("g",ids).get("w").status())
                    .isEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED);
        };
        assertThat(catalog.refill("g",targets(prepared).get("g"),held)).isZero();
        assertThat(takeItems(catalog,prepared.get("task").workerGroupId(),function(prepared.get("task")),List.of("CN"),1)).isEmpty();
    }

    @Test void invalidationAfterAdmissionRejectsTheOriginalCandidateFence() {
        hot("g",List.of("w"));
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","CN")));
        scores.advancePastScoreTimesToNow("g",List.of("w"));
        awaitDue("g");
        declareTask("task","g","worker.country",List.of(target(1,"CN")));
        var prepared=declarations("task");
        var offered=candidateize("g",offer("g",100));
        afterAdmission=ids->{
            catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","US")));
            assertThat(scores.advancePastScoreTimesToNow("g",ids).get("w").status())
                    .isEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED);
        };
        assertThat(catalog.refill("g",targets(prepared).get("g"),offered)).isEqualTo(1);
        var held=takeItems(catalog,prepared.get("task").workerGroupId(),function(prepared.get("task")),List.of("CN"),1).getFirst();
        assertThat(held).isEqualTo(candidate(offered.entrySet().iterator().next()));
        assertThat(scores.acquireObservedHotScoreLeases("g", Map.of("w",held.expectedScore()), System.currentTimeMillis()+5000)
                .get("w").status()).isEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.STALE);
    }

    @Test void factsInvalidationBeforeCandidateizationRejectsTheOldObservation() {
        hot("g",List.of("w"));
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","CN")));
        declareTask("task","g","worker.country",List.of(target(1,"CN")));
        var prepared=declarations("task");var observed=offer("g",100);
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","US")));
        scores.advancePastScoreTimesToNow("g",List.of("w"));
        assertThat(candidateize("g",observed)).isEmpty();
        assertThat(takeItems(catalog,prepared.get("task").workerGroupId(),function(prepared.get("task")),List.of("CN"),1)).isEmpty();
    }

    @Test void invalidatedStockCannotAcquireAndOneCandidateFenceExecutesOnlyOnce() throws Exception {
        hot("g",List.of("w")); declare("task","worker.any");
        refill("task"); scores.advancePastScoreTimesToNow("g",List.of("w"));
        var binding=declarations("task").get("task");
        var held=takeItems(catalog,binding.workerGroupId(),function(binding),Map.of(),1).getFirst();
        var rejected=scores.acquireObservedHotScoreLeases("g", Map.of("w",held.expectedScore()), System.currentTimeMillis()+5000);
        assertThat(rejected.get("w").status()).isNotEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED);
        // Invalidation returns HOT to the ordinary lane; qualify after the current slot passes.
        awaitDue("g");
        assertThat(refill("task")).isEqualTo(1);
        long generation=takeItems(catalog,binding.workerGroupId(),function(binding),Map.of(),1).getFirst().expectedScore();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var operations=IntStream.range(0,20).mapToObj(i -> executor.submit(() -> scores.acquireObservedHotScoreLeases("g", Map.of("w",generation), System.currentTimeMillis()+5000).get("w").status())).toList();
            int applied=0; for(var operation:operations)if(operation.get()==WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED)applied++;
            assertThat(applied).isEqualTo(1);
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void anotherExecutionCallerCanConsumeCachedGenerationWithoutRepairingStock(boolean direct) {
        hot("g", List.of("w"));
        declare("task", "worker.any");
        var prepared = declarations("task");
        long requestTime = System.currentTimeMillis() + 30_000;
        var acquired = scores.candidateizeObservedHotScores("g", offer("g", 100)).get("w");
        assertThat(acquired.status()).isEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED);
        var original = Map.entry("w", (long) (acquired.score()));
        assertThat(catalog.refill("g", targets(prepared).get("g"), Map.ofEntries(original))).isEqualTo(1);

        // Another bounded caller has the opaque fence; no allocator or cache scan is involved.
        var execution = (direct ? scores.acquireCurrentHotScoreLeases("g",List.of("w"),requestTime + 5000)
                : scores.acquireObservedHotScoreLeases("g",Map.of("w",original.getValue()),requestTime + 5000)).get("w");
        assertThat(execution.status()).isEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED);
        var binding = prepared.get("task");
        var cached = takeItems(catalog, binding.workerGroupId(), function(binding),Map.of(), 1).getFirst();
        assertThat(cached).isEqualTo(candidate(original));
        assertThat(requestTime).isGreaterThan(System.currentTimeMillis());
        assertThat(scores.acquireObservedHotScoreLeases("g", Map.of("w", cached.expectedScore()), requestTime + 10_000).get("w").status())
                .isEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.STALE);
        assertThat(scores.releaseScoreHolds("g", Map.of("w", cached.expectedScore()), requestTime - 1_000).get("w").status())
                .isEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.INVALID);
        assertThat(scores.releaseObservedHotScoreHolds("g", Map.of("w", cached.expectedScore()), requestTime - 1_000).get("w").status())
                .isEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.INVALID);
        assertThat(readScores(redis, keyspace, "g", List.of("w")).get("w")).isEqualTo(execution.score());
    }

    @Test void freshCatalogCannotConsumePreviousProcessStockOrRecoverItsFence() {
        hot("g",List.of("w")); declare("task","worker.any"); refill("task");
        try(var restarted=createCatalog(Map.of("g",Set.of("worker.any")))) {
            var views=declarations("task");
            assertThat(takeItems(restarted,views.get("task").workerGroupId(),function(views.get("task")),Map.of(),1)).isEmpty();
            assertThat(refillDeclarations(restarted,"g",views,100)).isZero();
        }
    }
    @Test void overlappingPoolTargetsAdmitOneGenerationOnlyOnce() {
        catalog.upsertWorkerFactsBatch("g", Map.of("w", messageFacts("CN", "phone")));
        hot("g", List.of("w"));
        declare("default", "worker.any"); declare("country", "worker.country"); declare("messaging", "worker.messaging.available");
        var prepared = declarations("default", "country", "messaging");
        refillStages.clear();
        assertThat(refillDeclarations(prepared)).isEqualTo(1);
        assertThat(refillStages.stream().filter("qualification"::equals).count()).isEqualTo(1);
        var candidates = new ArrayList<WorkerCandidate>();
        for (var task : prepared.values()) {
            candidates.addAll(takeItems(catalog, "g", function(task), Map.of(), 1));
        }
        assertThat(candidates).hasSize(1);
        var candidate = candidates.getFirst();
        assertThat(candidate.workerId()).isEqualTo("w");
        assertThat(scores.acquireObservedHotScoreLeases("g", Map.of("w", candidate.expectedScore()),
                System.currentTimeMillis() + 5000).get("w").status())
                .isEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED);
    }

    @Test void localTtlBlocksUntakenStockButDoesNotRevokeAnAlreadyTakenFence() {
        var clock = new java.util.concurrent.atomic.AtomicLong(1000);
        catalog.close();
        catalog = createCatalog(Map.of("g", Set.of("worker.any")), clock::get);
        hot("g", List.of("taken", "untaken"));
        declare("task", "worker.any");
        assertThat(refill("task")).isEqualTo(2);
        var taken = takeItems(catalog, "g", "worker.any", Map.of(), 1).getFirst();
        clock.set(61_000);
        assertThat(takeItems(catalog, "g", "worker.any", Map.of(), 1)).isEmpty();
        assertThat(scores.acquireObservedHotScoreLeases("g", Map.of(taken.workerId(), taken.expectedScore()),
                System.currentTimeMillis() + 5000).get(taken.workerId()).status())
                .isEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED);
    }

    @Test void explicitAndEmptyTargetsNormalizeWithoutPersistenceOrImplicitSupply() {
        commandTypes.clear();
        assertThat(catalog.normalizeRefill("g",List.of())).isEmpty();
        assertThat(catalog.normalizeRefill("g",List.of(target(15,"CN")))).containsExactly(target(15,"CN"));
        assertThat(commandTypes).isEmpty();
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
    @Test void corruptStoredFactsCannotBeSilentlyOverwritten() {
        catalog.upsertWorkerFactsBatch("g", Map.of("w", messageFacts("CN", "phone")));
        String facts = keyspace.base() + ":matching:worker:facts:g";
        redis.hset(facts, "w", "[]");
        assertThatThrownBy(() -> catalog.upsertWorkerFactsBatch("g", Map.of("w", messageFacts("US", "new"))))
                .isInstanceOf(RuntimeException.class);
        assertThat(redis.hget(facts, "w")).isEqualTo("[]");
        // The public observation retains its row-local null result.
        assertThat(catalog.loadWorkerFacts("g", List.of("w"))).containsEntry("w", null);
    }
    @Test void obsoleteCountryIndexesAreNeitherReadUpdatedNorRebuilt() {
        catalog.close();catalog=createCatalog(Map.of("g",Set.of("worker.country")));
        redis.set(indexKey(),"obsolete-corrupt-index");
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","CN")));
        assertThat(redis.get(indexKey())).isEqualTo("obsolete-corrupt-index");
        hot("g",List.of("w"));declare("country","worker.country");
        commandTypes.clear();assertThat(refill("country")).isEqualTo(1);
        assertThat(commandTypes).containsExactly("EVAL","EVAL","HMGET");
        assertThat(takeItems(catalog,"g","worker.country",List.of("CN"),1)).extracting(WorkerCandidate::workerId).containsExactly("w");
    }
    @Test void corruptWorkerFactsFailCountryBeforeAnyAdmission() {
        catalog.upsertWorkerFactsBatch("g",Map.of("a",Map.of("country","CN"),"b",Map.of("country","US")));
        hot("g",List.of("a","b"));declare("country","worker.country");
        redis.hset(keyspace.base()+":matching:worker:facts:g","b","[]");
        assertThatThrownBy(()->refill("country")).isInstanceOf(IllegalArgumentException.class);
        assertThat(takeItems(catalog,"g","worker.country",Map.of(),100)).isEmpty();
    }
    @Test void retiredQualificationIndexesAreNotReadWrittenValidatedOrRebuilt() {
        var oldKeys = new LinkedHashMap<String, String>();
        for (String name : List.of("messaging", "proof")) {
            String root = keyspace.base() + ":matching:worker:index:Zw:" + name;
            oldKeys.put(root, "wrong-type-root");
            oldKeys.put(root + ":partitions", "wrong-type-partitions");
            oldKeys.put(root + ":partition:legacy", "obsolete-partition");
        }
        oldKeys.forEach(redis::set);
        catalog.upsertWorkerFactsBatch("g", Map.of("message", messageFacts("CN", "phone"),
                "proof", Map.of("proofPool", "*")));
        catalog.patchWorkerPlatformProperties("g", "proof", Map.of("proofEnabled", "yes"));
        var enabled = groups(Map.of("g", Set.of("worker.messaging.available", "proof.worker.facts")));
        commandTypes.clear();
        try (var restarted = MatchingComposition.create(redisClient, keyspace, enabled)) {
            assertThat(commandTypes).isEmpty(); // No qualification index startup scan, even with poisoned keys.
            commandTypes.clear();
            assertThat(restarted.refill("g", List.of(new RefillTarget("messaging", ANY, 1)), Map.of("message", 42L))).isEqualTo(1);
            assertThat(commandTypes).containsExactly("HMGET");
            commandTypes.clear();
            assertThat(restarted.refill("g", List.of(new RefillTarget("proof-facts", ANY, 1)), Map.of("proof", 43L))).isEqualTo(1);
            assertThat(commandTypes).containsExactly("EVAL_RO");
            assertThat(restarted.take("g", Map.of("m", new WorkerQuery("proof.worker.facts", Map.of("proofPool", "*",
                    "proofEnabled", "yes"))))).containsEntry("m", new WorkerCandidate("proof", 43L));
        }
        oldKeys.forEach((key, value) -> assertThat(redis.get(key)).isEqualTo(value));
    }

    @Test void qualificationReadsOnlyOfferedFactsAndCorruptionCannotPartiallyAdmit() {
        String workerKey = keyspace.base() + ":matching:worker:facts:g";
        String platformKey = keyspace.base() + ":matching:worker:platform-properties:g";
        catalog.upsertWorkerFactsBatch("g", Map.of("valid", messageFacts("CN", "phone"),
                "bad", messageFacts("US", "other")));
        redis.hset(workerKey, "unoffered", "[]");
        redis.hset(platformKey, "unoffered", "[]");
        commandTypes.clear();
        assertThat(catalog.refill("g", List.of(new RefillTarget("messaging", ANY, 1)), Map.of("valid", 42L))).isEqualTo(1);
        assertThat(commandTypes).containsExactly("HMGET");
        assertThat(catalog.take("g", Map.of("m", new WorkerQuery("worker.messaging.available", Map.of()))))
                .containsEntry("m", new WorkerCandidate("valid", 42L));
        // Even after a valid first row, both strict paths fail before this Pool admits anything.
        var offered = new LinkedHashMap<String, Long>(); offered.put("valid", 43L); offered.put("bad", 44L);
        redis.hset(workerKey, "bad", "[]");
        assertThatThrownBy(() -> catalog.refill("g", List.of(new RefillTarget("messaging", ANY, 2)), offered))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catalog.take("g", Map.of("m", new WorkerQuery("worker.messaging.available", Map.of())))).isEmpty();
        redis.hset(workerKey, "bad", "{}");
        redis.hset(platformKey, "bad", "[]");
        assertThatThrownBy(() -> catalog.refill("g", List.of(new RefillTarget("proof-facts", ANY, 2)), offered))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catalog.take("g", Map.of("m", new WorkerQuery("proof.worker.facts", Map.of())))).isEmpty();
        // Absent Worker rows are skipped; absent Platform is an empty map.
        redis.hset(platformKey, "missing", "[]");
        commandTypes.clear();
        var snapshot = stores.get(catalog).readFactsSnapshot("g", List.of("valid", "missing"));
        assertThat(commandTypes).containsExactly("EVAL_RO");
        assertThat(snapshot.keySet()).containsExactly("valid");
        assertThat(snapshot.get("valid").platformProperties()).isEmpty();
    }

    @Test void proofSnapshotCannotMixWorkerAndPlatformCommits() throws Exception {
        String workerKey = keyspace.base() + ":matching:worker:facts:g";
        String platformKey = keyspace.base() + ":matching:worker:platform-properties:g";
        String write = "redis.call('HSET',KEYS[1],ARGV[1],ARGV[2]); redis.call('HSET',KEYS[2],ARGV[1],ARGV[2]); return 1";
        redis.eval(write, io.lettuce.core.ScriptOutputType.INTEGER, new String[]{workerKey, platformKey}, "w", "{\"version\":0}");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> {
                start.await();
                for (int i = 1; i <= 200; i++) redis.eval(write, io.lettuce.core.ScriptOutputType.INTEGER,
                        new String[]{workerKey, platformKey}, "w", "{\"version\":" + i + "}");
                return null;
            });
            start.countDown();
            for (int i = 0; i < 200; i++) {
                var facts = stores.get(catalog).readFactsSnapshot("g", List.of("w")).get("w");
                assertThat(facts.workerProperties().get("version")).isEqualTo(facts.platformProperties().get("version"));
            }
            writer.get(10, TimeUnit.SECONDS);
        }
    }

    @Test void qualifyingAGroupWithGlobAndSeparatorCharactersDoesNotAffectAnotherGroup() {
        String special = "g:*[x]";
        try (var selected = createCatalog(Map.of(special, Set.of("worker.country")));
             var neighbor = createCatalog(Map.of(special + ":other", Set.of("worker.country")))) {
            selected.upsertWorkerFactsBatch(special, Map.of("a", Map.of("country", "CN")));
            neighbor.upsertWorkerFactsBatch(special + ":other", Map.of("b", Map.of("country", "US")));
            var query = new TaskDescriptor("neighbor", "test-project", special+":other", TaskIdleDisposition.CLOSE_WHEN_IDLE, Map.of("priority","0","maxRetryTimes","1"), neighbor.normalizeRefill(special+":other", List.of(new RefillTarget("country",ANY,100))), null, java.util.Map.of());
            hot(special+":other",List.of("b"));
            refillDeclarations(neighbor,special+":other",Map.of("neighbor",query),100);
            assertThat(takeItems(neighbor,query.workerGroupId(),"worker.country",Map.of(),1)).extracting(h -> h.workerId()).containsExactly("b");
        }
    }
    @Test void restartQualificationUsesRetainedFactsWithoutIndexRebuild() {
        catalog.upsertWorkerFactsBatch("g",Map.of("w",messageFacts("CN","phone")));
        var query=declare("message","worker.messaging.available");
        redis.set(keyspace.base()+":unrelated","kept");
        catalog.close(); catalog=createCatalog(Map.of("g",Set.of("worker.messaging.available")));
        hot("g",List.of("w")); refill("message");
        assertThat(takeItems(catalog,query.workerGroupId(),function(query),Map.of("country",List.of("CN")),1)).extracting(h -> h.workerId()).containsExactly("w");
        assertThat(redis.get(keyspace.base()+":unrelated")).isEqualTo("kept");
    }

    private static WorkerCandidate candidate(Map.Entry<String, Long> held) { return new WorkerCandidate(held.getKey(),held.getValue()); }

    /** Fixture for stock-volume proofs: submit distinct Items through the public correlation port. */
    private static List<WorkerCandidate> takeItems(com.xa.mass.kernel.assignment.WorkerMatching matching,
            String group,String rule,Object input,int count) {
        var requests=new LinkedHashMap<String,WorkerQuery>();
        for(int i=0;i<count;i++)requests.put("message-"+i,new WorkerQuery(rule,input));
        return List.copyOf(matching.take(group,requests).values());
    }
}
