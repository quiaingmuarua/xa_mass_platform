package com.xa.mass.server.assembly.matching;

import com.xa.mass.kernel.task.TaskItemWorkerSelector;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.api.v1.contract.task.TaskCreateRequest;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskIdGenerator;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.workermatching.RedisWorkerMatchingCatalog;
import com.xa.mass.workermatching.WorkerMatchingCatalog.MutationStatus;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.event.command.CommandListener;
import io.lettuce.core.event.command.CommandStartedEvent;
import io.lettuce.core.event.command.CommandSucceededEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("redis-owner")
class RedisWorkerMatchingCatalogIntegrationTest {
    private static final long SCALE = 1L << 43;
    private static final TaskItemWorkerSelector CN = TaskItemWorkerSelector.parse(Map.of("worker.country", Map.of("op", "in", "values", List.of("CN"))));
    private static final TaskItemWorkerSelector US = TaskItemWorkerSelector.parse(Map.of("worker.country", Map.of("op", "in", "values", List.of("US"))));


    @Test void namedRuleSharesExistingIndexAndConstrainAnyAndExplicitTargets() {
        enableIndex();
        catalog.upsertWorkerFactsBatch("g", Map.of("cn", Map.of("country", "CN"), "us", Map.of("country", "US"), "absent", Map.of()));
        assertThat(catalog.bindTaskRule("a", "g", "worker.country").status()).isEqualTo(MutationStatus.APPLIED);
        assertThat(catalog.bindTaskRule("a", "g", "worker.country").status()).isEqualTo(MutationStatus.UNCHANGED);
        assertThat(catalog.bindTaskRule("b", "g", "worker.country").status()).isEqualTo(MutationStatus.APPLIED);
        assertThat(catalog.bindTaskAllocationRule("a", "g", Map.of()).status()).isEqualTo(MutationStatus.CONFLICT);
        assertThat(catalog.bindTaskRule("bad", "g", "unknown").status()).isEqualTo(MutationStatus.INVALID);
        assertThat(catalog.bindTaskRule("bad", "disabled", "worker.country").status()).isEqualTo(MutationStatus.INVALID);
        assertThat(redis.exists(rulesKey())).isZero();
        assertThat(catalog.loadTaskRules(List.of("a", "b")).values()).allSatisfy(rule -> {
            assertThat(rule.ruleId()).isEqualTo("worker.country");
            assertThat(rule.allocationRule()).isNull();
        });
        var query = catalog.prepareTaskQuery("a", "g");
        var any = TaskItemWorkerSelector.parse(Map.of());
        assertThat(query.take(Map.of(any, 100)).get(any)).containsExactlyInAnyOrder("cn", "us");
        var targets = TaskItemWorkerSelector.parse(Map.of("workerId", List.of("absent", "us", "missing")));
        assertThat(query.take(Map.of(targets, 100)).get(targets)).containsExactly("us");
        assertThat(catalog.prepareTaskQuery("a", "other")).isNull();
        for (String corrupt : List.of("worker.country", "{}", "[]", "{", "{\"ruleId\":\"worker.country\",\"workerGroupId\":\"g\",\"extra\":true}")) {
            redis.hset(bindingsKey(), "broken", corrupt);
            assertThat(catalog.prepareTaskQuery("broken", "g")).isNull();
            assertThat(catalog.loadTaskRules(List.of("broken")).get("broken")).isNull();
            assertThat(redis.hget(bindingsKey(), "broken")).isEqualTo(corrupt);
        }
    }

