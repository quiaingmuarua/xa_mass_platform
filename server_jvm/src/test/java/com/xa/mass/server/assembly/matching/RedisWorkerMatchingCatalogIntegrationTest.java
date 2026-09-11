package com.xa.mass.server.assembly.matching;

import com.xa.mass.kernel.task.TaskItemWorkerSelector;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.workermatching.RedisWorkerMatchingCatalog;
import com.xa.mass.workermatching.WorkerMatchingCatalog.MutationStatus;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("redis-owner")
class RedisWorkerMatchingCatalogIntegrationTest {
    private static final long SCALE = 1L << 43;
    private static final TaskItemWorkerSelector CN = TaskItemWorkerSelector.parse(Map.of("worker.country", List.of("CN")));
    private static final TaskItemWorkerSelector US = TaskItemWorkerSelector.parse(Map.of("worker.country", List.of("US")));

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
        assertThatThrownBy(() -> catalog.takeWorkerIds("g", TaskItemWorkerSelector.parse(Map.of("worker.country", List.of("cn"))), 1))
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
        var zz = TaskItemWorkerSelector.parse(Map.of("worker.country", List.of("ZZ")));
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
    void candidateRulesAreCreateOnlyAndContentIdempotent() {
        Map<String, Object> rule = Map.of(
                "worker.region",
                Map.of("$eq", "cn")
        );
        assertThat(catalog.createCandidateRule(
                "task-1",
                "group-1",
                rule
        ).status()).isEqualTo(MutationStatus.APPLIED);
        assertThat(catalog.createCandidateRule(
                "task-1",
                "group-1",
                rule
        ).status()).isEqualTo(MutationStatus.UNCHANGED);
        assertThat(catalog.createCandidateRule(
                "task-1",
                "group-1",
                Map.of("worker.region", Map.of("$eq", "us"))
        ).status()).isEqualTo(MutationStatus.CONFLICT);
        assertThat(catalog.loadCandidateRules(List.of(
                "task-1",
                "missing"
        )))
                .containsEntry("missing", null);
        assertThat(catalog.loadCandidateRules(List.of("task-1"))
                .get("task-1").allocationRule()).isEqualTo(rule);
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
        catalog.createCandidateRule("task-1", "group-1", rule);

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
        assertThat(catalog.loadCandidateRules(List.of("task-1"))
                .get("task-1").allocationRule()).isEqualTo(rule);
    }
}
