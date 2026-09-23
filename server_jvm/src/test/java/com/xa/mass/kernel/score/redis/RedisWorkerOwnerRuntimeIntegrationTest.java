package com.xa.mass.kernel.score.redis;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.SchedulingState;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerSchedulingChangeStatus;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import static com.xa.mass.kernel.score.redis.WorkerScoreEncoding.*;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.score.redis.RedisWorkerScoreCore;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.RegistrationStatus;
import com.xa.mass.kernel.worker.redis.RedisWorkerResourceCatalog;
import com.xa.mass.server.testsupport.RedisTestScope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

@Tag("redis-owner")
class RedisWorkerOwnerRuntimeIntegrationTest {

    private RedisTestScope testScope;
    private RedisKeyspace keyspace;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private RedisWorkerScoreCore scoreCore;
    private RedisWorkerResourceCatalog catalog;

    @BeforeEach
    void setUp() {
        testScope = RedisTestScope.create("java_worker_owner");
        keyspace = testScope.keyspace();
        redisClient = RedisClient.create(REDIS_URL);
        connection = redisClient.connect(StringCodec.UTF8);
        redis = connection.sync();
        scoreCore = new RedisWorkerScoreCore(redisClient, keyspace);
        catalog = new RedisWorkerResourceCatalog(redisClient, scoreCore, keyspace);
    }

    @AfterEach
    void tearDown() {
        if (redis != null) {
            testScope.cleanup(redis);
        }
        if (catalog != null) {
            catalog.close();
        }
        if (scoreCore != null) {
            scoreCore.close();
        }
        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void networkActivationRefreshesPastGenerationAndPreservesExecutionTime() {
        long now = redisTimeMillis() / SLOT_MILLIS;
        long floor = now - 100;
        var observations = new LinkedHashMap<String, Long>();
        for (var entry : Map.of("cold", 1L, "above", floor + 10,
                "future", now + 100, "pause", MAX_TIME_SLOT).entrySet()) {
            redis.zadd(scoreKey("activation"), workerScore(-1, entry.getValue(), 0), entry.getKey());
            observations.put(entry.getKey(), (now - 1) * SLOT_MILLIS);
        }
        var connected = scoreCore.rewriteCurrentPolarityWithinTimeFence("activation", observations,
                WorkerScorePolarity.HOT_ACQUIRE, floor * SLOT_MILLIS);
        assertThat(connected.get("cold").score()).isEqualTo(workerScore(1, now - 1, 0));
        assertThat(connected.get("above").score()).isEqualTo(workerScore(1, now - 1, 0));
        assertThat(connected.get("future").score()).isEqualTo(workerScore(1, now + 100, 0));
        assertThat(connected.get("pause").score()).isEqualTo(workerScore(1, MAX_TIME_SLOT, 0));
        var disconnected = scoreCore.rewriteCurrentPolarityWithinTimeFence("activation", observations,
                WorkerScorePolarity.RECOVERY_RECHECK, 0);
        for (String id : List.of("cold", "above")) {
            assertThat(disconnected.get(id).score()).isEqualTo(workerScore(-1, now, 0));
        }
        for (String id : List.of("future", "pause")) {
            assertThat(disconnected.get(id).score()).isEqualTo(-connected.get(id).score());
        }
        redis.zadd(scoreKey("activation"), workerScore(-1, floor - 10, 1), "old-evidence");
        var old = scoreCore.rewriteCurrentPolarityWithinTimeFence("activation",
                Map.of("old-evidence", (floor - 1) * SLOT_MILLIS), WorkerScorePolarity.HOT_ACQUIRE,
                floor * SLOT_MILLIS).get("old-evidence");
        assertThat(old.status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(old.score()).isEqualTo(workerScore(-1, floor - 10, 1));
    }

    @Test
    void identityHintAcquiresCurrentDueGenerationAndOnlyReturnedExecutionFenceCanReleaseIt() {
        long now = redisTimeMillis();
        long original = workerScore(1, (now - 30_000) / SLOT_MILLIS, 0);
        long later = original + 200;
        redis.zadd(scoreKey("hint"), later, "w");
        var commands = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var listener = commandListener(commands);
        redisClient.addListener(listener);
        WorkerScoreTransitionResult execution;
        try {
            assertThat(scoreCore.acquireObservedHotScoreLeases("hint", Map.of("w", original), now + 5_000).get("w"))
                    .isEqualTo(new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.STALE, later));
            commands.clear();
            execution = scoreCore.acquireCurrentHotScoreLeases("hint", List.of("w"), now + 5_000).get("w");
        } finally { redisClient.removeListener(listener); }
        assertThat(commands).containsExactly("EVAL");
        assertThat(execution).isEqualTo(new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.TRANSITIONED, workerScore(1, (now + 5_000) / SLOT_MILLIS, 0)));
        long releaseAt = redisTimeMillis() + 500;
        for (long old : new long[]{original, later}) {
            assertThat(scoreCore.releaseScoreHolds("hint", Map.of("w", old), releaseAt).get("w").status())
                    .isEqualTo(WorkerScoreTransitionStatus.INVALID);
        }
        assertThat(scoreCore.releaseScoreHolds("hint", Map.of("w", 0L), releaseAt).get("w").status())
                .isEqualTo(WorkerScoreTransitionStatus.INVALID);
        assertThat(scoreCore.releaseObservedHotScoreHolds("hint", Map.of("w", 0L), releaseAt).get("w").status())
                .isEqualTo(WorkerScoreTransitionStatus.INVALID);
        assertThat(redis.zscore(scoreKey("hint"), "w")).isEqualTo((double) execution.score());
        assertThat(scoreCore.releaseScoreHolds("hint", Map.of("w", execution.score()), releaseAt).get("w").status())
                .isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
    }