    @Test void hundredItemQueryUsesOneBindingReadOneTakeAndOneRecheck() {
        enableIndex();
        var facts = new LinkedHashMap<String, Map<String, String>>();
        var limits = new LinkedHashMap<TaskItemWorkerSelector, Integer>();
        var held = new LinkedHashMap<TaskItemWorkerSelector, List<String>>();
        for (int i=0; i<100; i++) {
            String id = "worker-"+i;
            facts.put(id, Map.of("country", "CN"));
            var selector = TaskItemWorkerSelector.parse(Map.of("workerId", List.of(id)));
            limits.put(selector, 1);
            held.put(selector, List.of(id));
        }
        catalog.upsertWorkerFactsBatch("g", facts);
        catalog.bindTaskRule("task", "g", "worker.country");
        var calls = new CopyOnWriteArrayList<String>();
        CommandListener listener = new CommandListener() {
            @Override public void commandStarted(CommandStartedEvent event) { calls.add(event.getCommand().getType().toString()); }
        };
        redisClient.addListener(listener);
        try {
            catalog.close();
            catalog.prepareTaskQuery("task", "g");
            calls.clear();
            var query = catalog.prepareTaskQuery("task", "g");
            assertThat(query.take(limits).values()).allMatch(ids -> ids.size() == 1);
            assertThat(query.retain(held).values()).allMatch(ids -> ids.size() == 1);
            assertThat(calls).containsExactly("HMGET", "EVAL", "EVAL");
            calls.clear();
            limits.put(TaskItemWorkerSelector.parse(Map.of()), 1);
            assertThatThrownBy(() -> query.take(limits)).isInstanceOf(IllegalArgumentException.class);
            assertThat(calls).isEmpty();
        } finally { redisClient.removeListener(listener); }
    }

    @Test void countryUnionRotatesAcrossPrefixesAndRechecksLiveFactsWithoutReloadingThem() {
        enableIndex();
        catalog.upsertWorkerFactsBatch("g", Map.of("cn", Map.of("country", "CN"), "us", Map.of("country", "US")));
        catalog.bindTaskRule("task", "g", "worker.country");
        var query = catalog.prepareTaskQuery("task", "g");
        var union = TaskItemWorkerSelector.parse(Map.of("worker.country", Map.of("op", "in", "values", List.of("CN", "US", "CN"))));
        redis.zadd(indexKey(), (double)(65*SCALE+100), "cn", (double)(538*SCALE+1), "us");
        assertThat(query.take(Map.of(union, 1)).get(union)).containsExactly("us");
        assertThat(query.take(Map.of(union, 1)).get(union)).containsExactly("cn");
        catalog.upsertWorkerFactsBatch("g", Map.of("cn", Map.of("country", "US")));
        assertThat(query.retain(Map.of(CN, List.of("cn"))).get(CN)).isEmpty();
        assertThat(query.retain(Map.of(US, List.of("cn"))).get(US)).containsExactly("cn");
        catalog.upsertWorkerFactsBatch("g", Map.of("cn", Map.of()));
        assertThat(query.retain(Map.of(union, List.of("cn"))).get(union)).isEmpty();
        redis.zadd(indexKey(), -1, "us");
        var ids = TaskItemWorkerSelector.parse(Map.of("workerId", List.of("us")));
        assertThatThrownBy(() -> query.take(Map.of(ids, 1))).isInstanceOf(RuntimeException.class);
        assertThat(redis.zscore(indexKey(), "us")).isEqualTo(-1);
    }

    private void enableIndex() {
        catalog.close();
        catalog = new RedisWorkerMatchingCatalog(redisClient, keyspace, java.util.Set.of("g"));
        catalog.rebuildCountryIndexes();
    }

    private String indexKey() { return keyspace.base() + ":matching:worker:index:country:g"; }

