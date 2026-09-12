package com.xa.mass.server.assembly.matching;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.*;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex.TaskQuery;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import com.xa.mass.server.testsupport.RedisTestScope;
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
    private final List<String> commandTypes=new CopyOnWriteArrayList<>();
    private static final TaskItemWorkerSelector ANY=TaskItemWorkerSelector.parse(Map.of());
    private static final TaskItemWorkerSelector CN=country("CN");
    private static final TaskItemWorkerSelector US=country("US");
    @BeforeEach void setUp() {
        testScope=RedisTestScope.create("rule_owner"); keyspace=testScope.keyspace();
        redisClient=RedisClient.create(REDIS_URL);
        redisClient.addListener(new CommandListener() {
            @Override public void commandStarted(CommandStartedEvent event) { commandTypes.add(event.getCommand().getType().toString()); }
        });
        connection=redisClient.connect(); redis=connection.sync();
        catalog=new RedisWorkerMatchingCatalog(redisClient,keyspace,Map.of("g",Set.of("worker.country","worker.messaging.available","proof.worker.facts")));
    }
    @AfterEach void tearDown() {
        if(catalog!=null)catalog.close();
        if(redis!=null)testScope.cleanup(redis);
        if(connection!=null)connection.close();
        if(redisClient!=null)redisClient.shutdown();
    }
    private static TaskItemWorkerSelector country(String country) {
        return TaskItemWorkerSelector.parse(Map.of("worker.country",Map.of("op","eq","values",List.of(country))));
    }
    private TaskQuery bound(String task,String rule) {
        assertThat(catalog.bindTaskRule(task,"g",rule).status()).isIn(MutationStatus.APPLIED,MutationStatus.UNCHANGED);
        return catalog.prepareTaskQueries(Map.of(task,"g")).get(task);
    }
    private static Map<String,String> messageFacts(String country,String phone) {
        return Map.of("country",country,"phone",phone,"messaging.enabled","true");
    }
    private String bindingsKey() { return keyspace.base()+":matching:task:rules"; }
    private String indexKey() { return keyspace.base()+":matching:worker:index:Zw:country"; }
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
        try (var competing = new RedisWorkerMatchingCatalog(redisClient, keyspace, Map.of("g",Set.of("worker.country","worker.messaging.available","proof.worker.facts")));
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


    @Test void explicitDefaultBindingDoesNotRequireFactsAndMissingBindingsNeverRelaxQueries() {
        var query=bound("default",WorkerMatchingCatalog.DEFAULT_RULE_ID);
        assertThat(query.usesIdentitySelection(ANY)).isTrue();
        assertThat(query.usesIdentitySelection(TaskItemWorkerSelector.parse(Map.of("workerId",List.of("not-registered"))))).isTrue();
        assertThat(catalog.prepareTaskQueries(Map.of("missing","g"))).containsEntry("missing",null);
        assertThat(catalog.prepareTaskQueries(Map.of("default","wrong-group"))).containsEntry("default",null);
        redis.hset(bindingsKey(),"default","{bad");
        assertThat(catalog.prepareTaskQueries(Map.of("default","g"))).containsEntry("default",null);
        assertThat(catalog.bindTaskRule("default","g",WorkerMatchingCatalog.DEFAULT_RULE_ID).status()).isEqualTo(MutationStatus.CONFLICT);
    }
    @Test void namedRulesRestrictAnyAndExplicitIdsToTheirMaterializedMembership() {
        catalog.upsertWorkerFactsBatch("g",Map.of("cn",Map.of("country","CN"),"us",Map.of("country","US"),"empty",Map.of()));
        var query=bound("country","worker.country");
        assertThat(query.usesIdentitySelection(ANY)).isFalse();
        assertThat(query.take(Map.of(ANY,100)).get(ANY)).containsExactlyInAnyOrder("cn","us");
        var ids=TaskItemWorkerSelector.parse(Map.of("workerId",List.of("cn","empty")));
        assertThat(query.take(Map.of(ids,100)).get(ids)).containsExactly("cn");
    }
    @Test void hundredTaskBindingsResolveWithOneHmgetAndNoFactsRead() {
        var groups=new LinkedHashMap<String,String>();
        for(int i=0;i<100;i++) { String id="t"+i; groups.put(id,"g"); bound(id,"worker.country"); }
        commandTypes.clear();
        assertThat(catalog.prepareTaskQueries(groups)).hasSize(100).doesNotContainValue(null);
        assertThat(commandTypes).containsExactly("HMGET");
    }
    @Test void hundredWorkersUseOneFactsLuaAndOneTakeAndOneRetain() {
        var facts=new LinkedHashMap<String,Map<String,String>>();
        for(int i=0;i<100;i++)facts.put("w"+i,Map.of("country","CN"));
        // Establish the connection before counting application commands.
        bound("task","worker.country"); commandTypes.clear();
        assertThat(catalog.upsertWorkerFactsBatch("g",facts)).hasSize(100);
        assertThat(commandTypes).containsExactly("EVAL");
        var query=catalog.prepareTaskQueries(Map.of("task","g")).get("task"); commandTypes.clear();
        var selected=query.take(Map.of(CN,100));
        assertThat(selected.get(CN)).hasSize(100);
        assertThat(query.retain(selected).get(CN)).hasSize(100);
        assertThat(commandTypes).containsExactly("EVAL","EVAL");
    }
    @Test void messagesUseSparsePhoneIndexAndRemovingEligibilityRevokesAllPartitions() {
        var facts=new LinkedHashMap<String,Map<String,String>>();
        for(int i=0;i<99;i++)facts.put("other"+i,messageFacts("CN","other"));
        facts.put("target",messageFacts("CN","rare")); catalog.upsertWorkerFactsBatch("g",facts);
        var query=bound("messages","worker.messaging.available");
        var selector=TaskItemWorkerSelector.parse(Map.of("worker.country",Map.of("op","eq","values",List.of("CN")),
                "worker.phone",Map.of("op","eq","values",List.of("rare"))));
        assertThat(query.take(Map.of(selector,1)).get(selector)).containsExactly("target");
        catalog.upsertWorkerFactsBatch("g",Map.of("target",messageFacts("US","new-phone")));
        assertThat(query.retain(Map.of(selector,List.of("target"))).get(selector)).isEmpty();
        assertThat(query.take(Map.of(selector,1)).get(selector)).isEmpty();
        catalog.upsertWorkerFactsBatch("g",Map.of("target",Map.of("country","US","messaging.enabled","false")));
        assertThat(query.retain(Map.of(ANY,List.of("target"))).get(ANY)).isEmpty();
    }
    @Test void takeIsOnlyIndexRotationAndPostHoldRecheckUsesCurrentFacts() {
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","CN")));
        var query=bound("task","worker.country"); var first=query.take(Map.of(CN,1));
        long taken=redis.zscore(indexKey(),"w").longValue() % (1L<<43);
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","US")));
        assertThat(redis.zscore(indexKey(),"w").longValue() % (1L<<43)).isEqualTo(taken);
        assertThat(query.retain(first).get(CN)).isEmpty();
        assertThat(query.take(Map.of(US,1)).get(US)).containsExactly("w");
        assertThat(redis.exists(keyspace.base()+":worker:score:g")).isZero();
    }
    @Test void platformPatchPreservesNestedJsonShapeAndAtomicallyRefreshesProofEligibility() {
        catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("proofPool","A","proofTarget","yes")));
        var query=bound("proof","proof.worker.facts");
        var selector=TaskItemWorkerSelector.parse(Map.of("worker.proofPool",Map.of("op","eq","values",List.of("A")),
                "platform.proofEnabled",Map.of("op","eq","values",List.of("yes"))));
        assertThat(query.take(Map.of(selector,1)).get(selector)).isEmpty();
        commandTypes.clear();
        catalog.patchWorkerPlatformProperties("g","w",Map.of("proofEnabled","yes","array",List.of(),"nested",Map.of("x",List.of("quote\"","back\\"))));
        assertThat(commandTypes).containsExactly("EVAL");
        assertThat(query.take(Map.of(selector,1)).get(selector)).containsExactly("w");
        catalog.patchWorkerPlatformProperties("g","w",Map.of("unrelated","value"));
        assertThat(catalog.loadWorkerFacts("g",List.of("w")).get("w").platformProperties()).containsEntry("array",List.of());
        var remove=new HashMap<String,Object>(); remove.put("proofEnabled",null);
        catalog.patchWorkerPlatformProperties("g","w",remove);
        assertThat(query.retain(Map.of(selector,List.of("w"))).get(selector)).isEmpty();
    }
    @Test void concurrentBindingsKeepExactlyOneWinnerWithoutDefinitions() throws Exception {
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var results=new ArrayList<Future<MutationStatus>>();
            for(int i=0;i<40;i++) { String rule=i%2==0 ? "worker.country" : "worker.messaging.available";
                results.add(executor.submit(()->catalog.bindTaskRule("shared","g",rule).status())); }
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
        redis.zadd(indexKey(),-1,"w");
        assertThatThrownBy(()->catalog.upsertWorkerFactsBatch("g",Map.of("w",Map.of("country","US")))) .isInstanceOf(RuntimeException.class);
        assertThat(catalog.loadWorkerFacts("g",List.of("w")).get("w").workerProperties()).containsEntry("country","CN");
        assertThatThrownBy(()->bound("task","worker.country").take(Map.of(ANY,1))).isInstanceOf(RuntimeException.class);
    }
    @Test void corruptPartitionMetadataCannotPartiallyWriteFactsOrTakeTime() {
        catalog.upsertWorkerFactsBatch("g", Map.of("w", messageFacts("CN", "phone")));
        String key = keyspace.base() + ":matching:worker:index:Zw:messaging";
        redis.hset(key + ":partitions", "w", "{}");
        Double score = redis.zscore(key, "w");
        assertThatThrownBy(() -> catalog.upsertWorkerFactsBatch("g", Map.of("w", messageFacts("US", "new"))))
                .isInstanceOf(RuntimeException.class);
        assertThat(catalog.loadWorkerFacts("g", List.of("w")).get("w").workerProperties())
                .containsEntry("country", "CN");
        assertThatThrownBy(() -> bound("messaging", "worker.messaging.available").take(Map.of(ANY, 1)))
                .isInstanceOf(RuntimeException.class);
        assertThat(redis.zscore(key, "w")).isEqualTo(score);
    }
    @Test void rebuildingAGroupWithGlobAndSeparatorCharactersDoesNotClearAnotherGroup() {
        String special = "g:*[x]";
        try (var selected = new RedisWorkerMatchingCatalog(redisClient, keyspace,
                Map.of(special, Set.of("worker.country")));
             var neighbor = new RedisWorkerMatchingCatalog(redisClient, keyspace,
                Map.of(special + ":other", Set.of("worker.country")))) {
            selected.upsertWorkerFactsBatch(special, Map.of("a", Map.of("country", "CN")));
            neighbor.upsertWorkerFactsBatch(special + ":other", Map.of("b", Map.of("country", "US")));
            selected.rebuildIndexes();
            neighbor.bindTaskRule("neighbor", special + ":other", "worker.country");
            var query = neighbor.prepareTaskQueries(Map.of("neighbor", special + ":other")).get("neighbor");
            assertThat(query.take(Map.of(ANY, 1)).get(ANY)).containsExactly("b");
        }
    }
    @Test void startupRebuildUsesRetainedFactsAndOnlyThisGroupsIndexes() {
        catalog.upsertWorkerFactsBatch("g",Map.of("w",messageFacts("CN","phone")));
        var query=bound("message","worker.messaging.available");
        redis.set(keyspace.base()+":unrelated","kept");
        catalog.rebuildIndexes();
        assertThat(query.take(Map.of(CN,1)).get(CN)).containsExactly("w");
        assertThat(redis.get(keyspace.base()+":unrelated")).isEqualTo("kept");
    }
}