    @Test
    void identityHintsRejectUnavailableAndCorruptMembersWithoutRepairOrCorruptEcho() {
        long now = redisTimeMillis();
        long future = workerScore(1, (now + 30_000) / SLOT_MILLIS, 0);
        Map<String, Double> stored = Map.of(
                "recovery", (double) -future, "occupied", (double) (future + 1),
                "paused", (double) workerScore(1, MAX_TIME_SLOT, 1),
                "zero", 0d, "fraction", future + 0.5,
                "overflow", (double) workerScore(1, MAX_TIME_SLOT + 1, 1),
                "infinity", Double.POSITIVE_INFINITY);
        stored.forEach((id, score) -> redis.zadd(scoreKey("hint"), score, id));
        var ids = new java.util.ArrayList<>(stored.keySet());
        ids.add("missing");
        var results = scoreCore.acquireCurrentHotScoreLeases("hint", ids, now + 5_000);
        for (String id : List.of("recovery", "occupied", "paused", "missing")) {
            assertThat(results.get(id).status()).as(id).isEqualTo(WorkerScoreTransitionStatus.STALE);
        }
        for (String id : List.of("zero", "fraction", "overflow", "infinity")) {
            assertThat(results.get(id)).as(id).isEqualTo(new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.INVALID, null));
        }
        assertThat(results.get("missing").score()).isNull();
        stored.forEach((id, score) -> assertThat(redis.zscore(scoreKey("hint"), id)).as(id).isEqualTo(score));
        assertThat(redis.zscore(scoreKey("hint"), "missing")).isNull();
        assertThat(scoreCore.acquireCurrentHotScoreLeases("other", List.of("occupied"), now + 5_000).get("occupied").status())
                .isEqualTo(WorkerScoreTransitionStatus.STALE);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void identityAndStrictAcquisitionCompeteAtMostOnce(boolean competitorIsStrict) throws Exception {
        long now = redisTimeMillis();
        long held = workerScore(1, (now - 30_000) / SLOT_MILLIS, 0);
        redis.zadd(scoreKey("hint"), held, "w");
        var start = new CountDownLatch(1);
        try (var competing = new RedisWorkerScoreCore(redisClient, keyspace);
             var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return scoreCore.acquireCurrentHotScoreLeases("hint", List.of("w"), now + 60_000).get("w").status();
            });
            var second = executor.submit(() -> {
                start.await();
                return (competitorIsStrict
                        ? competing.acquireObservedHotScoreLeases("hint", Map.of("w", held), now + 60_000)
                        : competing.acquireCurrentHotScoreLeases("hint", List.of("w"), now + 60_000))
                        .get("w").status();
            });
            start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(WorkerScoreTransitionStatus.TRANSITIONED, WorkerScoreTransitionStatus.STALE);
        }
    }

    @Test
    void identityAcquisitionCannotRenewOrShortenOccupiedCoordinates() {
        long now = redisTimeMillis();
        for (int mark : new int[]{0, 1}) {
            long future = workerScore(1, (now + 30_000) / SLOT_MILLIS, mark);
            redis.zadd(scoreKey("hint"), future, "w");
            assertThat(scoreCore.acquireCurrentHotScoreLeases("hint", List.of("w"), now).get("w").status())
                    .isEqualTo(WorkerScoreTransitionStatus.INVALID);
            for (long requested : new long[]{now + 5_000, now + 60_000}) {
                assertThat(scoreCore.acquireCurrentHotScoreLeases("hint", List.of("w"), requested).get("w"))
                        .isEqualTo(new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.STALE, future));
            }
            assertThat(redis.zscore(scoreKey("hint"), "w")).isEqualTo((double) future);
        }
    }

    @Test
    void directAcquisitionNeedsNoPoolAndAcceptsBothDueMarksInOneLua() {
        long now = redisTimeMillis(), target = now + 30_000;
        var ids = List.of("ordinary-due", "candidate-due");
        redis.zadd(scoreKey("direct-due"), workerScore(1, now / SLOT_MILLIS - 10, 0), ids.get(0));
        redis.zadd(scoreKey("direct-due"), workerScore(1, now / SLOT_MILLIS - 10, 1), ids.get(1));
        var commands = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var listener = commandListener(commands); redisClient.addListener(listener);
        Map<String, WorkerScoreTransitionResult> acquired;
        try { acquired = scoreCore.acquireCurrentHotScoreLeases("direct-due", ids, target); }
        finally { redisClient.removeListener(listener); }
        assertThat(commands).containsExactly("EVAL");
        assertThat(acquired.keySet()).containsExactlyElementsOf(ids);
        acquired.values().forEach(result -> assertThat(result).isEqualTo(new WorkerScoreTransitionResult(
                WorkerScoreTransitionStatus.TRANSITIONED, workerScore(1, target / SLOT_MILLIS, 0))));
    }

    @Test
    void directExecutionCompetesWithDueRefillWithoutLeavingAUsablePoolFence() throws Exception {
        long now = redisTimeMillis(), due = workerScore(1, now / SLOT_MILLIS - 10, 0);
        redis.zadd(scoreKey("direct-refill"), due, "w");
        var start = new CountDownLatch(1);
        try (var competing = new RedisWorkerScoreCore(redisClient, keyspace); var executor = Executors.newFixedThreadPool(2)) {
            var direct = executor.submit(() -> {
                start.await(); return scoreCore.acquireCurrentHotScoreLeases("direct-refill", List.of("w"), now + 60_000).get("w");
            });
            var refill = executor.submit(() -> {
                start.await(); return competing.candidateizeObservedHotScores("direct-refill", Map.of("w", due)).get("w");
            });
            start.countDown();
            var execution = direct.get(5, TimeUnit.SECONDS); var held = refill.get(5, TimeUnit.SECONDS);
            assertThat(execution.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
            assertThat(held.status()).isIn(WorkerScoreTransitionStatus.STALE, WorkerScoreTransitionStatus.TRANSITIONED);
            assertThat(redis.zscore(scoreKey("direct-refill"), "w")).isEqualTo((double) execution.score());
            if (held.status() == WorkerScoreTransitionStatus.TRANSITIONED) {
                assertThat(scoreCore.acquireObservedHotScoreLeases("direct-refill", Map.of("w", held.score()), now + 60_000)
                        .get("w").status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
            }
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void executionRejectsCurrentSlotUntilRedisCrossesItsBoundary(boolean hint) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            awaitRedisTime((redisTimeMillis() / SLOT_MILLIS + 1) * SLOT_MILLIS);
            long now = redisTimeMillis();
            long held = workerScore(1, now / SLOT_MILLIS, 0);
            redis.zadd(scoreKey("boundary"), held, "w");
            var result = (hint
                    ? scoreCore.acquireCurrentHotScoreLeases("boundary", List.of("w"), now + 5_000)
                    : scoreCore.acquireObservedHotScoreLeases("boundary", Map.of("w", held), now + 5_000))
                    .get("w");
            if (redisTimeMillis() / SLOT_MILLIS != now / SLOT_MILLIS) continue;
            assertThat(result.status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
            return;
        }
        throw new AssertionError("Could not observe one acquisition rejection within a Redis slot");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void executionAcquisitionsKeepChunkBoundsOrderAndIndependentOutcomes(boolean current) {
        long now = redisTimeMillis();
        long held = workerScore(1, (now - 30_000) / SLOT_MILLIS, 0);
        var expected = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 201; i++) {
            String id = "w" + i;
            redis.zadd(scoreKey("mixed"), held, id);
            expected.put(id, held);
        }
        long rejectedScore = current ? workerScore(1, now / SLOT_MILLIS + 100, 0) : held + 2;
        redis.zadd(scoreKey("mixed"), rejectedScore, "w101");
        var commands = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var listener = commandListener(commands);
        redisClient.addListener(listener);
        Map<String, WorkerScoreTransitionResult> results;
        try {
            results = current
                    ? scoreCore.acquireCurrentHotScoreLeases("mixed", List.copyOf(expected.keySet()), now + 5_000)
                    : scoreCore.acquireObservedHotScoreLeases("mixed", expected, now + 5_000);
        } finally { redisClient.removeListener(listener); }
        assertThat(commands).containsExactly("EVAL", "EVAL", "EVAL");
        assertThat(results.keySet()).containsExactlyElementsOf(expected.keySet());
        assertThat(transitionedScores(results)).hasSize(200).doesNotContainKey("w101");
        assertThat(results.get("w101")).isEqualTo(new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.STALE, rejectedScore));
        assertThat(results.get("w200").score()).isEqualTo(workerScore(1, (now + 5000) / 100, 0));
    }

    private Map<String, WorkerScoreState> readCoordinates(String group, List<String> ids) {
        var states = new LinkedHashMap<String, WorkerScoreState>();
        WorkerScoreRedisFixture.readScores(redis, keyspace, group, ids)
                .forEach((id, score) -> states.put(id, score == null ? null : decodeState(id, score)));
        return states;
    }

    @Test
    void concurrentDifferentGroupRegistrationsChooseOneImmutableDeclaration()
            throws Exception {
        WorkerGroupDescriptor first = group(
                "group-race",
                Map.of("candidate", "first"),
                Set.of("event.first")
        );
        WorkerGroupDescriptor second = group(
                "group-race",
                Map.of("candidate", "second"),
                Set.of("event.second")
        );
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try (var competing = new RedisWorkerResourceCatalog(
                redisClient,
                scoreCore,
                keyspace
        )) {
            var firstResult = executor.submit(() -> {
                start.await();
                return catalog.registerWorkerGroup(first);
            });
            var secondResult = executor.submit(() -> {
                start.await();
                return competing.registerWorkerGroup(second);
            });
            start.countDown();

            assertThat(List.of(
                    firstResult.get().status(),
                    secondResult.get().status()
            )).containsExactlyInAnyOrder(
                    RegistrationStatus.OK,
                    RegistrationStatus.CONFLICT
            );
            WorkerGroupDescriptor stored = catalog
                    .getWorkerGroupDescriptors(List.of("group-race"))
                    .get("group-race");
            assertThat(stored).isIn(first, second);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void groupRegistrationRejectsCorruptStoredIdentityWithoutMutation() {
        String stored = "{\"attributes\":{},\"eventCodes\":[],"
                + "\"workerGroupId\":\"different\"}";
        redis.hset(groupsKey(), "group-1", stored);

        assertThat(catalog.registerWorkerGroup(group(
                "group-1",
                Map.of(),
                Set.of()
        )).status()).isEqualTo(RegistrationStatus.INVALID);
        assertThat(redis.hget(groupsKey(), "group-1")).isEqualTo(stored);
    }

    @Test
    void samplesWorkerGroupsWithOneBoundedHashOperationSemantics() {
        for (int index = 0; index < 120; index++) {
            assertThat(catalog.registerWorkerGroup(group(
                    "group-%03d".formatted(index),
                    Map.of("index", index),
                    Set.of("event")
            )).status()).isEqualTo(RegistrationStatus.OK);
        }

        assertThat(catalog.sampleWorkerGroupDescriptors(1)).hasSize(1);
        Map<String, WorkerGroupDescriptor> sampled =
                catalog.sampleWorkerGroupDescriptors(100);
        assertThat(sampled).hasSize(100);
        assertThat(sampled.values()).allSatisfy(descriptor ->
                assertThat(descriptor).isNotNull());

        redis.del(groupsKey());
        redis.hset(groupsKey(), Map.of(
                "broken", "{not-json",
                "mismatched", "{\"attributes\":{},"
                        + "\"eventCodes\":[],"
                        + "\"workerGroupId\":\"different\"}"
        ));
        assertThat(catalog.sampleWorkerGroupDescriptors(100))
                .containsOnlyKeys("broken", "mismatched")
                .allSatisfy((workerGroupId, descriptor) ->
                        assertThat(descriptor).isNull());

        assertThatThrownBy(() -> catalog.sampleWorkerGroupDescriptors(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.sampleWorkerGroupDescriptors(101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registersOneHundredColdMembersAndPreservesExistingBindings() {
        var group = group("group-1", Map.of("kind", "initial"), Set.of("event"));
        assertThat(catalog.registerWorkerGroup(group).status()).isEqualTo(RegistrationStatus.OK);
        assertThat(catalog.registerWorkerGroup(group).status()).isEqualTo(RegistrationStatus.NOOP);
        var ids = java.util.stream.IntStream.range(0, 100).mapToObj(i -> "worker-" + i).toList();
        var first = catalog.registerWorkers("group-1", ids, "endpoint-1");
        assertThat(first.keySet()).containsExactlyElementsOf(ids);
        assertThat(first.values()).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(RegistrationStatus.OK);
            assertThat(result.endpointManagerId()).isEqualTo("endpoint-1");
        });
        assertThat(redis.hget(bindingsKey(), "worker-0"))
                .isEqualTo("{\"endpointManagerId\":\"endpoint-1\",\"workerGroupId\":\"group-1\"}");
        assertThat(redis.zmscore(scoreKey("group-1"), ids.toArray(String[]::new)))
                .containsOnly(-1.0);
        assertThat(catalog.registerWorkers("group-1", ids, "new-default").values()).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(RegistrationStatus.NOOP);
            assertThat(result.endpointManagerId()).isEqualTo("endpoint-1");
        });
        assertThat(scoreCore.observeDueHotScoreCandidates("group-1", null, 100)).isEmpty();
        assertThat(scoreCore.observeHotCandidatesBefore("group-1", redisTimeMillis(), 100)).isEmpty();
        assertThat(scoreCore.observeRecoveryRecheckCandidates("group-1", 100)).isEmpty();
    }

    @Test
    void missingGroupCrossGroupAndCorruptBindingDoNotWriteScores() {
        assertThat(catalog.registerWorkers("missing", List.of("w"), "endpoint").get("w").status())
                .isEqualTo(RegistrationStatus.NOT_FOUND);
        assertThat(redis.hget(bindingsKey(), "w")).isNull();
        catalog.registerWorkerGroup(group("g1", Map.of(), Set.of()));
        catalog.registerWorkerGroup(group("g2", Map.of(), Set.of()));
        catalog.registerWorkers("g1", List.of("w"), "endpoint");
        assertThat(catalog.registerWorkers("g2", List.of("w"), "endpoint").get("w").status())
                .isEqualTo(RegistrationStatus.CONFLICT);
        assertThat(redis.zscore(scoreKey("g2"), "w")).isNull();
        List<String> corrupt = List.of("not-json", "[]", "null", "{}",
                "{\"workerGroupId\":\"g1\",\"endpointManagerId\":\"\"}",
                "{\"workerGroupId\":\"g1\",\"endpointManagerId\":4}",
                "{\"workerGroupId\":\"g1\",\"endpointManagerId\":\"e\",\"workerId\":\"bad\"}",
                "{\"workerGroupId\":\"g1\",\"endpointManagerId\":\"e\",\"properties\":{}}");
        for (String stored : corrupt) {
            redis.hset(bindingsKey(), "bad", stored);
            var results = catalog.registerWorkers("g1", List.of("bad", "accepted"), "endpoint");
            assertThat(results.get("bad").status()).isEqualTo(RegistrationStatus.INVALID);
            assertThat(results.get("accepted").status()).isIn(RegistrationStatus.OK, RegistrationStatus.NOOP);
            assertThat(redis.hget(bindingsKey(), "bad")).isEqualTo(stored);
            assertThat(redis.zscore(scoreKey("g1"), "bad")).isNull();
            assertThat(catalog.getWorkerDescriptors(List.of("bad"))).containsEntry("bad", null);
        }
    }

    @Test
    void concurrentDifferentDefaultsReturnTheSameWinner() throws Exception {
        catalog.registerWorkerGroup(group("g", Map.of(), Set.of()));
        CountDownLatch start = new CountDownLatch(1);
        try (var competing = new RedisWorkerResourceCatalog(redisClient, scoreCore, keyspace);
             var executor = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 32; i++) {
                String endpoint = "endpoint-" + i % 2;
                var owner = i % 2 == 0 ? catalog : competing;
                futures.add(executor.submit(() -> {
                    start.await();
                    var result = owner.registerWorkers("g", List.of("w"), endpoint).get("w");
                    assertThat(result.status()).isIn(RegistrationStatus.OK, RegistrationStatus.NOOP);
                    return result.endpointManagerId();
                }));
            }
            start.countDown();
            String actual = futures.getFirst().get(10, TimeUnit.SECONDS);
            for (var future : futures) assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(actual);
            assertThat(catalog.getWorkerDescriptors(List.of("w")))
                    .containsEntry("w", worker("w", "g", actual));
            assertThat(redis.zcard(scoreKey("g"))).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void unknownScoreCompletionLeavesBindingAndRetryFillsOnlyMissingMembers(boolean committed) {
        catalog.registerWorkerGroup(group("g", Map.of(), Set.of()));
        WorkerScoreCore failing = mock(WorkerScoreCore.class);
        var failure = new IllegalStateException("unknown Score completion");
        when(failing.initializeRegisteredScores("g", List.of("w"))).thenAnswer(call -> {
            if (committed) scoreCore.initializeRegisteredScores("g", List.of("w"));
            throw failure;
        });
        try (var resources = new RedisWorkerResourceCatalog(redisClient, failing, keyspace)) {
            assertThatThrownBy(() -> resources.registerWorkers("g", List.of("w"), "endpoint"))
                    .isSameAs(failure);
        }
        assertThat(catalog.getWorkerDescriptors(List.of("w"))).containsEntry("w", worker("w", "g", "endpoint"));
        assertThat(redis.zscore(scoreKey("g"), "w")).isEqualTo(committed ? -1.0 : null);
        var retry = catalog.registerWorkers("g", List.of("w"), "different-default").get("w");
        assertThat(retry.status()).isEqualTo(committed ? RegistrationStatus.NOOP : RegistrationStatus.OK);
        assertThat(retry.endpointManagerId()).isEqualTo("endpoint");
        assertThat(redis.zscore(scoreKey("g"), "w")).isEqualTo(-1.0);
        redis.hdel(bindingsKey(), "w");
        assertThat(catalog.registerWorkers("g", List.of("w"), "endpoint").get("w").status())
                .isEqualTo(RegistrationStatus.OK);
        assertThat(redis.zscore(scoreKey("g"), "w")).isEqualTo(-1.0);
    }

    @Test
    void registrationNxPreservesEveryScoreShapeIncludingInvalidAndPaused() {
        catalog.registerWorkerGroup(group("g", Map.of(), Set.of()));
        long now = redisTimeMillis() / SLOT_MILLIS;
        long[] shapes = {0, -200, -201, 1, -1,
                workerScore(1, now, 0), workerScore(1, now, 1),
                workerScore(-1, now, 0), workerScore(-1, now, 1),
                workerScore(1, now + 600, 1), workerScore(-1, now + 600, 1),
                workerScore(1, PAUSE_TIME_SLOT, 0),
                workerScore(-1, PAUSE_TIME_SLOT, 1)};
        for (long shape : shapes) {
            redis.zadd(scoreKey("g"), shape, "w");
            catalog.registerWorkers("g", List.of("w"), "endpoint");
            assertThat(redis.zscore(scoreKey("g"), "w")).isEqualTo((double) shape);
        }
    }

    @Test
    void bindingReadsAreBoundedAndIndependentOfScoreWhileSamplesUseRegisteredMembers() {
        var unused = mock(WorkerScoreCore.class);
        redis.hset(bindingsKey(), Map.of("w", bindingJson("g", "endpoint"),
                "address-only", bindingJson("g", "endpoint"), "wrong-group", bindingJson("other", "endpoint")));
        try (var resources = new RedisWorkerResourceCatalog(redisClient, unused, keyspace)) {
            assertThat(resources.getWorkerDescriptors(List.of("w", "missing")))
                    .containsEntry("w", worker("w", "g", "endpoint")).containsEntry("missing", null);
            assertThat(resources.getWorkerDescriptorsAsync(List.of("w")).join())
                    .containsEntry("w", worker("w", "g", "endpoint"));
            verifyNoInteractions(unused);
        }
        scoreCore.initializeRegisteredScores("g", List.of("w", "missing", "wrong-group"));
        assertThat(catalog.sampleWorkerDescriptors("g", 100))
                .containsOnlyKeys("w", "missing", "wrong-group")
                .containsEntry("w", worker("w", "g", "endpoint"))
                .containsEntry("missing", null).containsEntry("wrong-group", null);
        var tooMany = java.util.stream.IntStream.range(0, 101).mapToObj(i -> "w" + i).toList();
        assertThat(catalog.getWorkerDescriptors(tooMany)).hasSize(101);
        assertThat(catalog.getWorkerDescriptorsAsync(tooMany).join()).hasSize(101);
        assertThatThrownBy(() -> catalog.registerWorkers("g", List.of("w", "w"), "e")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.registerWorkers("g", tooMany, "e")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.registerWorkers("g", List.of(), "e")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void obsoleteStorageCannotSupplyBindingsOrRegisteredMembers() {
        redis.hset(keyspace.base() + ":worker:id_owners", "old", "g");
        redis.hset(keyspace.base() + ":worker:metadata:g", "old", bindingJson("g", "e"));
        redis.hset(keyspace.base() + ":worker:binding:57", "old", "e");
        assertThat(catalog.getWorkerDescriptors(List.of("old"))).containsEntry("old", null);
        assertThat(catalog.sampleWorkerDescriptors("g", 100)).isEmpty();
    }

    @Test
    void workerPreviewSamplesOneThousandWithoutExpandingBindingBatchReads() {
        catalog.registerWorkerGroup(group("preview-group", Map.of(), Set.of()));
        List<String> ids = java.util.stream.IntStream.range(0, 1001)
                .mapToObj(i -> "preview-worker-" + i).toList();
        for (int offset = 0; offset < ids.size(); offset += 100) {
            catalog.registerWorkers("preview-group",
                    ids.subList(offset, Math.min(offset + 100, ids.size())), "endpoint");
        }
        var sampled = catalog.sampleWorkerDescriptors("preview-group", 1000);
        assertThat(sampled).hasSize(1000);
        assertThat(ids).containsAll(sampled.keySet());
        sampled.forEach((id, descriptor) ->
                assertThat(descriptor).isEqualTo(worker(id, "preview-group", "endpoint")));
        assertThat(catalog.sampleWorkerDescriptors("preview-group", 1)).hasSize(1);
        assertThat(catalog.getWorkerDescriptors(ids.subList(0, 1000))).hasSize(1000);
        for (int limit : List.of(0, 1001)) {
            assertThatThrownBy(() -> catalog.sampleWorkerDescriptors("preview-group", limit))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> scoreCore.sampleRegisteredWorkerIds("preview-group", limit))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"adapter", "system-polling"})
    void networkMechanismRequiresBindingAndPreservesLeaseMarkedAndPause(String endpoint) {
        catalog.registerWorkerGroup(group("g", Map.of(), Set.of()));
        catalog.registerWorkers("g", List.of("cold", "lease", "pause", "binding-only"), endpoint);
        redis.zrem(scoreKey("g"), "binding-only");
        long now = redisTimeMillis() / SLOT_MILLIS;
        long lease = workerScore(-1, now + 600, 1);
        long pause = workerScore(-1, PAUSE_TIME_SLOT, 1);
        redis.zadd(scoreKey("g"), lease, "lease");
        redis.zadd(scoreKey("g"), pause, "pause");
        var events = new com.xa.mass.kernel.worker.DefaultWorkerServiceabilityEvents(catalog, scoreCore, System.currentTimeMillis());
        var wrong = new com.xa.mass.kernel.worker.WorkerServiceabilityEvents.NetworkObservation("wrong", now * 100);
        events.onAvailable(Map.of("cold", wrong, "lease", wrong, "pause", wrong));
        assertThat(redis.zscore(scoreKey("g"), "cold")).isEqualTo(-1.0);
        assertThat(redis.zscore(scoreKey("g"), "lease")).isEqualTo((double) lease);

        // No retained first observation exists. A later actual observation alone activates.
        var valid = new com.xa.mass.kernel.worker.WorkerServiceabilityEvents.NetworkObservation(endpoint, now * 100);
        events.onAvailable(Map.of("cold", valid, "lease", valid, "pause", valid,
                "binding-only", valid, "missing", valid));
        assertThat(redis.zscore(scoreKey("g"), "cold")).isEqualTo((double) workerScore(1, now, 0));
        assertThat(redis.zscore(scoreKey("g"), "lease")).isEqualTo((double) -lease);
        assertThat(redis.zscore(scoreKey("g"), "pause")).isEqualTo((double) -pause);
        assertThat(redis.zscore(scoreKey("g"), "binding-only")).isNull();
        assertThat(redis.zscore(scoreKey("g"), "missing")).isNull();
    }

    @Test
    void schedulingObservationOwnsAllClassificationAndOneSharedLocalTime() {
        var expected = new LinkedHashMap<String, SchedulingState>();
        expected.put("missing", SchedulingState.MISSING);
        for (int sign : new int[]{1, -1}) {
            for (int mark : new int[]{0, 1}) {
                for (long slot : new long[]{1, 2, 149, 150, 151, PAUSE_TIME_SLOT}) {
                    String id = sign + ":" + mark + ":" + slot;
                    redis.zadd(scoreKey("observe"), workerScore(sign, slot, mark), id);
                    expected.put(id, slot == PAUSE_TIME_SLOT ? SchedulingState.PAUSED
                            : sign < 0 ? (slot == 1 ? SchedulingState.COLD : SchedulingState.RECOVERY)
                            : slot >= 150 ? SchedulingState.HELD_HOT : SchedulingState.HOT_SCORE_OVERDUE);
                }
            }
        }
        // Slot zero has only a mark, non-zero representation and keeps the existing cold boundary.
        redis.zadd(scoreKey("observe"), -1, "cold-zero");
        expected.put("cold-zero", SchedulingState.COLD);
        var commands = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var clockReads = new java.util.concurrent.atomic.AtomicInteger();
        try (var owner = new RedisWorkerScoreCore(redisClient, keyspace, () -> {
            assertThat(commands).containsExactly("ZMSCORE");
            clockReads.incrementAndGet();
            return 15_055;
        })) {
            var listener = commandListener(commands);
            redisClient.addListener(listener);
            WorkerScoreCore.WorkerSchedulingObservation observation;
            try {
                owner.pauseScheduling("warm", "missing");
                commands.clear();
                observation = owner.observeSchedulingStates("observe", List.copyOf(expected.keySet()));
            } finally { redisClient.removeListener(listener); }
            assertThat(clockReads).hasValue(1);
            assertThat(observation.readAtMillis()).isEqualTo(15_055);
            assertThat(observation.statesByWorkerId()).containsExactlyEntriesOf(expected);
            assertThatThrownBy(() -> observation.statesByWorkerId().clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThat(commands).containsExactly("ZMSCORE");
        }
    }

    @Test
    void pauseAndResumePreserveFieldsAndCommandBudgets() {
        long releaseTime = redisTimeMillis() + 5_000;
        var commands = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var listener = commandListener(commands);
        redisClient.addListener(listener);
        try (var owner = new RedisWorkerScoreCore(redisClient, keyspace, () -> releaseTime)) {
            owner.pauseScheduling("warm", "missing");
            assertThat(commandsDuring(commands, () -> assertThat(owner.resumeScheduling("control", "missing"))
                    .isEqualTo(WorkerSchedulingChangeStatus.MISSING))).containsExactly("ZMSCORE");
            assertThat(commandsDuring(commands, () -> assertThat(owner.pauseScheduling("control", "missing"))
                    .isEqualTo(WorkerSchedulingChangeStatus.MISSING))).containsExactly("EVAL");
            for (int sign : new int[]{1, -1}) {
                for (int mark : new int[]{0, 1}) {
                    long original = workerScore(sign, 123, mark);
                    redis.zadd(scoreKey("control"), original, "w");
                    assertThat(commandsDuring(commands, () -> assertThat(owner.resumeScheduling("control", "w"))
                            .isEqualTo(WorkerSchedulingChangeStatus.UNCHANGED))).containsExactly("ZMSCORE");
                    assertThat(commandsDuring(commands, () -> assertThat(owner.pauseScheduling("control", "w"))
                            .isEqualTo(WorkerSchedulingChangeStatus.APPLIED))).containsExactly("EVAL");
                    assertThat(redis.zscore(scoreKey("control"), "w"))
                            .isEqualTo((double) workerScore(sign, PAUSE_TIME_SLOT, 0));
                    assertThat(commandsDuring(commands, () -> assertThat(owner.pauseScheduling("control", "w"))
                            .isEqualTo(WorkerSchedulingChangeStatus.UNCHANGED))).containsExactly("EVAL");
                    assertThat(commandsDuring(commands, () -> assertThat(owner.resumeScheduling("control", "w"))
                            .isEqualTo(WorkerSchedulingChangeStatus.APPLIED))).containsExactly("ZMSCORE", "TIME", "EVAL");
                    assertThat(redis.zscore(scoreKey("control"), "w"))
                            .isEqualTo((double) workerScore(sign, releaseTime / SLOT_MILLIS, 0));
                }
            }
        } finally { redisClient.removeListener(listener); }
    }

    @Test
    void resumeRejectsDeletionOrAnyFenceChangeAfterReadingPauseWithoutRetry() {
        long paused = workerScore(1, PAUSE_TIME_SLOT, 0);
        long releaseTime = redisTimeMillis() + 5_000;
        for (Long replacement : java.util.Arrays.asList(null, paused + 1, -paused, paused - 2)) {
            redis.zadd(scoreKey("resume-race"), paused, "w");
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            try (var owner = new RedisWorkerScoreCore(redisClient, keyspace, () -> {
                reads.incrementAndGet();
                // The clock is requested after the exact paused observation was read and decoded.
                if (replacement == null) redis.zrem(scoreKey("resume-race"), "w");
                else redis.zadd(scoreKey("resume-race"), replacement, "w");
                return releaseTime;
            })) {
                assertThat(owner.resumeScheduling("resume-race", "w")).isEqualTo(WorkerSchedulingChangeStatus.CONFLICT);
                assertThat(reads).hasValue(1);
                assertThat(redis.zscore(scoreKey("resume-race"), "w"))
                        .isEqualTo(replacement == null ? null : replacement.doubleValue());
            }
        }
    }

    @Test
    void schedulingControlsKeepExistingCorruptScoreBehavior() {
        redis.zadd(scoreKey("corrupt-control"), 0, "zero");
        redis.zadd(scoreKey("corrupt-control"), 2.5, "fraction");
        redis.zadd(scoreKey("corrupt-control"), absoluteScore(PAUSE_TIME_SLOT + 1, 1), "above-pause");
        for (String id : List.of("zero", "fraction", "above-pause")) {
            assertThatThrownBy(() -> scoreCore.observeSchedulingStates("corrupt-control", List.of(id)))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> scoreCore.resumeScheduling("corrupt-control", id))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThat(scoreCore.pauseScheduling("corrupt-control", "zero")).isEqualTo(WorkerSchedulingChangeStatus.CONFLICT);
        assertThat(scoreCore.pauseScheduling("corrupt-control", "above-pause")).isEqualTo(WorkerSchedulingChangeStatus.UNCHANGED);
        assertThat(redis.zscore(scoreKey("corrupt-control"), "fraction")).isEqualTo(2.5);
    }

    @Test
    void nonAlignedRangeInputsHaveTheSameHeadAndPreserveTieOrder() {
        long now = redisTimeMillis();
        long cutoff = (now / 100 - 10) * 100;
        for (String id : List.of("a", "b", "c")) redis.zadd(scoreKey("unaligned"), cutoff / 100 - 1, id);
        var expected = scoreCore.observeHotCandidatesBefore("unaligned", cutoff, 100);
        assertThat(expected.keySet()).containsExactly("c", "b", "a");
        for (long offset : new long[]{1, 55, 99}) {
            assertThat(scoreCore.observeHotCandidatesBefore("unaligned", cutoff + offset, 100)).isEqualTo(expected);
            assertThat(scoreCore.observeDueHotScoreCandidates("unaligned", cutoff - 100 + offset, 100))
                    .containsExactlyEntriesOf(scoreCore.observeDueHotScoreCandidates("unaligned", cutoff - 100, 100));
        }
        assertThatThrownBy(() -> expected.put("extra", 1L)).isInstanceOf(UnsupportedOperationException.class);
        for (String id : List.of("a", "b", "c")) redis.zadd(scoreKey("unaligned"), -cutoff / 100, id);
        var recovery = scoreCore.observeRecoveryRecheckCandidates("unaligned", 100);
        assertThat(recovery.keySet()).containsExactly("c", "b", "a");
        assertThatThrownBy(() -> recovery.clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private io.lettuce.core.event.command.CommandListener commandListener(List<String> commands) {
        return new io.lettuce.core.event.command.CommandListener() {
            @Override public void commandStarted(io.lettuce.core.event.command.CommandStartedEvent event) {
                commands.add(event.getCommand().getType().toString());
            }
        };
    }

    private List<String> commandsDuring(List<String> commands, Runnable action) {
        commands.clear();
        action.run();
        return List.copyOf(commands);
    }

    @Test
    void workerScorePauseAndReleasePreserveOwnerShape() {
        long currentSlot = redisTimeMillis() / SLOT_MILLIS;
        long hotScore = workerScore(
                1,
                currentSlot,
                1
        );
        long recoveryScore = workerScore(
                -1,
                currentSlot,
                0
        );
        redis.zadd(scoreKey("group-1"), hotScore, "hot-worker");
        redis.zadd(
                scoreKey("group-1"),
                recoveryScore,
                "recovery-worker"
        );

        assertThat(scoreCore.pauseScheduling("group-1", "hot-worker"))
                .isEqualTo(WorkerScoreCore.WorkerSchedulingChangeStatus.APPLIED);
        assertThat(scoreCore.pauseScheduling("group-1", "recovery-worker"))
                .isEqualTo(WorkerScoreCore.WorkerSchedulingChangeStatus.APPLIED);
        assertThat(scoreCore.pauseScheduling("group-1", "missing-worker"))
                .isEqualTo(WorkerScoreCore.WorkerSchedulingChangeStatus.MISSING);

        Map<String, WorkerScoreState> pausedStates = readCoordinates(
                "group-1",
                List.of("hot-worker", "recovery-worker")
        );
        assertScoreShape(
                pausedStates.get("hot-worker"),
                WorkerScorePolarity.HOT_ACQUIRE,
                PAUSE_TIME_MILLIS,
                0
        );
        assertScoreShape(
                pausedStates.get("recovery-worker"),
                WorkerScorePolarity.RECOVERY_RECHECK,
                PAUSE_TIME_MILLIS,
                0
        );

        for (String id : List.of("hot-worker", "recovery-worker")) {
            assertThat(scoreCore.pauseScheduling("group-1", id))
                    .isEqualTo(WorkerScoreCore.WorkerSchedulingChangeStatus.UNCHANGED);
        }

        long releaseTimeMillis = redisTimeMillis()
                + SLOT_MILLIS;
        var released = scoreCore.releaseScoreHolds(
                "group-1",
                Map.of(
                        "hot-worker",
                        pausedStates.get("hot-worker").score(),
                        "recovery-worker",
                        pausedStates.get("recovery-worker").score()
                ),
                releaseTimeMillis
        );
        assertThat(released.values()).allSatisfy(result ->
                assertThat(result.status()).isEqualTo(
                        WorkerScoreTransitionStatus.TRANSITIONED
                ));

        long releasedTimeMillis = releaseTimeMillis
                / SLOT_MILLIS
                * SLOT_MILLIS;
        Map<String, WorkerScoreState> releasedStates = readCoordinates(
                "group-1",
                List.of("hot-worker", "recovery-worker")
        );
        assertScoreShape(
                releasedStates.get("hot-worker"),
                WorkerScorePolarity.HOT_ACQUIRE,
                releasedTimeMillis,
                0
        );
        assertScoreShape(
                releasedStates.get("recovery-worker"),
                WorkerScorePolarity.RECOVERY_RECHECK,
                releasedTimeMillis,
                0
        );
    }

    @Test
    void workerScoreReleaseUsesExactCasAndKeepsBatchResultsIndependent() {
        long firstObserved = workerScore(
                1,
                PAUSE_TIME_SLOT,
                0
        );
        long secondObserved = workerScore(
                -1,
                PAUSE_TIME_SLOT,
                0
        );
        long secondCurrent = workerScore(
                -1,
                PAUSE_TIME_SLOT,
                1
        );
        redis.zadd(scoreKey("group-1"), firstObserved, "first-worker");
        redis.zadd(scoreKey("group-1"), secondCurrent, "second-worker");
        long ordinaryObserved = workerScore(
                1,
                redisTimeMillis() / SLOT_MILLIS,
                0
        );
        long releaseTimeMillis = redisTimeMillis()
                + SLOT_MILLIS;
        LinkedHashMap<String, Long> observations = new LinkedHashMap<>();
        observations.put("first-worker", firstObserved);
        observations.put("second-worker", secondObserved);
        observations.put("missing-worker", firstObserved);
        observations.put("ordinary-worker", ordinaryObserved);

        var results = scoreCore.releaseScoreHolds(
                "group-1",
                observations,
                releaseTimeMillis
        );

        assertThat(results.keySet()).containsExactlyElementsOf(
                observations.keySet()
        );
        assertThat(results.get("first-worker").status())
                .isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(results.get("second-worker").status())
                .isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(results.get("second-worker").score())
                .isEqualTo(secondCurrent);
        assertThat(results.get("missing-worker").status())
                .isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(results.get("missing-worker").score()).isNull();
        assertThat(results.get("ordinary-worker").status())
                .isEqualTo(WorkerScoreTransitionStatus.INVALID);
        assertThat(redis.zscore(scoreKey("group-1"), "second-worker"))
                .isEqualTo((double) secondCurrent);

        var staleRetry = scoreCore.releaseScoreHolds(
                "group-1",
                Map.of("first-worker", firstObserved),
                releaseTimeMillis + SLOT_MILLIS
        );
        assertThat(staleRetry.get("first-worker").status())
                .isEqualTo(WorkerScoreTransitionStatus.STALE);

        long currentFirst = readCoordinates(
                "group-1",
                List.of("first-worker")
        ).get("first-worker").score();
        assertThat(scoreCore.releaseScoreHolds(
                "group-1",
                Map.of("first-worker", currentFirst),
                redisTimeMillis() - SLOT_MILLIS
        ).get("first-worker").status()).isEqualTo(
                WorkerScoreTransitionStatus.INVALID
        );
        assertThat(scoreCore.releaseScoreHolds(
                "group-1",
                Map.of(
                        "pause-base-worker",
                        workerScore(
                                1,
                                PAUSE_TIME_SLOT,
                                ORDINARY_MARK
                        )
                ),
                PAUSE_TIME_MILLIS
        ).get("pause-base-worker").status()).isEqualTo(
                WorkerScoreTransitionStatus.INVALID
        );
    }

    @Test
    void completedHotReleaseRepairsOnlyTheExactRecoveryCounterpart() {
        long leaseSlot = redisTimeMillis()
                / SLOT_MILLIS
                + 100;
        long observedHot = workerScore(
                1,
                leaseSlot,
                1
        );
        long recoveryCounterpart = -observedHot;
        redis.zadd(scoreKey("group-1"), observedHot, "positive-worker");
        redis.zadd(
                scoreKey("group-1"),
                recoveryCounterpart,
                "recovery-worker"
        );
        redis.zadd(
                scoreKey("group-1"),
                recoveryCounterpart - 1,
                "drifted-worker"
        );

        long releaseTime = redisTimeMillis()
                + SLOT_MILLIS;
        var results = scoreCore.releaseObservedHotScoreHolds(
                "group-1",
                Map.of(
                        "positive-worker", observedHot,
                        "recovery-worker", observedHot,
                        "drifted-worker", observedHot
                ),
                releaseTime
        );

        assertThat(results.get("positive-worker").status()).isEqualTo(
                WorkerScoreTransitionStatus.TRANSITIONED
        );
        assertThat(results.get("recovery-worker").status()).isEqualTo(
                WorkerScoreTransitionStatus.TRANSITIONED
        );
        assertThat(results.get("drifted-worker").status()).isEqualTo(
                WorkerScoreTransitionStatus.STALE
        );
        Map<String, WorkerScoreState> states = readCoordinates(
                "group-1",
                List.of("positive-worker", "recovery-worker")
        );
        assertThat(states.get("positive-worker").polarity()).isEqualTo(
                WorkerScorePolarity.HOT_ACQUIRE
        );

        assertThat(states.get("recovery-worker").polarity()).isEqualTo(
                WorkerScorePolarity.HOT_ACQUIRE
        );

        assertThat(states.get("recovery-worker").mark()).isEqualTo(1);
    }



    @Test
    void readOnlyHeadRetainsScoreAndMemberOrderWithinOneCommand() {
        long due = workerScore(1, redisTimeMillis() / 100 - 100, 0);
        var ids = java.util.stream.IntStream.range(0, 250)
                .mapToObj(i -> "worker-%03d".formatted(i)).toList();
        ids.forEach(id -> redis.zadd(scoreKey("g"), due, id));
        var before = redis.zrangeWithScores(scoreKey("g"), 0, -1);
        var commands = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var listener = new io.lettuce.core.event.command.CommandListener() {
            @Override public void commandStarted(io.lettuce.core.event.command.CommandStartedEvent event) {
                commands.add(event.getCommand().getType().toString());
            }
        };
        redisClient.addListener(listener);
        try {
            scoreCore.observeDueHotScoreCandidates("g", null, 100); // Warm the observed Owner connection.
            for (int round = 0; round < 3; round++) {
                commands.clear();
                var observed = scoreCore.observeDueHotScoreCandidates("g", null, 100);
                assertThat(commands).containsExactly("EVAL");
                assertThat(observed.keySet()).containsExactlyElementsOf(ids.subList(0, 100));
                assertThat(observed.values()).containsOnly(due);
                assertThatThrownBy(observed::clear).isInstanceOf(UnsupportedOperationException.class);
            }
            assertThat(redis.zrangeWithScores(scoreKey("g"), 0, -1)).isEqualTo(before);
        } finally { redisClient.removeListener(listener); }
    }

    @Test
    void observationFiltersCorruptRowsWithinTheRawLimitWithoutReplacementReads() {
        long slot = redisTimeMillis() / 100;
        long eligible = workerScore(1, slot - 10, 0);
        redis.zadd(scoreKey("g"), eligible, "ordinary");
        redis.zadd(scoreKey("g"), eligible + 0.25, "corrupt");
        redis.zadd(scoreKey("g"), eligible + 1, "next-ordinary");
        assertThat(scoreCore.observeDueHotScoreCandidates("g", null, 2))
                .containsExactly(Map.entry("ordinary", eligible));
        assertThat(scoreCore.observeDueHotScoreCandidates("g", null, 3))
                .containsExactly(Map.entry("ordinary", eligible), Map.entry("next-ordinary", eligible + 1));
        assertThat(redis.zscore(scoreKey("g"), "corrupt")).isEqualTo(eligible + 0.25);

        for (int index = 0; index < 100; index++) {
            redis.zadd(scoreKey("bad-head"), eligible + 0.25, "corrupt-" + index);
        }
        redis.zadd(scoreKey("bad-head"), eligible + 1, "valid-tail");
        for (int round = 0; round < 2; round++) {
            assertThat(scoreCore.observeDueHotScoreCandidates("bad-head", null, 100)).isEmpty();
        }
        assertThat(redis.zcard(scoreKey("bad-head"))).isEqualTo(101);
        assertThat(redis.zscore(scoreKey("bad-head"), "valid-tail")).isEqualTo((double) eligible + 1);
        assertThat(scoreCore.observeDueHotScoreCandidates("absent", null, 100)).isEmpty();
        for (int limit : new int[]{0, -1}) {
            assertThatThrownBy(() -> scoreCore.observeDueHotScoreCandidates("g", null, limit))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(scoreCore.observeDueHotScoreCandidates("g", -1L, 100)).isEmpty();
        assertThat(scoreCore.observeDueHotScoreCandidates("g", MAX_TIME_MILLIS + 1, 100)).isEmpty();
    }

    @Test
    void observationHonorsFloorAndExcludesTheCurrentSlotFuturePauseAndRecovery() {
        scoreCore.observeDueHotScoreCandidates("g", null, 100);
        for (int attempt = 0; attempt < 8; attempt++) {
            long slot = redisTimeMillis() / 100;
            long eligible = workerScore(1, slot - 10, 0);
            redis.zadd(scoreKey("g"), eligible, "ordinary");
            redis.zadd(scoreKey("g"), eligible + 1, "next-ordinary");
            redis.zadd(scoreKey("g"), workerScore(1, slot - 20, 0), "below-floor");
            redis.zadd(scoreKey("g"), -eligible, "recovery");
            redis.zadd(scoreKey("g"), workerScore(1, slot, 0), "current");
            redis.zadd(scoreKey("g"), workerScore(1, slot + 100, 0), "occupied");
            redis.zadd(scoreKey("g"), workerScore(1, PAUSE_TIME_SLOT, 0), "pause");
            var observed = scoreCore.observeDueHotScoreCandidates("g", (slot - 10) * 100, 100);
            if (redisTimeMillis() / 100 != slot) continue; // Resample only a missed time window.
            assertThat(observed).containsExactly(Map.entry("ordinary", eligible), Map.entry("next-ordinary", eligible + 1));
            assertThat(scoreCore.observeDueHotScoreCandidates("g", (slot + 1000) * 100, 100)).isEmpty();
            return;
        }
        throw new AssertionError("Could not observe the current Redis slot within eight attempts");
    }

    @Test
    void acquiredWorkersLeaveTheNextObservationRangeWithoutRelease() {
        String groupId = "group-match-hold";
        long dueSlot = redisTimeMillis() / SLOT_MILLIS - 20;
        long dueScore = workerScore(
                1,
                dueSlot,
                ORDINARY_MARK
        );
        List<String> workerIds = java.util.stream.IntStream.range(0, 250)
                .mapToObj(index -> "worker-%03d".formatted(index))
                .toList();
        workerIds.forEach(workerId ->
                redis.zadd(scoreKey(groupId), dueScore, workerId));
        long holdUntilMillis = (
                redisTimeMillis() / SLOT_MILLIS + 100
        ) * SLOT_MILLIS;

        Map<String, Long> first = scoreCore.observeDueHotScoreCandidates(
                groupId,
                null,
                100
        );
        assertThat(first.keySet()).containsExactlyElementsOf(
                workerIds.subList(0, 100)
        );
        assertThat(scoreCore.acquireObservedHotScoreLeases(
                groupId,
                first,
                holdUntilMillis
        ).values()).allSatisfy(result -> assertThat(result.status())
                .isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED));

        Map<String, Long> second = scoreCore.observeDueHotScoreCandidates(
                groupId,
                null,
                100
        );
        assertThat(second.keySet()).containsExactlyElementsOf(
                workerIds.subList(100, 200)
        );
        assertThat(scoreCore.acquireObservedHotScoreLeases(
                groupId,
                second,
                holdUntilMillis
        ).values()).allSatisfy(result -> assertThat(result.status())
                .isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED));

        assertThat(scoreCore.observeDueHotScoreCandidates(
                groupId,
                null,
                100
        ).keySet()).containsExactlyElementsOf(workerIds.subList(200, 250));
    }

    @Test
    void recycledCandidateMovesBehindOlderUncandidateizedWorkers() throws Exception {
        long now = redisTimeMillis();
        long first = workerScore(1, now / 100 - 100, 0);
        long waiting = workerScore(1, now / 100 - 90, 0);
        redis.zadd(scoreKey("g"), first, "first"); redis.zadd(scoreKey("g"), waiting, "waiting");
        long candidate = scoreCore.candidateizeObservedHotScores("g", Map.of("first", first)).get("first").score();
        long recycled = scoreCore.recycleObservedHotCandidates("g", Map.of("first", candidate)).get("first").score();
        assertThat(recycled).isGreaterThan(waiting);
        assertThat(scoreCore.observeDueHotScoreCandidates("g", null, 1)).containsExactly(Map.entry("waiting", waiting));
        assertThat(scoreCore.recycleObservedHotCandidates("g", Map.of("first", candidate)).get("first").status())
                .isEqualTo(WorkerScoreTransitionStatus.STALE);
    }

    @Test
    void serviceabilityHoldsAndEvidenceUseExactBatchFences() {
        long beforeSlot = redisTimeMillis() / SLOT_MILLIS;
        long hot = workerScore(
                1,
                beforeSlot - 20,
                1
        );
        long recovery = workerScore(
                -1,
                beforeSlot - 20,
                1
        );
        long invalidDelay = workerScore(
                -1,
                beforeSlot - 20,
                0
        );
        redis.zadd(scoreKey("group-serviceability"), hot, "hot");
        redis.zadd(
                scoreKey("group-serviceability"),
                recovery,
                "recovery"
        );
        redis.zadd(
                scoreKey("group-serviceability"),
                invalidDelay,
                "invalidDelay"
        );

        var hotResults = scoreCore
                .deferObservedToRecovery("group-serviceability", Map.of(
                                "hot", hot,
                                "stale", hot
                        ), 60_000);
        var recoveryResults = scoreCore.deferObservedToRecovery(
                "group-serviceability",
                Map.of("recovery", recovery), 240_000
        );
        var invalidDelayResults = scoreCore.deferObservedToRecovery(
                "group-serviceability", Map.of("invalidDelay", invalidDelay), 0);

        assertThat(hotResults.get("hot").status()).isEqualTo(
                WorkerScoreTransitionStatus.TRANSITIONED
        );
        assertThat(hotResults.get("stale").status()).isEqualTo(
                WorkerScoreTransitionStatus.STALE
        );
        assertThat(recoveryResults.get("recovery").status()).isEqualTo(
                WorkerScoreTransitionStatus.TRANSITIONED
        );
        assertThat(invalidDelayResults.get("invalidDelay").status()).isEqualTo(
                WorkerScoreTransitionStatus.INVALID
        );

        Map<String, WorkerScoreState> held = readCoordinates(
                "group-serviceability",
                List.of("hot", "recovery", "invalidDelay")
        );
        long afterSlot = redisTimeMillis() / SLOT_MILLIS;
        assertThat(held.get("hot").timeMillis()
                / SLOT_MILLIS).isBetween(
                        beforeSlot + 600,
                        afterSlot + 600
                );
        assertThat(held.get("recovery").timeMillis()
                / SLOT_MILLIS).isBetween(
                        beforeSlot + 2400,
                        afterSlot + 2400
                );
        assertScoreShape(
                held.get("hot"),
                WorkerScorePolarity.RECOVERY_RECHECK,
                held.get("hot").timeMillis(),
                0
        );
        assertScoreShape(
                held.get("recovery"),
                WorkerScorePolarity.RECOVERY_RECHECK,
                held.get("recovery").timeMillis(),
                0
        );
        assertThat(held.get("invalidDelay").score()).isEqualTo(invalidDelay);

        long currentSlot = redisTimeMillis() / SLOT_MILLIS;
        long newer = workerScore(
                1,
                currentSlot - 1,
                1
        );
        long future = workerScore(
                1,
                currentSlot + 100,
                1
        );
        long pause = workerScore(
                1,
                PAUSE_TIME_SLOT,
                1
        );
        long oldHot = workerScore(
                1,
                currentSlot - 100,
                1
        );
        redis.zadd(scoreKey("group-serviceability"), newer, "newer");
        redis.zadd(scoreKey("group-serviceability"), future, "future");
        redis.zadd(scoreKey("group-serviceability"), pause, "pause");
        redis.zadd(scoreKey("group-serviceability"), oldHot, "old-hot");

        LinkedHashMap<String, Long> evidence = new LinkedHashMap<>();
        evidence.put("hot", redisTimeMillis());
        evidence.put(
                "newer",
                (currentSlot - 2) * SLOT_MILLIS
        );
        evidence.put(
                "future",
                (currentSlot - 100) * SLOT_MILLIS
        );
        evidence.put(
                "pause",
                (currentSlot - 100) * SLOT_MILLIS
        );
        evidence.put(
                "old-hot",
                currentSlot * SLOT_MILLIS
        );
        evidence.put("missing", currentSlot * SLOT_MILLIS);
        var evidenceResults = scoreCore.rewriteCurrentPolarityWithinTimeFence(
                "group-serviceability",
                evidence,
                WorkerScorePolarity.HOT_ACQUIRE, currentSlot * SLOT_MILLIS
        );

        assertThat(evidenceResults.get("hot").status()).isEqualTo(
                WorkerScoreTransitionStatus.TRANSITIONED
        );
        assertThat(evidenceResults.get("newer").status()).isEqualTo(
                WorkerScoreTransitionStatus.STALE
        );
        assertThat(evidenceResults.get("future").status()).isEqualTo(
                WorkerScoreTransitionStatus.NOOP
        );
        assertThat(evidenceResults.get("pause").status()).isEqualTo(
                WorkerScoreTransitionStatus.NOOP
        );
        assertThat(evidenceResults.get("old-hot").status()).isEqualTo(
                WorkerScoreTransitionStatus.TRANSITIONED
        );
        assertThat(evidenceResults.get("missing").status()).isEqualTo(
                WorkerScoreTransitionStatus.STALE
        );

        var unavailable = scoreCore.rewriteCurrentPolarityWithinTimeFence(
                "group-serviceability",
                Map.of(
                        "future",
                        (currentSlot - 100) * SLOT_MILLIS,
                        "pause",
                        (currentSlot - 100) * SLOT_MILLIS
                ),
                WorkerScorePolarity.RECOVERY_RECHECK, 0L
        );
        assertThat(unavailable.values()).allSatisfy(result ->
                assertThat(result.status()).isEqualTo(
                        WorkerScoreTransitionStatus.TRANSITIONED
                ));
        Map<String, WorkerScoreState> finalStates = readCoordinates(
                "group-serviceability",
                List.of("hot", "future", "pause", "old-hot")
        );
        assertScoreShape(
                finalStates.get("hot"),
                WorkerScorePolarity.HOT_ACQUIRE,
                held.get("hot").timeMillis(),
                0
        );
        assertScoreShape(
                finalStates.get("future"),
                WorkerScorePolarity.RECOVERY_RECHECK,
                (currentSlot + 100) * SLOT_MILLIS,
                1
        );
        assertScoreShape(
                finalStates.get("pause"),
                WorkerScorePolarity.RECOVERY_RECHECK,
                PAUSE_TIME_MILLIS,
                1
        );
        assertScoreShape(
                finalStates.get("old-hot"),
                WorkerScorePolarity.HOT_ACQUIRE,
                currentSlot * SLOT_MILLIS,
                0
        );
    }



    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(WorkerScorePolarity.class)
    void serviceabilityCurrentSlotBatchPreservesCoordinatesAndArrivalOrder(WorkerScorePolarity target)
            throws Exception {
        var commands = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var listener = new io.lettuce.core.event.command.CommandListener() {
            @Override public void commandStarted(io.lettuce.core.event.command.CommandStartedEvent event) {
                commands.add(event.getCommand().getType().toString());
            }
        };
        redisClient.addListener(listener);
        try {
            for (int attempt = 0; attempt < 8; attempt++) {
                long slot = redisTimeMillis() / 100 + 10;
                var original = new LinkedHashMap<String, Long>();
                var evidence = new LinkedHashMap<String, Long>();
                for (int index = 0; index < 100; index++) {
                    String id = "worker-" + index;
                    long score = workerScore(-polarityValue(target), slot, index % 2);
                    original.put(id, score);
                    evidence.put(id, (slot - 1) * 100);
                    redis.zadd(scoreKey("g"), score, id);
                }
                // Warm the lazy Owner connection before measuring commands and the target slot.
                scoreCore.observeSchedulingStates("g", List.of("worker-0"));
                awaitRedisTime(slot * 100 + 2);
                long before = redisTimeMillis();
                Map<String, WorkerScoreTransitionResult> first, repeated, currentPreserved, reversed;
                var ahead = new LinkedHashMap<>(evidence);
                ahead.replaceAll((id, timestamp) -> (slot + 1) * 100);
                WorkerScorePolarity opposite = target == WorkerScorePolarity.HOT_ACQUIRE
                        ? WorkerScorePolarity.RECOVERY_RECHECK : WorkerScorePolarity.HOT_ACQUIRE;
                commands.clear();
                first = scoreCore.rewriteCurrentPolarityWithinTimeFence("g", evidence, target, 0L);
                repeated = scoreCore.rewriteCurrentPolarityWithinTimeFence("g", evidence, target, 0L);
                // The Owner preserves current coordinates; future-report admission remains upstream.
                currentPreserved = scoreCore.rewriteCurrentPolarityWithinTimeFence("g", ahead, target, 0L);
                reversed = scoreCore.rewriteCurrentPolarityWithinTimeFence("g", evidence, opposite, 0L);
                long after = redisTimeMillis();
                assertThat(commands).containsExactly("EVAL", "EVAL", "EVAL", "EVAL");
                if (before / 100 != slot || after / 100 != slot) continue;

                assertThat(first).hasSize(100);
                original.forEach((id, score) -> {
                    assertThat(first.get(id).status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
                    assertThat(first.get(id).score()).isEqualTo(-score);
                    assertThat(repeated.get(id).status()).isEqualTo(WorkerScoreTransitionStatus.NOOP);
                    assertThat(repeated.get(id).score()).isEqualTo(-score);
                    assertThat(currentPreserved.get(id).status()).isEqualTo(WorkerScoreTransitionStatus.NOOP);
                    assertThat(currentPreserved.get(id).score()).isEqualTo(-score);
                    assertThat(reversed.get(id).status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
                    assertThat(reversed.get(id).score()).isEqualTo(score);
                });
                return;
            }
            throw new AssertionError("Could not execute the evidence batch within one Redis slot in eight attempts");
        } finally {
            redisClient.removeListener(listener);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(WorkerScorePolarity.class)
    void serviceabilityPastCoordinatesKeepFreshnessChecksAndPauseProtection(WorkerScorePolarity target) {
        long nowSlot = redisTimeMillis() / 100;
        long pastSlot = nowSlot - 20;
        long past = workerScore(-polarityValue(target), pastSlot, 1);
        long pause = workerScore(-polarityValue(target), PAUSE_TIME_SLOT, 1);
        for (String id : List.of("older", "same", "newer")) redis.zadd(scoreKey("g"), past, id);
        redis.zadd(scoreKey("g"), pause, "pause");
        var result = scoreCore.rewriteCurrentPolarityWithinTimeFence("g", Map.of(
                "older", (pastSlot - 1) * 100,
                "same", pastSlot * 100,
                "newer", (pastSlot + 1) * 100,
                "pause", pastSlot * 100,
                "missing", pastSlot * 100), target, (target == WorkerScorePolarity.HOT_ACQUIRE ? pastSlot : 0) * 100);

        assertThat(result.get("older").status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(redis.zscore(scoreKey("g"), "older")).isEqualTo((double) past);
        assertThat(result.get("same").status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(result.get("same").score()).isEqualTo(workerScore(polarityValue(target), pastSlot + 1, 0));
        assertThat(result.get("newer").status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(result.get("newer").score()).isEqualTo(workerScore(polarityValue(target), pastSlot + 1, 0));
        assertThat(result.get("pause").score()).isEqualTo(-pause);
        assertThat(result.get("missing").status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(redis.zscore(scoreKey("g"), "missing")).isNull();
    }

    @Test
    void serviceabilityCurrentSlotDisconnectAndConfirmationHaveOnlySerializedOutcomes() throws Exception {
        try (var competing = new RedisWorkerScoreCore(redisClient, keyspace);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            competing.observeSchedulingStates("g", List.of("w"));
            for (int attempt = 0; attempt < 8; attempt++) {
                long slot = redisTimeMillis() / 100 + 10;
                long held = workerScore(1, slot, 0);
                redis.zadd(scoreKey("g"), held, "w");
                var start = new CountDownLatch(1);
                var confirmation = executor.submit(() -> {
                    start.await();
                    return competing.acquireObservedHotScoreLeases("g", Map.of("w", held), (slot + 50) * 100).get("w");
                });
                var disconnection = executor.submit(() -> {
                    start.await();
                    return scoreCore.rewriteCurrentPolarityWithinTimeFence("g", Map.of("w", (slot - 1) * 100),
                            WorkerScorePolarity.RECOVERY_RECHECK, 0L).get("w");
                });
                long before;
                try {
                    awaitRedisTime(slot * 100 + 2);
                    before = redisTimeMillis();
                } finally {
                    start.countDown();
                }
                var confirmed = confirmation.get(5, TimeUnit.SECONDS);
                var disconnected = disconnection.get(5, TimeUnit.SECONDS);
                long after = redisTimeMillis();
                if (before / 100 != slot || after / 100 != slot) continue;

                assertThat(disconnected.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
                assertThat(confirmed.status()).isIn(WorkerScoreTransitionStatus.TRANSITIONED, WorkerScoreTransitionStatus.STALE);
                long expected = confirmed.status() == WorkerScoreTransitionStatus.TRANSITIONED ? -confirmed.score() : -held;
                assertThat(redis.zscore(scoreKey("g"), "w")).isEqualTo((double) expected);
                return;
            }
        }
        throw new AssertionError("Could not observe the concurrent transitions within one Redis slot in eight attempts");
    }

    private void awaitRedisTime(long targetMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            long remaining = targetMillis - redisTimeMillis();
            if (remaining <= 0) return;
            Thread.sleep(Math.min(10, remaining));
        }
        throw new AssertionError("Redis clock did not reach the test window within five seconds");
    }

    @Test
    void serviceabilityRangesUseExclusiveHotCutoffAndExcludeColdScores() {
        long nowSlot = redisTimeMillis() / SLOT_MILLIS;
        long hotCutoffMillis = (nowSlot - 20) * SLOT_MILLIS;
        long hotCutoffSlot = hotCutoffMillis / SLOT_MILLIS;
        long hotHigher = workerScore(
                1,
                hotCutoffSlot - 1,
                0
        );
        long hotLower = workerScore(
                1,
                hotCutoffSlot - 2,
                0
        );
        redis.zadd(scoreKey("group-range"), hotHigher, "hot-higher");
        redis.zadd(scoreKey("group-range"), hotLower, "hot-lower");
        redis.zadd(scoreKey("group-range"), workerScore(
                1,
                hotCutoffSlot,
                0
        ), "hot-at-floor");

        var firstHot = scoreCore.observeHotCandidatesBefore(
                "group-range", hotCutoffMillis, 1
        );
        assertThat(scoreCore.observeHotCandidatesBefore("group-range", hotCutoffMillis, 1))
                .isEqualTo(firstHot);
        assertThat(scoreCore.deferObservedToRecovery("group-range", Map.of("hot-higher", hotHigher), 60_000)
                .get("hot-higher").status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        var secondHot = scoreCore.observeHotCandidatesBefore("group-range", hotCutoffMillis, 2);
        assertThat(firstHot.keySet()).containsExactly("hot-higher");
        assertThat(secondHot.keySet()).containsExactly("hot-lower");

        long recoveryOlder = workerScore(
                -1,
                nowSlot - 100,
                0
        );
        long recoveryNewer = workerScore(
                -1,
                nowSlot - 50,
                0
        );
        redis.zadd(
                scoreKey("group-range"),
                recoveryOlder,
                "recovery-older"
        );
        redis.zadd(
                scoreKey("group-range"),
                recoveryNewer,
                "recovery-newer"
        );
        redis.zadd(scoreKey("group-range"), workerScore(
                -1,
                1,
                0
        ), "cold");

        var firstRecovery = scoreCore.observeRecoveryRecheckCandidates(
                "group-range", 1
        );
        assertThat(scoreCore.observeRecoveryRecheckCandidates("group-range", 1))
                .isEqualTo(firstRecovery);
        assertThat(scoreCore.deferObservedToRecovery("group-range", Map.of("recovery-older", recoveryOlder), 60_000)
                .get("recovery-older").status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        var secondRecovery = scoreCore.observeRecoveryRecheckCandidates("group-range", 2);
        assertThat(firstRecovery.keySet()).containsExactly("recovery-older");
        assertThat(secondRecovery.keySet()).containsExactly("recovery-newer");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, -1})
    void serviceabilityAdvancesAllEqualScoreWorkersFromTheSameHead(int polarity) {
        long nowSlot = redisTimeMillis() / 100;
        long observed = workerScore(polarity, nowSlot - 100, 1);
        var remaining = new java.util.HashSet<String>();
        for (int i = 0; i < 250; i++) {
            String id = "worker-" + i;
            remaining.add(id);
            redis.zadd(scoreKey("group-recheck-head"), observed, id);
        }
        for (int expectedSize : new int[]{100, 100, 50}) {
            var head = polarity == 1
                    ? scoreCore.observeHotCandidatesBefore("group-recheck-head", nowSlot * 100, 100)
                    : scoreCore.observeRecoveryRecheckCandidates("group-recheck-head", 100);
            assertThat(head).hasSize(expectedSize);
            var targets = new LinkedHashMap<String, Long>();
            head.forEach((id, score) -> {
                assertThat(remaining.remove(id)).isTrue();
                assertThat(score).isEqualTo(observed);
                targets.put(id, score);
            });
            var results = scoreCore.deferObservedToRecovery("group-recheck-head", targets, 60_000);
            assertThat(results).hasSize(expectedSize);
            assertThat(results.values()).allSatisfy(result ->
                    assertThat(result.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED));
        }
        assertThat(remaining).isEmpty();
        assertThat(scoreCore.observeHotCandidatesBefore("group-recheck-head", nowSlot * 100, 100)).isEmpty();
        assertThat(scoreCore.observeRecoveryRecheckCandidates("group-recheck-head", 100)).isEmpty();
    }

    @Test
    void recoveryReadIncludesOldCoordinatesAndRespectsCurrentSlotAndColdFloor() throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            awaitRedisTime((redisTimeMillis() / 100 + 1) * 100);
            long now = redisTimeMillis();
            long slot = now / 100;
            String group = "recheck-boundary-" + attempt;
            long floor = slot - 864_000;
            var scores = Map.of(
                    "older-than-day", workerScore(-1, floor - 1, 0),
                    "one-day", workerScore(-1, floor, 0),
                    "due-mark", workerScore(-1, slot - 1, 1),
                    "current", workerScore(-1, slot, 0),
                    "future", workerScore(-1, slot + 100, 0),
                    "pause", workerScore(-1, PAUSE_TIME_SLOT, 0),
                    "cold", workerScore(-1, 1, 0),
                    "cold-mark", workerScore(-1, 1, 1),
                    "first-valid", workerScore(-1, 2, 0));
            scores.forEach((id, score) -> redis.zadd(scoreKey(group), score, id));
            var head = scoreCore.observeRecoveryRecheckCandidates(group, 100);
            var rejected = scoreCore.deferObservedToRecovery(group, Map.of(
                    "current", scores.get("current"),
                    "future", scores.get("future"),
                    "pause", scores.get("pause")), 1_000);
            var invalidTarget = scoreCore.deferObservedToRecovery(group,
                    Map.of("due-mark", scores.get("due-mark")),
                    PAUSE_TIME_MILLIS - now + 100);
            if (redisTimeMillis() / 100 != slot) continue;
            assertThat(head.keySet())
                    .containsExactly("first-valid", "older-than-day", "one-day", "due-mark");
            for (String id : List.of("current", "future", "pause")) {
                assertThat(rejected.get(id).status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
            }
            assertThat(invalidTarget.get("due-mark").status()).isEqualTo(WorkerScoreTransitionStatus.INVALID);
            scores.forEach((id, score) -> assertThat(redis.zscore(scoreKey(group), id)).isEqualTo(score.doubleValue()));
            // No sweep restart is required: the unchanged current-slot member becomes visible once due.
            awaitRedisTime((slot + 1) * 100);
            assertThat(scoreCore.observeRecoveryRecheckCandidates(group, 100).keySet()).contains("current");
            return;
        }
        throw new AssertionError("Could not check recheck boundaries within one Redis slot in eight attempts");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, -1})
    void concurrentServiceabilityHoldsHaveOneWinnerAndCannotAdvanceAgainBeforeDue(int polarity)
            throws Exception {
        long observed = workerScore(polarity, redisTimeMillis() / 100 - 10, 1);
        redis.zadd(scoreKey("recheck-race"), observed, "w");
        var target = Map.of("w", observed);
        var start = new CountDownLatch(1);
        try (var competing = new RedisWorkerScoreCore(redisClient, keyspace);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await();
                return scoreCore.deferObservedToRecovery("recheck-race", target, 60_000);
            });
            var second = executor.submit(() -> {
                start.await();
                return competing.deferObservedToRecovery("recheck-race", target, 60_000);
            });
            start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS).get("w").status(),
                    second.get(5, TimeUnit.SECONDS).get("w").status()))
                    .containsExactlyInAnyOrder(WorkerScoreTransitionStatus.TRANSITIONED, WorkerScoreTransitionStatus.STALE);
        }
        long held = readCoordinates("recheck-race", List.of("w")).get("w").score();
        assertThat(scoreCore.deferObservedToRecovery("recheck-race", Map.of("w", held), 60_000).get("w").status())
                .isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(redis.zscore(scoreKey("recheck-race"), "w")).isEqualTo((double) held);
        scoreCore.rewriteCurrentPolarityWithinTimeFence("recheck-race", Map.of("w", redisTimeMillis()),
                WorkerScorePolarity.HOT_ACQUIRE, System.currentTimeMillis());
        assertThat(scoreCore.observeDueHotScoreCandidates("recheck-race", null, 100)).isEmpty();
        assertThat(scoreCore.deferObservedToRecovery("recheck-race", Map.of("w", -held), 60_000).get("w").status())
                .isEqualTo(WorkerScoreTransitionStatus.STALE);
    }

    @Test
    void recheckDelayStartsAtRedisExecutionEvenWhenSubmissionIsDelayed() {
        long before = redisTimeMillis();
        long oldRecovery = workerScore(-1, before / 100 - 10, 1);
        redis.zadd(scoreKey("recheck-time"), oldRecovery, "w");
        var commands = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var measuring = new java.util.concurrent.atomic.AtomicBoolean(true);
        var listener = new io.lettuce.core.event.command.CommandListener() {
            @Override public void commandStarted(io.lettuce.core.event.command.CommandStartedEvent event) {
                if (!measuring.get()) return;
                String command = event.getCommand().getType().toString();
                commands.add(command);
                if (!command.equals("EVAL")) return;
                try { Thread.sleep(300); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }
        };
        redisClient.addListener(listener);
        try {
            assertThat(scoreCore.deferObservedToRecovery("recheck-time", Map.of("w", oldRecovery), 60_000).get("w").status())
                    .isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        } finally {
            measuring.set(false);
            redisClient.removeListener(listener);
        }
        long after = redisTimeMillis();
        var held = readCoordinates("recheck-time", List.of("w")).get("w");
        assertThat(commands).containsExactly("EVAL");
        assertThat(held.timeMillis()).isBetween((before + 300 + 60_000) / 100 * 100,
                (after + 60_000) / 100 * 100);

        assertThat(held.mark()).isZero();
    }

    @Test
    void polarityToggleAndColdParkUseExactObservedScoreCas() {
        long timeSlot = redisTimeMillis() / SLOT_MILLIS;
        long hot = workerScore(
                1,
                timeSlot,
                1
        );
        long recovery = workerScore(
                -1,
                timeSlot,
                1
        );
        redis.zadd(scoreKey("group-1"), hot, "toggle-worker");
        redis.zadd(scoreKey("group-1"), recovery, "cold-worker");

        var toggled = scoreCore.toggleCurrentPolarity(
                "group-1",
                "toggle-worker",
                hot
        );
        assertThat(toggled.status()).isEqualTo(
                WorkerScoreTransitionStatus.TRANSITIONED
        );
        assertScoreShape(
                readCoordinates(
                        "group-1",
                        List.of("toggle-worker")
                ).get("toggle-worker"),
                WorkerScorePolarity.RECOVERY_RECHECK,
                timeSlot * SLOT_MILLIS,
                0
        );
        assertThat(scoreCore.toggleCurrentPolarity(
                "group-1",
                "toggle-worker",
                hot
        ).status()).isEqualTo(WorkerScoreTransitionStatus.STALE);

        var parked = scoreCore.parkObservedRecoveryScore(
                "group-1",
                "cold-worker",
                recovery
        );
        assertThat(parked.status()).isEqualTo(
                WorkerScoreTransitionStatus.TRANSITIONED
        );
        assertScoreShape(
                readCoordinates(
                        "group-1",
                        List.of("cold-worker")
                ).get("cold-worker"),
                WorkerScorePolarity.RECOVERY_RECHECK,
                SLOT_MILLIS,
                1
        );
        assertThat(scoreCore.parkObservedRecoveryScore(
                "group-1",
                "toggle-worker",
                hot
        ).status()).isEqualTo(WorkerScoreTransitionStatus.INVALID);
        assertThat(scoreCore.parkObservedRecoveryScore("group-1", "cold-worker", recovery).status())
                .isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(scoreCore.parkObservedRecoveryScore("group-1", "cold-worker", parked.score()).status())
                .isEqualTo(WorkerScoreTransitionStatus.NOOP);
    }

    @Test
    void rejectsStoredIdentityMismatch() {
        redis.hset(
                groupsKey(),
                "group-1",
                "{\"attributes\":{},\"eventCodes\":[\"event\"],"
                        + "\"workerGroupId\":\"group-2\"}"
        );
        assertThat(catalog.getWorkerGroupDescriptors(List.of("group-1")))
                .containsEntry("group-1", null);

    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 100})
    void pastTimeInvalidationUsesOneCommandAndNeverCreatesMembers(int count) {
        var ids = java.util.stream.IntStream.range(0, count).mapToObj(i -> "w" + i).toList();
        ids.forEach(id -> redis.zadd(scoreKey("g"), -200, id));
        var commands = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var listener = commandListener(commands); redisClient.addListener(listener);
        Map<String, WorkerScoreTransitionResult> results;
        try { results = scoreCore.advancePastScoreTimesToNow("g", ids); }
        finally { redisClient.removeListener(listener); }
        assertThat(commands).containsExactly("EVAL");
        assertThat(results.values()).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
            assertThat(result.score()).isNegative();
            assertThat(decodeState("w", result.score()).mark()).isZero();
        });
        assertThat(results.values().stream().map(WorkerScoreTransitionResult::score).distinct()).hasSize(1);
        assertThat(scoreCore.advancePastScoreTimesToNow("g", List.of("missing")).get("missing").status())
                .isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(redis.zcard(scoreKey("g"))).isEqualTo(count);
    }

    @Test
    void pastTimeInvalidationResetsOnlyPastHotMarksAndRejectsCorruptScoresPerMember() {
        long now = redisTimeMillis() / SLOT_MILLIS;
        for (int sign : List.of(-1, 1)) {
            for (long slot : List.of(0L, 1L, now - 1, now + 100, PAUSE_TIME_SLOT)) {
                for (int mark : List.of(0, 1)) {
                    long original = workerScore(sign, slot, mark);
                    if (original == 0) continue; // Slot zero with zero mark has no legal polarity.
                    redis.zadd(scoreKey("g"), original, "w");
                    var result = scoreCore.advancePastScoreTimesToNow("g", List.of("w")).get("w");
                    boolean coldRecovery = sign < 0 && slot <= COLD_PARK_TIME_SLOT;
                    assertThat(result.status()).isEqualTo(slot < now && !coldRecovery
                            ? WorkerScoreTransitionStatus.TRANSITIONED : WorkerScoreTransitionStatus.NOOP);
                    var state = decodeState("w", result.score());
                    assertThat(state.mark()).isEqualTo(sign > 0 && slot < now ? 0 : mark);
                    assertThat(state.polarity()).isEqualTo(sign > 0 ? WorkerScorePolarity.HOT_ACQUIRE : WorkerScorePolarity.RECOVERY_RECHECK);
                    if (coldRecovery) assertThat(state.timeMillis()).isEqualTo(slot * SLOT_MILLIS);
                    else assertThat(state.timeMillis()).isBetween(Math.max(slot, now) * 100, Math.max(slot, redisTimeMillis() / 100) * 100);
                    assertThat(redis.zscore(scoreKey("g"), "w")).isEqualTo(result.score().doubleValue());
                }
            }
        }
        for (double invalid : List.of(0.0, 200.5, -200.5, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, (double) workerScore(1, MAX_TIME_SLOT + 1, 1))) {
            redis.zadd(scoreKey("g"), invalid, "bad");
            redis.zadd(scoreKey("g"), 200, "valid");
            var results = scoreCore.advancePastScoreTimesToNow("g", List.of("bad", "valid"));
            assertThat(results.get("bad").status()).isEqualTo(WorkerScoreTransitionStatus.INVALID);
            assertThat(redis.zscore(scoreKey("g"), "bad")).isEqualTo(invalid);
            assertThat(decodeState("valid", results.get("valid").score()).timeMillis()).isGreaterThan(20000);
        }
    }

    @Test
    void propertiesInvalidationKeepsCurrentSlotCoordinatesUnchanged() throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            awaitRedisTime((redisTimeMillis() / SLOT_MILLIS + 1) * SLOT_MILLIS);
            long slot = redisTimeMillis() / SLOT_MILLIS;
            var originals = new LinkedHashMap<String, Long>();
            for (int sign : List.of(-1, 1)) {
                for (int mark : List.of(0, 1)) {
                    String id = sign + "-" + mark;
                    long score = workerScore(sign, slot, mark);
                    originals.put(id, score);
                    redis.zadd(scoreKey("current"), score, id);
                }
            }
            var ids = List.copyOf(originals.keySet());
            var first = scoreCore.advancePastScoreTimesToNow("current", ids);
            var repeated = scoreCore.advancePastScoreTimesToNow("current", ids);
            if (redisTimeMillis() / SLOT_MILLIS != slot) continue;
            originals.forEach((id, score) -> {
                var unchanged = new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.NOOP, score);
                assertThat(first.get(id)).isEqualTo(unchanged);
                assertThat(repeated.get(id)).isEqualTo(unchanged);
            });
            return;
        }
        throw new AssertionError("Could not observe Properties invalidation within a Redis slot");
    }

    @Test
    void invalidationBeforeConfirmationRejectsBothCachedAndLateMatchingFences() {
        long now = redisTimeMillis();
        long due = workerScore(1, now / SLOT_MILLIS - 10, 0);
        redis.zadd(scoreKey("g"), due, "w");
        var held = scoreCore.candidateizeObservedHotScores("g", Map.of("w", due)).get("w");
        assertThat(held.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(held.score() / MARK_BASE).isEqualTo(1);
        var changed = scoreCore.advancePastScoreTimesToNow("g", List.of("w")).get("w");
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThat(scoreCore.acquireObservedHotScoreLeases("g", Map.of("w", held.score()), now + 40_000)
                    .get("w").status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
        }
        assertThat(scoreCore.acquireObservedHotScoreLeases("g", Map.of("w", held.score() + 1), now + 40_000)
                .get("w").status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(redis.zscore(scoreKey("g"), "w")).isEqualTo((double) changed.score());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void executionConsumesEitherCandidateLaneAndPropertiesPreserveResultFence(boolean extend) {
        long now = redisTimeMillis();
        long held = workerScore(1, (now - 30_000) / SLOT_MILLIS, extend ? 1 : 0);
        long target = now + (extend ? 40_000 : 20_000);
        redis.zadd(scoreKey("g"), held, "w");
        var result = scoreCore.acquireObservedHotScoreLeases("g", Map.of("w", held), target).get("w");
        long execution = workerScore(1, target / SLOT_MILLIS, 0);
        assertThat(result).isEqualTo(new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.TRANSITIONED, execution));
        assertThat(scoreCore.acquireObservedHotScoreLeases("g", Map.of("w", held), target).get("w").status())
                .isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(scoreCore.advancePastScoreTimesToNow("g", List.of("w")).get("w"))
                .isEqualTo(new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.NOOP, execution));
        long releaseAt = redisTimeMillis() + 500;
        assertThat(scoreCore.releaseObservedHotScoreHolds("g", Map.of("w", held), releaseAt)
                .get("w").status()).isEqualTo(WorkerScoreTransitionStatus.INVALID);
        var released = scoreCore.releaseObservedHotScoreHolds("g", Map.of("w", execution), releaseAt).get("w");
        assertThat(released.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(released.score() / MARK_BASE).isZero();
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).until(() ->
                !scoreCore.observeDueHotScoreCandidates("g", null, 100).isEmpty());
        assertThat(scoreCore.observeDueHotScoreCandidates("g", null, 10)).containsKey("w");
        var reacquired = scoreCore.acquireObservedHotScoreLeases("g", Map.of("w", released.score()),
                redisTimeMillis() + 30_000).get("w");
        assertThat(reacquired.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(reacquired.score() / MARK_BASE).isZero();
        assertThat(reacquired.score()).isNotEqualTo(held);
    }

    @Test
    void executionFencePreservesExistingFailureAndSignFlippedSuccessReleaseRules() {
        long now = redisTimeMillis();
        long held = workerScore(1, (now - 30_000) / SLOT_MILLIS, 0);
        redis.zadd(scoreKey("g"), held, "w");
        long execution = scoreCore.acquireObservedHotScoreLeases("g", Map.of("w", held), now + 20_000).get("w").score();
        redis.zadd(scoreKey("g"), -execution, "w");
        scoreCore.advancePastScoreTimesToNow("g", List.of("w"));
        long releaseAt = redisTimeMillis() + 500;
        assertThat(scoreCore.releaseScoreHolds("g", Map.of("w", execution), releaseAt).get("w").status())
                .isEqualTo(WorkerScoreTransitionStatus.STALE);
        var completed = scoreCore.releaseObservedHotScoreHolds("g", Map.of("w", execution), releaseAt).get("w");
        assertThat(completed.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(completed.score()).isPositive();
        assertThat(completed.score() / MARK_BASE).isZero();
        redis.zadd(scoreKey("g"), execution, "w");
        assertThat(scoreCore.releaseScoreHolds("g", Map.of("w", execution), releaseAt).get("w").status())
                .isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void concurrentExecutionAcquisitionsHaveOneWinnerAndRejectOccupiedHolds(boolean candidateMark) throws Exception {
        long now = redisTimeMillis();
        long held = workerScore(1, (now - 30_000) / SLOT_MILLIS, candidateMark ? 1 : 0);
        redis.zadd(scoreKey("g"), held, "w");
        CountDownLatch start = new CountDownLatch(1);
        try (var competing = new RedisWorkerScoreCore(redisClient, keyspace);
             var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await();
                return scoreCore.acquireObservedHotScoreLeases("g", Map.of("w", held), now + 60_000).get("w").status(); });
            var second = executor.submit(() -> { start.await();
                return competing.acquireObservedHotScoreLeases("g", Map.of("w", held), now + 60_000).get("w").status(); });
            start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(WorkerScoreTransitionStatus.TRANSITIONED, WorkerScoreTransitionStatus.STALE);
        }
        long pause = workerScore(1, PAUSE_TIME_SLOT, 1);
        long expired = workerScore(1, now / SLOT_MILLIS + 100, 0);
        redis.zadd(scoreKey("g"), pause, "paused");
        redis.zadd(scoreKey("g"), expired, "expired");
        assertThat(scoreCore.acquireObservedHotScoreLeases("g", Map.of("paused", pause, "expired", expired), now + 20_000)
                .values()).allSatisfy(result -> assertThat(result.status()).isEqualTo(WorkerScoreTransitionStatus.STALE));
        assertThat(redis.zscore(scoreKey("g"), "paused")).isEqualTo((double) pause);
    }

    @Test
    void candidateizationAndExecutionAcquisitionUseOneLuaEachAndInvalidateOldFences() {
        long now=redisTimeMillis();
        var due=new LinkedHashMap<String,Long>();
        for(int i=0;i<100;i++) {
            long value=workerScore(1,now/100-10,0);
            due.put("w"+i,value);redis.zadd(scoreKey("g"),value,"w"+i);
        }
        var commands=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var listener=new io.lettuce.core.event.command.CommandListener() {
            @Override public void commandStarted(io.lettuce.core.event.command.CommandStartedEvent event) {
                commands.add(event.getCommand().getType().toString());
            }
        };
        redisClient.addListener(listener);
        try {
            var candidate=transitionedScores(scoreCore.candidateizeObservedHotScores("g",due));
            assertThat(candidate).hasSize(100);assertThat(commands).containsExactly("EVAL");commands.clear();
            var execution=transitionedScores(scoreCore.acquireObservedHotScoreLeases("g", candidate, now+3000));
            assertThat(execution).hasSize(100);assertThat(commands).containsExactly("EVAL");
            for(String id:due.keySet()) {
                assertThat(candidate.get(id) / MARK_BASE).isEqualTo(1);
                assertThat(candidate.get(id)).isEqualTo(workerScore(1, now / 100 - 10, 1));
                assertThat(execution.get(id)).isEqualTo(workerScore(1, (now + 3000) / 100, 0));
            }
            assertThat(scoreCore.acquireObservedHotScoreLeases("g",due,now+6000).values())
                    .allSatisfy(r->assertThat(r.status()).isEqualTo(WorkerScoreTransitionStatus.STALE));
            assertThat(scoreCore.acquireObservedHotScoreLeases("g", candidate, now+6000).values())
                    .allSatisfy(r->assertThat(r.status()).isEqualTo(WorkerScoreTransitionStatus.STALE));
            assertThat(scoreCore.releaseObservedHotScoreHolds("g",Map.of("w0",candidate.get("w0")),now+500).get("w0").status())
                    .isEqualTo(WorkerScoreTransitionStatus.INVALID);
            assertThat(scoreCore.releaseObservedHotScoreHolds("g",Map.of("w0",execution.get("w0")),now+500).get("w0").status())
                    .isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        } finally { redisClient.removeListener(listener); }
    }





    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void maximumCoordinateIsOccupiedAndRequiresExplicitRelease(boolean hint) {
        long maximum = workerScore(1, MAX_TIME_SLOT, 0);
        long request = redisTimeMillis() + 5000;
        redis.zadd(scoreKey("max"), maximum, "w");
        assertThat((hint ? scoreCore.acquireCurrentHotScoreLeases("max", List.of("w"), request)
                : scoreCore.acquireObservedHotScoreLeases("max", Map.of("w", maximum), request)).get("w").status())
                .isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(scoreCore.pauseScheduling("max", "w")).isEqualTo(WorkerSchedulingChangeStatus.UNCHANGED);
        assertThat(scoreCore.resumeScheduling("max", "w")).isEqualTo(WorkerSchedulingChangeStatus.APPLIED);
    }

    @Test
    void relativeDeferralAllowsTheMaximumSlotAndRejectsOnlyLargerTargets() throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            awaitRedisTime((redisTimeMillis() / SLOT_MILLIS + 1) * SLOT_MILLIS);
            long before = redisTimeMillis();
            String group = "relative-max-" + attempt;
            var observations = new LinkedHashMap<String, Long>();
            for (int sign : new int[]{1, -1}) for (int mark : new int[]{0, 1}) {
                String id = sign + ":" + mark;
                long score = workerScore(sign, before / SLOT_MILLIS - 10, mark);
                redis.zadd(scoreKey(group), score, id);
                observations.put(id, score);
            }
            long delay = MAX_TIME_MILLIS - before;
            var results = scoreCore.deferObservedToRecovery(group, observations, delay);
            if (redisTimeMillis() - before >= SLOT_MILLIS) continue;
            observations.forEach((id, score) -> assertThat(results.get(id)).isEqualTo(new WorkerScoreTransitionResult(
                    WorkerScoreTransitionStatus.TRANSITIONED, workerScore(-1, MAX_TIME_SLOT, 0))));
            observations.forEach((id, score) -> redis.zadd(scoreKey(group), score, id));
            var rejected = scoreCore.deferObservedToRecovery(group, observations, delay + SLOT_MILLIS);
            observations.forEach((id, score) -> {
                assertThat(rejected.get(id)).isEqualTo(new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.INVALID, score));
                assertThat(redis.zscore(scoreKey(group), id)).isEqualTo(score.doubleValue());
            });
            return;
        }
        throw new AssertionError("Could not check maximum relative target within one Redis slot in eight attempts");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1})
    void availableEvidenceRestoresOrdinaryHotAtANewGeneration(int mark) {
        long now = redisTimeMillis();
        long recovery = workerScore(-1, now / 100 - 300, mark);
        redis.zadd(scoreKey("evidence-candidate"), recovery, "w");
        long evidenceSlot = now / 100 - 2;
        var activated = scoreCore.rewriteCurrentPolarityWithinTimeFence("evidence-candidate", Map.of("w", evidenceSlot * 100),
                WorkerScorePolarity.HOT_ACQUIRE, now - 60_000).get("w");
        assertThat(activated.score()).isEqualTo(workerScore(1, evidenceSlot, 0));
        assertThat(decodeState("w", activated.score()).mark()).isZero();
        assertThat(scoreCore.acquireObservedHotScoreLeases("evidence-candidate", Map.of("w", activated.score()), now + 5000)
                .get("w").status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
    }

    private static Map<String,Long> transitionedScores(Map<String,WorkerScoreTransitionResult> results) {
        var scores=new LinkedHashMap<String,Long>();
        results.forEach((id,result)-> {if(result.status()==WorkerScoreTransitionStatus.TRANSITIONED)scores.put(id,result.score());});
        return scores;
    }

    @Test
    void executionAcquisitionAcceptsDueMarkedScoresButRejectsOccupiedPausedOrChangedObservations() {
        long now=redisTimeMillis(),slot=now/100;
        var observed=new LinkedHashMap<String,Long>();
        observed.put("ordinary",workerScore(1,slot-10,0));
        observed.put("candidate",workerScore(1,slot-10,1));
        observed.put("pause",workerScore(1,PAUSE_TIME_SLOT,0));
        observed.put("occupied",workerScore(1,slot+100,0));
        observed.put("recovery",workerScore(-1,slot-10,0));
        observed.put("changed",workerScore(1,slot-10,0));
        observed.forEach((id,score)->redis.zadd(scoreKey("g"),score,id));
        redis.zadd(scoreKey("g"),observed.get("changed")+1,"changed");
        observed.put("missing",observed.get("ordinary"));
        var result=scoreCore.acquireObservedHotScoreLeases("g",observed,now+1000);
        assertThat(transitionedScores(result)).containsOnlyKeys("ordinary","candidate");
        assertThat(transitionedScores(result).values()).allSatisfy(value->assertThat(value / MARK_BASE).isZero());
        for(String id:List.of("pause","occupied","recovery"))assertThat(redis.zscore(scoreKey("g"),id)).isEqualTo(observed.get(id).doubleValue());
        assertThat(redis.zscore(scoreKey("g"),"missing")).isNull();
    }

    @Test
    void concurrentExecutionAcquisitionsOfOneObservationHaveOneWinner() throws Exception {
        long now=redisTimeMillis(),observed=workerScore(1,(now-1000)/100,1);
        redis.zadd(scoreKey("g"),observed,"w");
        var start=new CountDownLatch(1);
        try(var competing=new RedisWorkerScoreCore(redisClient,keyspace);var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->{start.await();return scoreCore.acquireObservedHotScoreLeases("g",Map.of("w",observed),now+1000).get("w").status();});
            var b=executor.submit(()->{start.await();return competing.acquireObservedHotScoreLeases("g",Map.of("w",observed),now+2000).get("w").status();});
            start.countDown();
            assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(WorkerScoreTransitionStatus.TRANSITIONED,WorkerScoreTransitionStatus.STALE);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"expired-request","became-due"})
    void leaseTimeIsCheckedAtLuaSubmissionAfterTheCallerCrossesTheSlot(String mode) {
        long now=redisTimeMillis(),deadline=now+300;
        long fence=workerScore(1,(mode.equals("expired-request")?now-1000:deadline)/100,0);
        redis.zadd(scoreKey("g"),fence,"w");
        var listener=new io.lettuce.core.event.command.CommandListener() {
            @Override public void commandStarted(io.lettuce.core.event.command.CommandStartedEvent event) {
                if(!event.getCommand().getType().toString().equals("EVAL"))return;
                try { Thread.sleep(Math.max(0,deadline-System.currentTimeMillis()+200)); }
                catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new IllegalStateException(failure); }
            }
        };
        redisClient.addListener(listener);
        WorkerScoreTransitionResult result;
        try {
            result=switch(mode) {
                case "expired-request" -> scoreCore.acquireObservedHotScoreLeases("g",Map.of("w",fence),deadline).get("w");
                default -> scoreCore.acquireObservedHotScoreLeases("g", Map.of("w",fence), now+5000).get("w");
            };
        } finally {redisClient.removeListener(listener);}
        assertThat(result.status()).isEqualTo(mode.equals("expired-request")?WorkerScoreTransitionStatus.INVALID:WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(redis.zscore(scoreKey("g"),"w")).isEqualTo(mode.equals("expired-request") ? (double) fence : result.score().doubleValue());
    }

    @Test
    void existingRawScoreEchoesStillExposeCorruptFractionalMembersWithoutWriting() {
        long slot = redisTimeMillis() / 100;
        long observation = workerScore(1, slot - 10, 0);
        double corrupt = observation + 0.5;
        redis.zadd(scoreKey("corrupt-echo"), corrupt, "w");
        assertThatThrownBy(() -> scoreCore.deferObservedToRecovery("corrupt-echo", Map.of("w", observation), 1_000))
                .isInstanceOf(IllegalStateException.class).hasMessage("Worker score must be an integer");
        assertThatThrownBy(() -> scoreCore.rewriteCurrentPolarityWithinTimeFence("corrupt-echo",
                Map.of("w", (slot - 5) * 100), WorkerScorePolarity.HOT_ACQUIRE, 0L))
                .isInstanceOf(IllegalStateException.class).hasMessage("Worker score must be an integer");
        assertThatThrownBy(() -> scoreCore.rewriteCurrentPolarityWithinTimeFence("corrupt-echo",
                Map.of("w", (slot - 20) * 100), WorkerScorePolarity.RECOVERY_RECHECK, 0L))
                .isInstanceOf(IllegalStateException.class).hasMessage("Worker score must be an integer");
        assertThat(redis.zscore(scoreKey("corrupt-echo"), "w")).isEqualTo(corrupt);
        // Ordinary CAS already uses Redis numeric replies; keep its separate result convention too.
        var exact = scoreCore.toggleCurrentPolarity("corrupt-echo", "w", observation);
        assertThat(exact.status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(exact.score()).isEqualTo(observation);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, -1})
    void pastPolarityChangeRefreshesGenerationWithOrWithoutAnActivationMinimum(int polarity) {
        long slot = redisTimeMillis() / 100;
        var target = polarity == 1 ? WorkerScorePolarity.HOT_ACQUIRE : WorkerScorePolarity.RECOVERY_RECHECK;
        for (boolean withMinimum : new boolean[]{false, true}) {
            String id = "minimum-" + withMinimum;
            long observed = workerScore(-polarity, slot - 10, 1);
            redis.zadd(scoreKey("mechanical-polarity"), observed, id);
            var times = Map.of(id, (slot - 5) * 100);
            var result = scoreCore.rewriteCurrentPolarityWithinTimeFence(
                    "mechanical-polarity", times, target, withMinimum ? (slot - 5) * 100 : 0L).get(id);
            assertThat(result.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
            assertThat(result.score()).isEqualTo(workerScore(polarity, slot - 5, 0));
            assertThat(scoreCore.rewriteCurrentPolarityWithinTimeFence(
                    "mechanical-polarity", times, target, withMinimum ? (slot - 5) * 100 : 0L).get(id).status())
                    .isEqualTo(WorkerScoreTransitionStatus.NOOP);
        }
    }

    @Test
    void acquisitionRejectsExpiredRequestedTargetBeforeCheckingObservedState() {
        long now = redisTimeMillis();
        long request = now + 300;
        long observed = workerScore(1, (now + 60_000) / 100, 0);
        redis.zadd(scoreKey("requested-target"), observed, "w");
        var listener = new io.lettuce.core.event.command.CommandListener() {
            @Override public void commandStarted(io.lettuce.core.event.command.CommandStartedEvent event) {
                if (!event.getCommand().getType().toString().equals("EVAL")) return;
                try { Thread.sleep(500); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
            }
        };
        redisClient.addListener(listener);
        WorkerScoreTransitionResult result;
        try {
            result = scoreCore.acquireObservedHotScoreLeases("requested-target", Map.of("w", observed), request).get("w");
        } finally {
            redisClient.removeListener(listener);
        }
        assertThat(result.status()).isEqualTo(WorkerScoreTransitionStatus.INVALID);
        assertThat(result.score()).isNull();
        assertThat(redis.zscore(scoreKey("requested-target"), "w")).isEqualTo((double) observed);
    }

    @Test
    void exactReplacementRejectsEveryChangedFieldAndCompletionMapsAcceptedNoop() {
        long slot = redisTimeMillis() / 100 + 600;
        long observed = workerScore(1, slot, 1);
        var changed = Map.of("time", observed + 2,
                "mark", observed - MARK_BASE, "polarity", -observed);
        changed.forEach((id, value) -> redis.zadd(scoreKey("exact-fields"), value, id));
        for (String id : changed.keySet()) {
            var result = scoreCore.toggleCurrentPolarity("exact-fields", id, observed);
            assertThat(result.status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
            assertThat(result.score()).isEqualTo(changed.get(id));
        }
        var observations = new LinkedHashMap<String, Long>();
        changed.keySet().forEach(id -> observations.put(id, observed));
        var completion = scoreCore.releaseObservedHotScoreHolds("exact-fields", observations, slot * 100);
        assertThat(completion.get("polarity").status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        for (String id : List.of("time", "mark")) {
            assertThat(completion.get(id).status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
            assertThat(redis.zscore(scoreKey("exact-fields"), id)).isEqualTo(changed.get(id).doubleValue());
        }
        redis.zadd(scoreKey("exact-fields"), observed, "same");
        assertThat(scoreCore.releaseScoreHolds("exact-fields", Map.of("same", observed), slot * 100)
                .get("same").status()).isEqualTo(WorkerScoreTransitionStatus.NOOP);
        assertThat(scoreCore.releaseObservedHotScoreHolds("exact-fields", Map.of("same", observed), slot * 100)
                .get("same").status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
    }

    @Test
    void subSlotDelayAddsBeforeRoundingAndResetsCandidateMark() throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            awaitRedisTime((redisTimeMillis() / 100 + 1) * 100 + 75);
            long before = redisTimeMillis();
            long observed = workerScore(1, before / 100 - 10, 1);
            String group = "relative-rounding-" + attempt;
            redis.zadd(scoreKey(group), observed, "w");
            var result = scoreCore.deferObservedToRecovery(group, Map.of("w", observed), 37).get("w");
            long after = redisTimeMillis();
            if (before / 100 != after / 100 || before % 100 < 75) continue;
            assertThat(result.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
            assertThat(result.score()).isEqualTo(workerScore(-1, before / 100 + 1, 0));
            return;
        }
        throw new AssertionError("Could not check sub-slot delay within one Redis slot in eight attempts");
    }

    @Test
    void recheckBatchSharesOneExecutionTimeAndDelayWhileKeepingMemberResultsIndependent() {
        String group = "shared-recheck-delay";
        long before = redisTimeMillis();
        var observations = new LinkedHashMap<String, Long>();
        for (int i = 0; i < 96; i++) {
            long observed = workerScore(i % 2 == 0 ? 1 : -1, before / 100 - 100, i / 2 % 2);
            observations.put("w-" + i, observed);
            redis.zadd(scoreKey(group), observed, "w-" + i);
        }
        long due = workerScore(1, before / 100 - 100, 0);
        observations.put("invalid", 0L);
        observations.put("cold", -1L);
        observations.put("missing", due);
        observations.put("changed", due);
        redis.zadd(scoreKey(group), 0, "invalid");
        redis.zadd(scoreKey(group), -1, "cold");
        redis.zadd(scoreKey(group), due + 1, "changed");
        var commands = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var listener = new io.lettuce.core.event.command.CommandListener() {
            @Override public void commandStarted(io.lettuce.core.event.command.CommandStartedEvent event) {
                commands.add(event.getCommand().getType().toString());
            }
        };
        Map<String, WorkerScoreTransitionResult> results;
        redisClient.addListener(listener);
        try {
            results = scoreCore.deferObservedToRecovery(group, observations, 15_037);
        } finally {
            redisClient.removeListener(listener);
        }
        long after = redisTimeMillis();
        assertThat(commands).containsExactly("EVAL");
        assertThat(results.keySet()).containsExactlyElementsOf(observations.keySet());
        long targetSlot = Math.abs(results.get("w-0").score()) % MARK_BASE;
        assertThat(targetSlot).isBetween((before + 15_037) / 100, (after + 15_037) / 100);
        for (int i = 0; i < 96; i++) {
            var result = results.get("w-" + i);
            assertThat(result.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
            assertThat(result.score()).isEqualTo(workerScore(-1, targetSlot, 0));
            assertThat(redis.zscore(scoreKey(group), "w-" + i)).isEqualTo(result.score().doubleValue());
        }
        for (String id : List.of("invalid", "cold")) {
            assertThat(results.get(id).status()).isEqualTo(WorkerScoreTransitionStatus.INVALID);
            assertThat(results.get(id).score()).isNull();
            assertThat(redis.zscore(scoreKey(group), id)).isEqualTo(observations.get(id).doubleValue());
        }
        assertThat(results.get("missing").status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(results.get("missing").score()).isNull();
        assertThat(redis.zscore(scoreKey(group), "missing")).isNull();
        assertThat(results.get("changed").status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(results.get("changed").score()).isEqualTo(due + 1);
        assertThat(redis.zscore(scoreKey(group), "changed")).isEqualTo((double) due + 1);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, -1})
    void rangeObservationsCannotDeferMembersChangedOrDeletedAfterTheRead(int polarity) {
        String group = "changed-after-observation";
        long now = redisTimeMillis();
        long observed = workerScore(polarity, now / 100 - 100, 0);
        var ids = List.of("deleted", "time-changed", "mark-changed", "polarity-changed", "paused", "unchanged");
        ids.forEach(id -> redis.zadd(scoreKey(group), observed, id));
        var head = polarity == 1
                ? scoreCore.observeHotCandidatesBefore(group, now, 100)
                : scoreCore.observeRecoveryRecheckCandidates(group, 100);
        assertThat(head).hasSize(ids.size());
        var fences = new LinkedHashMap<String, Long>();
        fences.putAll(head);
        redis.zrem(scoreKey(group), "deleted");
        var changed = Map.of(
                "time-changed", workerScore(polarity, now / 100 - 50, 0),
                "mark-changed", workerScore(polarity, now / 100 - 100, 1),
                "polarity-changed", -observed,
                "paused", workerScore(polarity, PAUSE_TIME_SLOT, 0));
        changed.forEach((id, score) -> redis.zadd(scoreKey(group), score, id));

        var results = scoreCore.deferObservedToRecovery(group, fences, 15_000);

        assertThat(results.get("unchanged").status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(results.get("deleted").status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(results.get("deleted").score()).isNull();
        assertThat(redis.zscore(scoreKey(group), "deleted")).isNull();
        changed.forEach((id, score) -> {
            assertThat(results.get(id).status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
            assertThat(results.get(id).score()).isEqualTo(score);
            assertThat(redis.zscore(scoreKey(group), id)).isEqualTo(score.doubleValue());
        });
    }

    @Test
    void recheckChecksExactBeforeAnInvalidExecutionTargetAndTargetBeforeDue() {
        String group = "recheck-result-order";
        long now = redisTimeMillis();
        long due = workerScore(1, now / 100 - 100, 0);
        long future = workerScore(-1, now / 100 + 1_000, 1);
        redis.zadd(scoreKey(group), due, "due");
        redis.zadd(scoreKey(group), future, "future");
        redis.zadd(scoreKey(group), due + 1, "changed");

        var results = scoreCore.deferObservedToRecovery(group,
                Map.of("due", due, "future", future, "changed", due, "missing", due),
                PAUSE_TIME_MILLIS - now + 1_000);

        assertThat(results.get("missing").status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(results.get("missing").score()).isNull();
        assertThat(results.get("changed").status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(results.get("changed").score()).isEqualTo(due + 1);
        for (var entry : Map.of("due", due, "future", future).entrySet()) {
            assertThat(results.get(entry.getKey()).status()).isEqualTo(WorkerScoreTransitionStatus.INVALID);
            assertThat(results.get(entry.getKey()).score()).isEqualTo(entry.getValue());
        }
        assertThat(redis.zscore(scoreKey(group), "due")).isEqualTo((double) due);
        assertThat(redis.zscore(scoreKey(group), "future")).isEqualTo((double) future);
        assertThat(redis.zscore(scoreKey(group), "changed")).isEqualTo((double) due + 1);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void hotLeaseRequestExpiryPrecedesMissingOrChangedExactFences(boolean confirm) {
        String group = "lease-rejection-order";
        long now = redisTimeMillis();
        long observed = workerScore(1, now / 100 + (confirm ? 1_000 : -100), 0);
        redis.zadd(scoreKey(group), observed + 1, "changed");
        var fences = Map.of("changed", observed, "missing", observed);

        var results = confirm
                ? scoreCore.acquireObservedHotScoreLeases(group, fences, now - 1_000)
                : scoreCore.acquireObservedHotScoreLeases(group, fences, now - 1_000);

        assertThat(results.values()).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(WorkerScoreTransitionStatus.INVALID);
            assertThat(result.score()).isNull();
        });
        assertThat(redis.zscore(scoreKey(group), "changed")).isEqualTo((double) observed + 1);
        assertThat(redis.zscore(scoreKey(group), "missing")).isNull();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, -1})
    void serviceabilityFractionalRangeRowsKeepTheirExceptionAndDoNotReadReplacements(int polarity) {
        String group = "fractional-range";
        long now = redisTimeMillis();
        double corrupt = workerScore(polarity, now / 100 - 100, 0) + 0.5;
        redis.zadd(scoreKey(group), corrupt, "corrupt");
        redis.zadd(scoreKey(group), corrupt - 1.5, "unread");
        var commands = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var listener = new io.lettuce.core.event.command.CommandListener() {
            @Override public void commandStarted(io.lettuce.core.event.command.CommandStartedEvent event) {
                commands.add(event.getCommand().getType().toString());
            }
        };
        redisClient.addListener(listener);
        try {
            assertThatThrownBy(() -> {
                if (polarity == 1) scoreCore.observeHotCandidatesBefore(group, now, 1);
                else scoreCore.observeRecoveryRecheckCandidates(group, 1);
            }).isInstanceOf(IllegalStateException.class);
        } finally {
            redisClient.removeListener(listener);
        }
        if (polarity == 1) assertThat(commands).containsExactly("ZREVRANGEBYSCORE", "ZREVRANGEBYSCORE");
        else assertThat(commands).containsExactly("TIME", "ZREVRANGEBYSCORE", "ZREVRANGEBYSCORE");
        assertThat(redis.zscore(scoreKey(group), "corrupt")).isEqualTo(corrupt);
    }

    private static WorkerGroupDescriptor group(
            String workerGroupId,
            Map<String, Object> attributes,
            Set<String> eventCodes
    ) {
        return new WorkerGroupDescriptor(
                workerGroupId,
                attributes,
                eventCodes
        );
    }

    private static WorkerDescriptor worker(
            String workerId,
            String workerGroupId,
            String endpointManagerId
    ) {
        return new WorkerDescriptor(
                workerId,
                workerGroupId,
                endpointManagerId
        );
    }

    private static String bindingJson(String group, String endpoint) {
        return com.xa.mass.workerdelivery.json.Jsons.toJson(Map.of(
                "workerGroupId", group, "endpointManagerId", endpoint));
    }

    private String groupsKey() { return keyspace.base() + ":worker:groups"; }
    private String bindingsKey() { return keyspace.base() + ":worker:bindings"; }

    private String scoreKey(String workerGroupId) {
        return keyspace.base() + ":worker:score:" + workerGroupId;
    }

    private long redisTimeMillis() {
        List<String> parts = redis.time();
        return Long.parseLong(parts.get(0)) * 1_000
                + Long.parseLong(parts.get(1)) / 1_000;
    }

    private static long workerScore(
            int polarity,
            long timeSlot,
            int mark
    ) {
        long absoluteScore = timeSlot + mark * MARK_BASE;
        return polarity * absoluteScore;
    }

    private static void assertScoreShape(
            WorkerScoreState state,
            WorkerScorePolarity polarity,
            long timeMillis,
            int mark
    ) {
        assertThat(state).isNotNull();
        assertThat(state.polarity()).isEqualTo(polarity);
        assertThat(state.timeMillis()).isEqualTo(timeMillis);
        assertThat(state.mark()).isEqualTo(mark);
    }
}