    @Test void countryIndexTakesInOrderPreservesTimeAndMovesOrRemovesMembership() throws Exception {
        enableIndex();
        catalog.upsertWorkerFactsBatch("g", Map.of("a", Map.of("country", "CN"), "b", Map.of("country", "CN"),
                "c", Map.of("country", "US")));
        assertThat(redis.zscore(indexKey(), "a").longValue()).isEqualTo(65 * SCALE);
        assertThat(catalog.takeWorkerIds("g", CN, 1)).containsExactly("a");
        long touched = redis.zscore(indexKey(), "a").longValue();
        assertThat(touched % SCALE).isPositive();
        assertThat(catalog.takeWorkerIds("g", CN, 1)).containsExactly("b");
        var facts = Map.of("country", "CN", "network", "wifi");
        assertThat(catalog.upsertWorkerFactsBatch("g", Map.of("a", facts)).get("a").status()).isEqualTo(MutationStatus.APPLIED);
        assertThat(catalog.upsertWorkerFactsBatch("g", Map.of("a", facts)).get("a").status()).isEqualTo(MutationStatus.UNCHANGED);
        assertThat(redis.zscore(indexKey(), "a").longValue()).isEqualTo(touched);
        catalog.upsertWorkerFactsBatch("g", Map.of("a", Map.of("country", "US")));
        assertThat(redis.zscore(indexKey(), "a").longValue()).isEqualTo(538 * SCALE + touched % SCALE);
        assertThat(catalog.retainWorkerIds("g", CN, List.of("a", "b", "c", "missing"))).containsExactly("b");
        assertThat(catalog.retainWorkerIds("g", US, List.of("a", "b", "c"))).containsExactlyInAnyOrder("a", "c");
        catalog.patchWorkerPlatformProperties("g", "b", Map.of("country", "US"));
        assertThat(catalog.retainWorkerIds("g", CN, List.of("b"))).containsExactly("b");
        for (Map<String, String> replacement : List.of(Map.<String, String>of(), Map.of("country", ""), Map.of("country", "cn"))) {
            catalog.upsertWorkerFactsBatch("g", Map.of("b", replacement));
            assertThat(redis.zscore(indexKey(), "b")).isNull();
            assertThat(catalog.loadWorkerFacts("g", List.of("b")).get("b").workerProperties()).isEqualTo(replacement);
        }
        assertThatThrownBy(() -> catalog.takeWorkerIds("disabled", CN, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.takeWorkerIds("g", CN, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.takeWorkerIds("g", TaskItemWorkerSelector.parse(Map.of("worker.country", Map.of("op", "in", "values", List.of("cn")))), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void startupRebuildStreamsExistingFactsAndDoesNotChangeThem() {
        for (int page = 0; page < 4; page++) {
            Map<String, Map<String, String>> batch = new LinkedHashMap<>();
            for (int i = 0; i < 100; i++) batch.put("w" + (page * 100 + i), Map.of("country", i % 2 == 0 ? "CN" : "US"));
            catalog.upsertWorkerFactsBatch("g", batch);
        }
        var before = redis.hgetall(keyspace.base() + ":matching:worker:facts:g");
        redis.zadd(indexKey(), 1, "orphan");
        enableIndex();
        assertThat(redis.zcard(indexKey())).isEqualTo(400);
        assertThat(redis.zscore(indexKey(), "orphan")).isNull();
        assertThat(redis.hgetall(keyspace.base() + ":matching:worker:facts:g")).isEqualTo(before);
        assertThat(catalog.takeWorkerIds("g", CN, 100)).hasSize(100);
        assertThat(catalog.takeWorkerIds("g", US, 100)).hasSize(100);
        catalog.rebuildCountryIndexes();
        assertThat(redis.zscore(indexKey(), "w0").longValue() % SCALE).isZero();
    }

    @Test void busyPrefixCanRotatePastHundredWithoutRemovingMembers() throws Exception {
        enableIndex();
        for (int start = 0; start < 200; start += 100) {
            Map<String, Map<String, String>> batch = new LinkedHashMap<>();
            for (int i = start; i < start + 100; i++) batch.put(String.format("w%03d", i), Map.of("country", "CN"));
            catalog.upsertWorkerFactsBatch("g", batch);
        }
        var first = catalog.takeWorkerIds("g", CN, 100);
        assertThat(first.getFirst()).isEqualTo("w000");
        var second = catalog.takeWorkerIds("g", CN, 100);
        assertThat(second).doesNotContainAnyElementsOf(first).contains("w199");
        long secondTime = redis.zscore(indexKey(), "w199").longValue();
        Thread.sleep(3);
        catalog.takeWorkerIds("g", CN, 100);
        assertThat(redis.zscore(indexKey(), "w000").longValue()).isGreaterThan(secondTime);
        assertThat(redis.zcard(indexKey())).isEqualTo(200);
    }

    @Test void highestCountryRangePreservesLowIntegerDigitsAndSharesOneBatchTime() {
        enableIndex();
        var zz = TaskItemWorkerSelector.parse(Map.of("worker.country", Map.of("op", "in", "values", List.of("ZZ"))));
        catalog.upsertWorkerFactsBatch("g", Map.of("z1", Map.of("country", "ZZ"), "z2", Map.of("country", "ZZ"),
                "aa", Map.of("country", "AA")));
        long exact = 675 * SCALE + 1234567890123L;
        redis.zadd(indexKey(), (double) exact, "z1");
        catalog.upsertWorkerFactsBatch("g", Map.of("z1", Map.of("country", "CN")));
        assertThat(redis.zscore(indexKey(), "z1").longValue()).isEqualTo(65 * SCALE + 1234567890123L);
        catalog.upsertWorkerFactsBatch("g", Map.of("z1", Map.of("country", "ZZ")));
        assertThat(redis.zscore(indexKey(), "z1").longValue()).isEqualTo(exact);
        assertThat(catalog.takeWorkerIds("g", zz, 100)).containsExactly("z2", "z1");
        assertThat(redis.zscore(indexKey(), "z1")).isEqualTo(redis.zscore(indexKey(), "z2"));
        assertThat(redis.zscore(indexKey(), "z1").longValue() / SCALE).isEqualTo(675);
        assertThat(catalog.retainWorkerIds("g", zz, List.of("aa", "z1", "z2"))).containsExactlyInAnyOrder("z1", "z2");
    }

    @Test void corruptFactsFailStartupRebuild() {
        redis.hset(keyspace.base() + ":matching:worker:facts:g", "w", "not-json");
        assertThatThrownBy(this::enableIndex).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void concurrentFactsAndTakeNeverSplitCountryMembershipOrEraseTakenTime() throws Exception {
        enableIndex();
        catalog.upsertWorkerFactsBatch("g", Map.of("w", Map.of("country", "CN")));
        catalog.takeWorkerIds("g", CN, 1);
        long initialTime = redis.zscore(indexKey(), "w").longValue() % SCALE;
        try (var other = new RedisWorkerMatchingCatalog(redisClient, keyspace, java.util.Set.of("g"));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = executor.submit(() -> {
                for (int i = 0; i < 300; i++) other.upsertWorkerFactsBatch("g", Map.of("w", Map.of("country", i % 2 == 0 ? "CN" : "US")));
            });
            for (int i = 0; i < 300; i++) {
                catalog.takeWorkerIds("g", i % 2 == 0 ? CN : US, 1);
                Long code = redis.eval("""
                        local facts = cjson.decode(redis.call('HGET', KEYS[1], 'w'))
                        local code = math.floor(tonumber(redis.call('ZSCORE', KEYS[2], 'w')) / 8796093022208)
                        if (facts.country == 'CN' and code == 65) or (facts.country == 'US' and code == 538) then return 1 end
                        return 0
                        """, io.lettuce.core.ScriptOutputType.INTEGER,
                        new String[]{keyspace.base() + ":matching:worker:facts:g", indexKey()});
                assertThat(code).isEqualTo(1L);
                assertThat(redis.zscore(indexKey(), "w").longValue() % SCALE).isGreaterThanOrEqualTo(initialTime);
            }
            writer.get(10, TimeUnit.SECONDS);
        }
    }

    private RedisTestScope testScope;
    private RedisKeyspace keyspace;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private RedisWorkerMatchingCatalog catalog;

    @BeforeEach
    void setUp() {
        testScope = RedisTestScope.create("worker_matching_owner");
        keyspace = testScope.keyspace();
        redisClient = RedisClient.create(REDIS_URL);
        connection = redisClient.connect(StringCodec.UTF8);
        redis = connection.sync();
        catalog = new RedisWorkerMatchingCatalog(redisClient, keyspace, java.util.Set.of());
    }

    @AfterEach
    void tearDown() {
        if (redis != null) {
            testScope.cleanup(redis);
        }
        if (catalog != null) {
            catalog.close();
        }
        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
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
        try (var competing = new RedisWorkerMatchingCatalog(redisClient, keyspace, java.util.Set.of());
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

    @Test
    void taskBindingsAreCreateOnlyAndReuseSharedDefinitions() {
        Map<String, Object> rule = Map.of(
                "worker.region",
                Map.of("$eq", "cn")
        );
        assertThat(catalog.bindTaskAllocationRule(
                "task-1",
                "group-1",
                rule
        ).status()).isEqualTo(MutationStatus.APPLIED);
        assertThat(catalog.bindTaskAllocationRule(
                "task-1",
                "group-1",
                rule
        ).status()).isEqualTo(MutationStatus.UNCHANGED);
        assertThat(catalog.bindTaskAllocationRule(
                "task-1",
                "group-1",
                Map.of("worker.region", Map.of("$eq", "us"))
        ).status()).isEqualTo(MutationStatus.CONFLICT);
        assertThat(catalog.loadTaskRules(List.of(
                "task-1",
                "missing"
        )))
                .containsEntry("missing", null);
        assertThat(catalog.loadTaskRules(List.of("task-1"))
                .get("task-1").allocationRule()).isEqualTo(rule);
        assertThat(catalog.bindTaskAllocationRule("task-2", "group-1", rule).status()).isEqualTo(MutationStatus.APPLIED);
        var loaded = catalog.loadTaskRules(List.of("task-2", "missing", "task-1", "task-2"));
        assertThat(loaded.keySet()).containsExactly("task-2", "missing", "task-1");
        assertThat(loaded.get("task-1")).isSameAs(loaded.get("task-2"));
        assertThat(loaded.get("task-1").ruleId()).matches("rule-[0-9a-f]{64}");
        assertThat(redis.hlen(rulesKey())).isEqualTo(1); // Conflicting content did not create an orphan.
        assertThat(redis.hlen(bindingsKey())).isEqualTo(2);
        assertThat(catalog.bindTaskAllocationRule("task-1", "other-group", rule).status()).isEqualTo(MutationStatus.CONFLICT);
        assertThat(catalog.bindTaskAllocationRule("task-3", "other-group", rule).status()).isEqualTo(MutationStatus.APPLIED);
        assertThat(catalog.loadTaskRules(List.of("task-3")).get("task-3").ruleId())
                .isNotEqualTo(loaded.get("task-1").ruleId());
    }

    @Test
    void canonicalIdentityPreservesArraysNumbersAndOperatorNames() {
        Map<String, Object> conditions = new LinkedHashMap<>();
        conditions.put("$gte", 1);
        conditions.put("$lt", 10);
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("$lt", 10);
        reordered.put("$gte", 1);
        catalog.bindTaskAllocationRule("a", "g", Map.of("worker.capacity", conditions));
        catalog.bindTaskAllocationRule("b", "g", Map.of("worker.capacity", reordered));
        assertThat(catalog.loadTaskRules(List.of("a", "b")).get("a"))
                .isEqualTo(catalog.loadTaskRules(List.of("a", "b")).get("b"));
        var variants = List.<Map<String, Object>>of(
                Map.of("worker.region", Map.of("$in", List.of("cn", "us"))),
                Map.of("worker.region", Map.of("$in", List.of("us", "cn"))),
                Map.of("worker.capacity", Map.of("$eq", 1)),
                Map.of("worker.capacity", Map.of("$eq", 1.0)),
                Map.of("worker.capacity", Map.of("$equal", 1)),
                Map.of("worker.capacity", Map.of("$eq", new BigDecimal("1.000000000000000000000000001")))
        );
        List<String> ids = IntStream.range(0, variants.size()).mapToObj(i -> "variant-" + i).toList();
        for (int i = 0; i < variants.size(); i++) catalog.bindTaskAllocationRule(ids.get(i), "g", variants.get(i));
        assertThat(catalog.loadTaskRules(ids).values()).doesNotContainNull()
                .extracting(rule -> rule.ruleId()).doesNotHaveDuplicates();
    }

    @Test
    void retriesFillEitherMissingStageWithoutRebindingOrOverwritingCorruption() {
        catalog.bindTaskAllocationRule("task", "g", Map.of());
        String id = storedRuleId("task");
        String raw = redis.hget(rulesKey(), id);
        redis.hdel(rulesKey(), id);
        assertThat(catalog.loadTaskRules(List.of("task"))).containsEntry("task", null);
        assertThat(catalog.bindTaskAllocationRule("task", "g", Map.of()).status()).isEqualTo(MutationStatus.APPLIED);
        redis.hdel(bindingsKey(), "task");
        assertThat(catalog.bindTaskAllocationRule("task", "g", Map.of()).status()).isEqualTo(MutationStatus.APPLIED);
        assertThat(catalog.bindTaskAllocationRule("task", "g", Map.of()).status()).isEqualTo(MutationStatus.UNCHANGED);
        assertThat(redis.hget(rulesKey(), id)).isEqualTo(raw);
        redis.hset(rulesKey(), id, "broken");
        assertThat(catalog.bindTaskAllocationRule("task", "g", Map.of()).status()).isEqualTo(MutationStatus.CONFLICT);
        assertThat(redis.hget(rulesKey(), id)).isEqualTo("broken");
        redis.hset(bindingsKey(), "bad-binding", "broken");
        assertThat(catalog.bindTaskAllocationRule("bad-binding", "other", Map.of()).status()).isEqualTo(MutationStatus.CONFLICT);
        assertThat(redis.hlen(rulesKey())).isEqualTo(1);
    }

    @Test
    void invalidInputAndWrongKeyTypesDoNotCreatePartialBindings() {
        assertThat(catalog.bindTaskAllocationRule("", "g", Map.of()).status()).isEqualTo(MutationStatus.INVALID);
        assertThat(catalog.bindTaskAllocationRule("task", "", Map.of()).status()).isEqualTo(MutationStatus.INVALID);
        assertThat(catalog.bindTaskAllocationRule("task", "g", null).status()).isEqualTo(MutationStatus.INVALID);
        assertThat(catalog.bindTaskAllocationRule("task", "g", Map.of("value", Double.NaN)).status())
                .isEqualTo(MutationStatus.INVALID);
        assertThat(catalog.loadTaskRules(List.of())).isEmpty();
        assertThatThrownBy(() -> catalog.loadTaskRules(List.of(""))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.loadTaskRules(IntStream.range(0, 101).mapToObj(i -> "t" + i).toList()))
                .isInstanceOf(IllegalArgumentException.class);
        for (String wrongKey : List.of(bindingsKey(), rulesKey())) {
            redis.set(wrongKey, "wrong-type");
            assertThatThrownBy(() -> catalog.bindTaskAllocationRule("task", "g", Map.of())).isInstanceOf(RuntimeException.class);
            assertThat(redis.exists(wrongKey.equals(bindingsKey()) ? rulesKey() : bindingsKey())).isZero();
            redis.unlink(wrongKey);
        }
        catalog.bindTaskAllocationRule("task", "g", Map.of());
        redis.unlink(rulesKey());
        redis.set(rulesKey(), "wrong-type");
        assertThatThrownBy(() -> catalog.loadTaskRules(List.of("task"))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void corruptOrMissingDefinitionsNeverFallBackToTaskAddressedRules() throws Exception {
        var invalid = List.of("not-json", "[]", "{}",
                "{\"workerGroupId\":\"\",\"allocationRule\":{}}",
                "{\"workerGroupId\":\"g\",\"allocationRule\":{},\"extra\":true}",
                "{\"workerGroupId\":\"g\",\"allocationRule\":[],\"extra\":true}");
        List<String> tasks = new ArrayList<>();
        for (int i = 0; i < invalid.size(); i++) {
            String raw = invalid.get(i);
            String id = "rule-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
            String task = "invalid-" + i;
            tasks.add(task);
            redis.hset(bindingsKey(), task, bindingJson("g", id));
            redis.hset(rulesKey(), id, raw);
        }
        catalog.bindTaskAllocationRule("mismatch", "g", Map.of());
        String id = storedRuleId("mismatch");
        redis.hset(rulesKey(), id, "{\"workerGroupId\":\"other\",\"allocationRule\":{}}");
        redis.hset(rulesKey(), "legacy-task", "{\"workerGroupId\":\"g\",\"allocationRule\":{}}");
        redis.hset(bindingsKey(), "missing-definition", bindingJson("g", "rule-" + "0".repeat(64)));
        tasks.addAll(List.of("mismatch", "legacy-task", "missing-definition", "missing"));
        assertThat(catalog.loadTaskRules(tasks).values()).containsOnlyNulls();
    }

    @Test
    void concurrentBindingsChooseOneRuleAndShareDefinitionsAcrossTasks() throws Exception {
        try (var other = new RedisWorkerMatchingCatalog(redisClient, keyspace, java.util.Set.of());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch start = new CountDownLatch(1);
            var attempts = IntStream.range(0, 32).mapToObj(i -> executor.submit(() -> {
                start.await();
                return (i % 2 == 0 ? catalog : other).bindTaskAllocationRule("contested", "g",
                        Map.of("worker.region", Map.of("$eq", i % 2 == 0 ? "cn" : "us"))).status();
            })).toList();
            start.countDown();
            List<MutationStatus> results = new ArrayList<>();
            for (var attempt : attempts) results.add(attempt.get(5, TimeUnit.SECONDS));
            assertThat(results).containsOnly(MutationStatus.APPLIED, MutationStatus.UNCHANGED, MutationStatus.CONFLICT);
            assertThat(Collections.frequency(results, MutationStatus.APPLIED)).isEqualTo(1);
            assertThat(Collections.frequency(results, MutationStatus.CONFLICT)).isEqualTo(16);
            assertThat(redis.hlen(rulesKey())).isEqualTo(1);
            var rule = catalog.loadTaskRules(List.of("contested")).get("contested");
            var shared = IntStream.range(0, 100).mapToObj(i -> executor.submit(() ->
                    (i % 2 == 0 ? catalog : other).bindTaskAllocationRule("shared-" + i, "g", rule.allocationRule()))).toList();
            for (var attempt : shared) assertThat(attempt.get(5, TimeUnit.SECONDS).status()).isEqualTo(MutationStatus.APPLIED);
            assertThat(redis.hlen(rulesKey())).isEqualTo(1);
            assertThat(redis.hvals(bindingsKey())).hasSize(101).containsOnly(bindingJson("g", rule.ruleId()));
        }
    }

    @Test
    void bindingAndHundredTaskResolutionEachUseOneCommandAndDefinitionsTravelOnce() throws Exception {
        var commands = new CopyOnWriteArrayList<String>();
        var replies = new CopyOnWriteArrayList<Object>();
        var loadedReply = new CountDownLatch(1);
        CommandListener listener = new CommandListener() {
            @Override public void commandStarted(CommandStartedEvent event) {
                commands.add(event.getCommand().getType().toString());
            }
            @Override public void commandSucceeded(CommandSucceededEvent event) {
                Object reply = event.getCommand().getOutput().get();
                if (reply instanceof List<?> list && list.size() == 3 && list.getFirst() instanceof List<?>) {
                    replies.add(reply);
                    loadedReply.countDown();
                }
            }
        };
        redisClient.addListener(listener);
        try {
            catalog.bindTaskAllocationRule("warmup", "g", Map.of()); // Listeners attach when a connection opens.
            var tasks = IntStream.range(0, 100).mapToObj(i -> "task-" + i).toList();
            for (String task : tasks) {
                commands.clear();
                catalog.bindTaskAllocationRule(task, "g", Map.of());
                assertThat(commands).containsExactly("EVAL");
            }
            commands.clear();
            replies.clear();
            var loaded = catalog.loadTaskRules(tasks);
            assertThat(loaded).hasSize(100);
            assertThat(loaded.values()).allMatch(rule -> rule == loaded.get("task-0"));
            assertThat(commands).containsExactly("EVAL");
            assertThat(loadedReply.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(replies).hasSize(1);
            List<?> reply = (List<?>) replies.getFirst();
            assertThat((List<?>) reply.get(0)).hasSize(100);
            assertThat((List<?>) reply.get(1)).hasSize(1);
            assertThat((List<?>) reply.get(2)).hasSize(1);
        } finally {
            redisClient.removeListener(listener);
        }
    }

    private String storedRuleId(String taskId) {
        return (String) tools.jackson.databind.json.JsonMapper.builder().build()
                .readValue(redis.hget(bindingsKey(), taskId), Map.class).get("ruleId");
    }

    private static String bindingJson(String group, String ruleId) {
        return tools.jackson.databind.json.JsonMapper.builder().build()
                .writeValueAsString(new java.util.TreeMap<>(Map.of("workerGroupId", group, "ruleId", ruleId)));
    }

    private String rulesKey() { return keyspace.base() + ":matching:candidate:rules"; }
    private String bindingsKey() { return keyspace.base() + ":matching:task:rules"; }

    @Test
    void lostOwnerReplyCanBeRetriedWithoutCreatingAnotherDefinition() {
        catalog = spy(catalog);
        doAnswer(invocation -> {
            invocation.callRealMethod(); // Real Redis committed, but the caller does not receive the result.
            throw new IllegalStateException("injected lost owner reply");
        }).when(catalog).bindTaskAllocationRule("task", "g", Map.of());
        assertThatThrownBy(() -> catalog.bindTaskAllocationRule("task", "g", Map.of())).isInstanceOf(IllegalStateException.class);
        var before = catalog.loadTaskRules(List.of("task")).get("task");
        doCallRealMethod().when(catalog).bindTaskAllocationRule("task", "g", Map.of());
        assertThat(catalog.bindTaskAllocationRule("task", "g", Map.of()).status()).isEqualTo(MutationStatus.UNCHANGED);
        assertThat(catalog.loadTaskRules(List.of("task")).get("task")).isEqualTo(before);
        assertThat(redis.hlen(rulesKey())).isEqualTo(1);
    }

    @Test
    void kernelCreationFailureLeavesBindingAndAnotherTaskCanReuseItsRule() {
        var workers = mock(WorkerResourceCatalog.class);
        var tasks = mock(TaskRuntime.class);
        var ids = mock(TaskIdGenerator.class);
        when(workers.getWorkerGroupDescriptors(List.of("g"))).thenReturn(Map.of("g",
                new WorkerResourceCatalog.WorkerGroupDescriptor("g", Map.of(), java.util.Set.of())));
        when(ids.nextTaskId()).thenReturn("failed-task", "next-task");
        when(tasks.createTask(any())).thenThrow(new IllegalStateException("kernel unavailable"))
                .thenReturn(new TaskRuntime.TaskCreationResult(TaskRuntime.TaskCreationStatus.CREATED));
        var service = new TaskCreationService(workers, catalog, tasks, ids);
        var request = new TaskCreateRequest("g", Map.of(), null, null, null, null);
        assertThatThrownBy(() -> service.create(request)).isInstanceOf(ServerException.class);
        assertThat(catalog.loadTaskRules(List.of("failed-task")).get("failed-task")).isNotNull();
        assertThat(service.create(request).taskId()).isEqualTo("next-task");
        var loaded = catalog.loadTaskRules(List.of("failed-task", "next-task"));
        assertThat(loaded.get("failed-task")).isSameAs(loaded.get("next-task"));
        assertThat(redis.hlen(rulesKey())).isEqualTo(1);
    }

    @Test
    void persistentFactsAndRulesSurviveCatalogInstanceReplacement() {
        Map<String, Object> rule = Map.of(
                "worker.region",
                Map.of("$eq", "cn")
        );
        catalog.upsertWorkerFactsBatch("group-1",
                Map.of("worker-1", Map.of("region", "cn", "capacity", "2")));
        catalog.patchWorkerPlatformProperties(
                "group-1",
                "worker-1",
                Map.of("network", "wifi")
        );
        catalog.bindTaskAllocationRule("task-1", "group-1", rule);

        catalog.close();
        catalog = new RedisWorkerMatchingCatalog(redisClient, keyspace, java.util.Set.of());

        var facts = catalog.loadWorkerFacts(
                "group-1",
                List.of("worker-1")
        ).get("worker-1");
        assertThat(facts.workerProperties())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "region", "cn",
                        "capacity", "2"
                ));
        assertThat(facts.platformProperties())
                .containsExactlyEntriesOf(Map.of("network", "wifi"));
        assertThat(catalog.loadTaskRules(List.of("task-1"))
                .get("task-1").allocationRule()).isEqualTo(rule);
    }
}
