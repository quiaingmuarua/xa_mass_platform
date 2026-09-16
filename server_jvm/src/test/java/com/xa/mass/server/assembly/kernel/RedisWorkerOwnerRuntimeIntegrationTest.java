package com.xa.mass.server.assembly.kernel;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreDelayTarget;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
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
                .containsOnly(-200.0);
        assertThat(catalog.registerWorkers("group-1", ids, "new-default").values()).allSatisfy(result -> {
            assertThat(result.status()).isEqualTo(RegistrationStatus.NOOP);
            assertThat(result.endpointManagerId()).isEqualTo("endpoint-1");
        });
        assertThat(scoreCore.observeDueHotScoreCandidates("group-1", null, 100)).isEmpty();
        assertThat(scoreCore.acquireHotCandidatesBefore("group-1", redisTimeMillis(), 100)).isEmpty();
        assertThat(scoreCore.acquireRecoveryRecheckCandidates("group-1", 100)).isEmpty();
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
        assertThat(redis.zscore(scoreKey("g"), "w")).isEqualTo(committed ? -200.0 : null);
        var retry = catalog.registerWorkers("g", List.of("w"), "different-default").get("w");
        assertThat(retry.status()).isEqualTo(committed ? RegistrationStatus.NOOP : RegistrationStatus.OK);
        assertThat(retry.endpointManagerId()).isEqualTo("endpoint");
        assertThat(redis.zscore(scoreKey("g"), "w")).isEqualTo(-200.0);
        redis.hdel(bindingsKey(), "w");
        assertThat(catalog.registerWorkers("g", List.of("w"), "endpoint").get("w").status())
                .isEqualTo(RegistrationStatus.OK);
        assertThat(redis.zscore(scoreKey("g"), "w")).isEqualTo(-200.0);
    }

    @Test
    void registrationNxPreservesEveryScoreShapeIncludingInvalidAndPaused() {
        catalog.registerWorkerGroup(group("g", Map.of(), Set.of()));
        long now = redisTimeMillis() / WorkerScoreCore.SLOT_MILLIS;
        long[] shapes = {0, -200, -201, 1, -1,
                workerScore(1, now, 5, 0), workerScore(1, now, 5, 1),
                workerScore(-1, now, 5, 0), workerScore(-1, now, 5, 1),
                workerScore(1, now + 600, 9, 1), workerScore(-1, now + 600, 9, 1),
                workerScore(1, WorkerScoreCore.PAUSE_TIME_SLOT, 0, 0),
                workerScore(-1, WorkerScoreCore.PAUSE_TIME_SLOT, 99, 1)};
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
        assertThatThrownBy(() -> catalog.getWorkerDescriptors(tooMany)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.getWorkerDescriptorsAsync(tooMany)).isInstanceOf(IllegalArgumentException.class);
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
        assertThatThrownBy(() -> catalog.getWorkerDescriptors(ids.subList(0, 101)))
                .isInstanceOf(IllegalArgumentException.class);
        for (int limit : List.of(0, 1001)) {
            assertThatThrownBy(() -> catalog.sampleWorkerDescriptors("preview-group", limit))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> scoreCore.sampleRegisteredWorkerIds("preview-group", limit))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"adapter", "system-polling"})
    void networkMechanismRequiresBindingAndPreservesLeaseDirtyAndPause(String endpoint) {
        catalog.registerWorkerGroup(group("g", Map.of(), Set.of()));
        catalog.registerWorkers("g", List.of("cold", "lease", "pause", "binding-only"), endpoint);
        redis.zrem(scoreKey("g"), "binding-only");
        long now = redisTimeMillis() / WorkerScoreCore.SLOT_MILLIS;
        long lease = workerScore(-1, now + 600, 5, 1);
        long pause = workerScore(-1, WorkerScoreCore.PAUSE_TIME_SLOT, 9, 1);
        redis.zadd(scoreKey("g"), lease, "lease");
        redis.zadd(scoreKey("g"), pause, "pause");
        var events = new com.xa.mass.kernel.worker.DefaultWorkerServiceabilityEvents(catalog, scoreCore);
        var wrong = new com.xa.mass.kernel.worker.WorkerServiceabilityEvents.NetworkObservation("wrong", now * 100);
        events.onAvailable(Map.of("cold", wrong, "lease", wrong, "pause", wrong));
        assertThat(redis.zscore(scoreKey("g"), "cold")).isEqualTo(-200.0);
        assertThat(redis.zscore(scoreKey("g"), "lease")).isEqualTo((double) lease);

        // No retained first observation exists. A later actual observation alone activates.
        var valid = new com.xa.mass.kernel.worker.WorkerServiceabilityEvents.NetworkObservation(endpoint, now * 100);
        events.onAvailable(Map.of("cold", valid, "lease", valid, "pause", valid,
                "binding-only", valid, "missing", valid));
        assertThat(redis.zscore(scoreKey("g"), "cold")).isEqualTo((double) workerScore(1, now, 0, 0));
        assertThat(redis.zscore(scoreKey("g"), "lease")).isEqualTo((double) -lease);
        assertThat(redis.zscore(scoreKey("g"), "pause")).isEqualTo((double) -pause);
        assertThat(redis.zscore(scoreKey("g"), "binding-only")).isNull();
        assertThat(redis.zscore(scoreKey("g"), "missing")).isNull();
    }

    @Test
    void workerScorePauseAndReleasePreserveOwnerShape() {
        long currentSlot = redisTimeMillis() / WorkerScoreCore.SLOT_MILLIS;
        long hotScore = workerScore(
                WorkerScoreCore.HOT_ACQUIRE_POLARITY,
                currentSlot,
                7,
                1
        );
        long recoveryScore = workerScore(
                WorkerScoreCore.RECOVERY_RECHECK_POLARITY,
                currentSlot,
                43,
                0
        );
        redis.zadd(scoreKey("group-1"), hotScore, "hot-worker");
        redis.zadd(
                scoreKey("group-1"),
                recoveryScore,
                "recovery-worker"
        );

        var paused = scoreCore.rewriteCurrentScores(
                "group-1",
                List.of("hot-worker", "recovery-worker", "missing-worker"),
                WorkerScoreCore.PAUSE_TIME_MILLIS,
                null
        );

        assertThat(paused.keySet()).containsExactly(
                "hot-worker",
                "recovery-worker",
                "missing-worker"
        );
        assertThat(paused.get("hot-worker").status())
                .isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(paused.get("recovery-worker").status())
                .isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(paused.get("missing-worker").status())
                .isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(paused.get("missing-worker").score()).isNull();

        Map<String, WorkerScoreState> pausedStates = scoreCore.getScoreStates(
                "group-1",
                List.of("hot-worker", "recovery-worker")
        );
        assertScoreShape(
                pausedStates.get("hot-worker"),
                WorkerScorePolarity.HOT_ACQUIRE,
                WorkerScoreCore.PAUSE_TIME_MILLIS,
                7,
                1
        );
        assertScoreShape(
                pausedStates.get("recovery-worker"),
                WorkerScorePolarity.RECOVERY_RECHECK,
                WorkerScoreCore.PAUSE_TIME_MILLIS,
                43,
                0
        );

        var repeatedPause = scoreCore.rewriteCurrentScores(
                "group-1",
                List.of("hot-worker", "recovery-worker"),
                WorkerScoreCore.PAUSE_TIME_MILLIS,
                null
        );
        assertThat(repeatedPause.values()).allSatisfy(result -> {
            assertThat(result.status())
                    .isEqualTo(WorkerScoreTransitionStatus.STALE);
            assertThat(result.score()).isNotNull();
        });

        long releaseTimeMillis = redisTimeMillis()
                + WorkerScoreCore.SLOT_MILLIS;
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
                / WorkerScoreCore.SLOT_MILLIS
                * WorkerScoreCore.SLOT_MILLIS;
        Map<String, WorkerScoreState> releasedStates = scoreCore.getScoreStates(
                "group-1",
                List.of("hot-worker", "recovery-worker")
        );
        assertScoreShape(
                releasedStates.get("hot-worker"),
                WorkerScorePolarity.HOT_ACQUIRE,
                releasedTimeMillis,
                7,
                1
        );
        assertScoreShape(
                releasedStates.get("recovery-worker"),
                WorkerScorePolarity.RECOVERY_RECHECK,
                releasedTimeMillis,
                43,
                0
        );
    }

    @Test
    void workerScoreReleaseUsesExactCasAndKeepsBatchResultsIndependent() {
        long firstObserved = workerScore(
                WorkerScoreCore.HOT_ACQUIRE_POLARITY,
                WorkerScoreCore.PAUSE_TIME_SLOT,
                3,
                0
        );
        long secondObserved = workerScore(
                WorkerScoreCore.RECOVERY_RECHECK_POLARITY,
                WorkerScoreCore.PAUSE_TIME_SLOT,
                4,
                0
        );
        long secondCurrent = workerScore(
                WorkerScoreCore.RECOVERY_RECHECK_POLARITY,
                WorkerScoreCore.PAUSE_TIME_SLOT,
                4,
                1
        );
        redis.zadd(scoreKey("group-1"), firstObserved, "first-worker");
        redis.zadd(scoreKey("group-1"), secondCurrent, "second-worker");
        long ordinaryObserved = workerScore(
                WorkerScoreCore.HOT_ACQUIRE_POLARITY,
                redisTimeMillis() / WorkerScoreCore.SLOT_MILLIS,
                1,
                0
        );
        long releaseTimeMillis = redisTimeMillis()
                + WorkerScoreCore.SLOT_MILLIS;
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
                releaseTimeMillis + WorkerScoreCore.SLOT_MILLIS
        );
        assertThat(staleRetry.get("first-worker").status())
                .isEqualTo(WorkerScoreTransitionStatus.STALE);

        long currentFirst = scoreCore.getScoreStates(
                "group-1",
                List.of("first-worker")
        ).get("first-worker").score();
        assertThat(scoreCore.releaseScoreHolds(
                "group-1",
                Map.of("first-worker", currentFirst),
                redisTimeMillis() - WorkerScoreCore.SLOT_MILLIS
        ).get("first-worker").status()).isEqualTo(
                WorkerScoreTransitionStatus.INVALID
        );
        assertThat(scoreCore.releaseScoreHolds(
                "group-1",
                Map.of(
                        "pause-base-worker",
                        workerScore(
                                WorkerScoreCore.HOT_ACQUIRE_POLARITY,
                                WorkerScoreCore.PAUSE_TIME_SLOT,
                                WorkerScoreCore.MIN_LANE_RANK,
                                WorkerScoreCore.MIN_DIRTY
                        )
                ),
                WorkerScoreCore.PAUSE_TIME_MILLIS
        ).get("pause-base-worker").status()).isEqualTo(
                WorkerScoreTransitionStatus.INVALID
        );
    }

    @Test
    void completedHotReleaseRepairsOnlyTheExactRecoveryCounterpart() {
        long leaseSlot = redisTimeMillis()
                / WorkerScoreCore.SLOT_MILLIS
                + 100;
        long observedHot = workerScore(
                WorkerScoreCore.HOT_ACQUIRE_POLARITY,
                leaseSlot,
                7,
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
                recoveryCounterpart - WorkerScoreCore.SLOT_FACTOR,
                "drifted-worker"
        );

        long releaseTime = redisTimeMillis()
                + WorkerScoreCore.SLOT_MILLIS;
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
        Map<String, WorkerScoreState> states = scoreCore.getScoreStates(
                "group-1",
                List.of("positive-worker", "recovery-worker")
        );
        assertThat(states.get("positive-worker").polarity()).isEqualTo(
                WorkerScorePolarity.HOT_ACQUIRE
        );
        assertThat(states.get("positive-worker").laneRank()).isEqualTo(7);
        assertThat(states.get("recovery-worker").polarity()).isEqualTo(
                WorkerScorePolarity.HOT_ACQUIRE
        );
        assertThat(states.get("recovery-worker").laneRank()).isEqualTo(7);
        assertThat(states.get("recovery-worker").dirty()).isEqualTo(1);
    }

    @Test
    void activeConfirmationAcceptsOnlyExactCleanHotFences() {
        long slot = redisTimeMillis() / 100 + 100;
        long active = workerScore(1, slot, 7, 0);
        String group = "group-lease-confirm";
        redis.zadd(scoreKey(group), active, "active");
        redis.zadd(scoreKey(group), active + 1, "dirty");
        redis.zadd(scoreKey(group), -active, "recovery");
        redis.zadd(scoreKey(group), active + 200, "different-slot");
        var states = scoreCore.getScoreStates(group,
                List.of("active", "dirty", "recovery", "different-slot", "missing"));
        assertThat(states.get("active").score()).isEqualTo(active);
        assertThat(states.get("missing")).isNull();
        var results = scoreCore.confirmActiveHotScoreLeases(group, Map.of(
                "active", active, "dirty", states.get("dirty").score(),
                "recovery", states.get("recovery").score(), "different-slot", active,
                "missing", active), slot * 100);
        assertThat(results.get("active").status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(results.get("active").score()).isEqualTo(active + 1);
        assertThat(results.get("recovery").status()).isEqualTo(WorkerScoreTransitionStatus.INVALID);
        for (String id : List.of("dirty", "different-slot", "missing")) {
            assertThat(results.get(id).status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
        }
        assertThat(scoreCore.confirmActiveHotScoreLeases(group, Map.of("active", active), slot * 100)
                .get("active").status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
    }

    @Test
    void readOnlyHeadRetainsScoreAndMemberOrderWithinOneCommand() {
        long due = workerScore(1, redisTimeMillis() / 100 - 100, 7, 1);
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
        long eligible = workerScore(1, slot - 10, 7, 0);
        redis.zadd(scoreKey("g"), eligible, "clean");
        redis.zadd(scoreKey("g"), eligible + 0.25, "corrupt");
        redis.zadd(scoreKey("g"), eligible + 1, "dirty");
        assertThat(scoreCore.observeDueHotScoreCandidates("g", null, 2))
                .containsExactly(Map.entry("clean", eligible));
        assertThat(scoreCore.observeDueHotScoreCandidates("g", null, 3))
                .containsExactly(Map.entry("clean", eligible), Map.entry("dirty", eligible + 1));
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
        for (int limit : new int[]{0, 101}) {
            assertThatThrownBy(() -> scoreCore.observeDueHotScoreCandidates("g", null, limit))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(scoreCore.observeDueHotScoreCandidates("g", -1L, 100)).isEmpty();
        assertThat(scoreCore.observeDueHotScoreCandidates("g", WorkerScoreCore.MAX_TIME_MILLIS + 1, 100)).isEmpty();
    }

    @Test
    void observationHonorsFloorAndExcludesTheCurrentSlotFuturePauseAndRecovery() {
        scoreCore.observeDueHotScoreCandidates("g", null, 100);
        for (int attempt = 0; attempt < 8; attempt++) {
            long slot = redisTimeMillis() / 100;
            long eligible = workerScore(1, slot - 10, 7, 0);
            redis.zadd(scoreKey("g"), eligible, "clean");
            redis.zadd(scoreKey("g"), eligible + 1, "dirty");
            redis.zadd(scoreKey("g"), workerScore(1, slot - 20, 7, 0), "below-floor");
            redis.zadd(scoreKey("g"), -eligible, "recovery");
            redis.zadd(scoreKey("g"), workerScore(1, slot, 0, 0), "current");
            redis.zadd(scoreKey("g"), workerScore(1, slot + 100, 7, 0), "occupied");
            redis.zadd(scoreKey("g"), workerScore(1, WorkerScoreCore.PAUSE_TIME_SLOT, 7, 0), "pause");
            var observed = scoreCore.observeDueHotScoreCandidates("g", (slot - 10) * 100, 100);
            if (redisTimeMillis() / 100 != slot) continue; // Resample only a missed time window.
            assertThat(observed).containsExactly(Map.entry("clean", eligible), Map.entry("dirty", eligible + 1));
            assertThat(scoreCore.observeDueHotScoreCandidates("g", (slot + 1000) * 100, 100)).isEmpty();
            return;
        }
        throw new AssertionError("Could not observe the current Redis slot within eight attempts");
    }

    @Test
    void acquiredWorkersLeaveTheNextObservationRangeWithoutRelease() {
        String groupId = "group-match-hold";
        long dueSlot = redisTimeMillis() / WorkerScoreCore.SLOT_MILLIS - 20;
        long dueScore = workerScore(
                WorkerScoreCore.HOT_ACQUIRE_POLARITY,
                dueSlot,
                WorkerScoreCore.MIN_LANE_RANK,
                WorkerScoreCore.MIN_DIRTY
        );
        List<String> workerIds = java.util.stream.IntStream.range(0, 250)
                .mapToObj(index -> "worker-%03d".formatted(index))
                .toList();
        workerIds.forEach(workerId ->
                redis.zadd(scoreKey(groupId), dueScore, workerId));
        long holdUntilMillis = (
                redisTimeMillis() / WorkerScoreCore.SLOT_MILLIS + 100
        ) * WorkerScoreCore.SLOT_MILLIS;

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
    void expiredCandidateLeaseRetainsItsNewPositionBehindOlderUnacquiredWorkers() throws Exception {
        long slot = redisTimeMillis() / 100;
        long first = workerScore(1, slot - 20, 7, 1);
        long olderUnacquired = workerScore(1, slot - 10, 7, 0);
        redis.zadd(scoreKey("g"), first, "first");
        redis.zadd(scoreKey("g"), olderUnacquired, "waiting");
        var observed = scoreCore.observeDueHotScoreCandidates("g", null, 1);
        assertThat(observed).containsExactly(Map.entry("first", first));
        long until = redisTimeMillis() + 1000;
        var acquired = scoreCore.acquireObservedHotScoreLeases("g", observed, until).get("first");
        assertThat(acquired.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        awaitRedisTime((until / 100 + 1) * 100);
        assertThat(scoreCore.observeDueHotScoreCandidates("g", null, 2))
                .containsExactly(Map.entry("waiting", olderUnacquired), Map.entry("first", acquired.score()));
        assertThat(redis.zscore(scoreKey("g"), "first")).isEqualTo(acquired.score().doubleValue());
    }

    @Test
    void serviceabilityHoldsAndEvidenceUseExactBatchFences() {
        long beforeSlot = redisTimeMillis() / WorkerScoreCore.SLOT_MILLIS;
        long hot = workerScore(
                WorkerScoreCore.HOT_ACQUIRE_POLARITY,
                beforeSlot - 20,
                7,
                1
        );
        long recovery = workerScore(
                WorkerScoreCore.RECOVERY_RECHECK_POLARITY,
                beforeSlot - 20,
                2,
                1
        );
        long exhausted = workerScore(
                WorkerScoreCore.RECOVERY_RECHECK_POLARITY,
                beforeSlot - 20,
                WorkerScoreCore.MAX_LANE_RANK,
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
                exhausted,
                "exhausted"
        );

        var hotResults = scoreCore
                .deferObservedToRecovery(
                        "group-serviceability",
                        Map.of(
                                "hot", new WorkerScoreDelayTarget(hot, 60_000, 0),
                                "stale", new WorkerScoreDelayTarget(hot, 60_000, 0)
                        )
                );
        var recoveryResults = scoreCore.deferObservedToRecovery(
                "group-serviceability",
                Map.of(
                        "recovery", new WorkerScoreDelayTarget(recovery, 240_000, 3),
                        "exhausted", new WorkerScoreDelayTarget(exhausted, 240_000, 100)
                )
        );

        assertThat(hotResults.get("hot").status()).isEqualTo(
                WorkerScoreTransitionStatus.TRANSITIONED
        );
        assertThat(hotResults.get("stale").status()).isEqualTo(
                WorkerScoreTransitionStatus.STALE
        );
        assertThat(recoveryResults.get("recovery").status()).isEqualTo(
                WorkerScoreTransitionStatus.TRANSITIONED
        );
        assertThat(recoveryResults.get("exhausted").status()).isEqualTo(
                WorkerScoreTransitionStatus.INVALID
        );

        Map<String, WorkerScoreState> held = scoreCore.getScoreStates(
                "group-serviceability",
                List.of("hot", "recovery", "exhausted")
        );
        long afterSlot = redisTimeMillis() / WorkerScoreCore.SLOT_MILLIS;
        assertThat(held.get("hot").timeMillis()
                / WorkerScoreCore.SLOT_MILLIS).isBetween(
                        beforeSlot + 600,
                        afterSlot + 600
                );
        assertThat(held.get("recovery").timeMillis()
                / WorkerScoreCore.SLOT_MILLIS).isBetween(
                        beforeSlot + 2400,
                        afterSlot + 2400
                );
        assertScoreShape(
                held.get("hot"),
                WorkerScorePolarity.RECOVERY_RECHECK,
                held.get("hot").timeMillis(),
                0,
                1
        );
        assertScoreShape(
                held.get("recovery"),
                WorkerScorePolarity.RECOVERY_RECHECK,
                held.get("recovery").timeMillis(),
                3,
                1
        );
        assertThat(held.get("exhausted").score()).isEqualTo(exhausted);

        long currentSlot = redisTimeMillis() / WorkerScoreCore.SLOT_MILLIS;
        long newer = workerScore(
                WorkerScoreCore.HOT_ACQUIRE_POLARITY,
                currentSlot - 1,
                4,
                1
        );
        long future = workerScore(
                WorkerScoreCore.HOT_ACQUIRE_POLARITY,
                currentSlot + 100,
                5,
                1
        );
        long pause = workerScore(
                WorkerScoreCore.HOT_ACQUIRE_POLARITY,
                WorkerScoreCore.PAUSE_TIME_SLOT,
                6,
                1
        );
        long oldHot = workerScore(
                WorkerScoreCore.HOT_ACQUIRE_POLARITY,
                currentSlot - 100,
                8,
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
                (currentSlot - 2) * WorkerScoreCore.SLOT_MILLIS
        );
        evidence.put(
                "future",
                (currentSlot - 100) * WorkerScoreCore.SLOT_MILLIS
        );
        evidence.put(
                "pause",
                (currentSlot - 100) * WorkerScoreCore.SLOT_MILLIS
        );
        evidence.put(
                "old-hot",
                currentSlot * WorkerScoreCore.SLOT_MILLIS
        );
        evidence.put("missing", currentSlot * WorkerScoreCore.SLOT_MILLIS);
        var evidenceResults = scoreCore.rewriteCurrentPolarityWithinTimeFence(
                "group-serviceability",
                evidence,
                WorkerScorePolarity.HOT_ACQUIRE, true
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
                        (currentSlot - 100) * WorkerScoreCore.SLOT_MILLIS,
                        "pause",
                        (currentSlot - 100) * WorkerScoreCore.SLOT_MILLIS
                ),
                WorkerScorePolarity.RECOVERY_RECHECK, false
        );
        assertThat(unavailable.values()).allSatisfy(result ->
                assertThat(result.status()).isEqualTo(
                        WorkerScoreTransitionStatus.TRANSITIONED
                ));
        Map<String, WorkerScoreState> finalStates = scoreCore.getScoreStates(
                "group-serviceability",
                List.of("hot", "future", "pause", "old-hot")
        );
        assertScoreShape(
                finalStates.get("hot"),
                WorkerScorePolarity.HOT_ACQUIRE,
                held.get("hot").timeMillis(),
                0,
                1
        );
        assertScoreShape(
                finalStates.get("future"),
                WorkerScorePolarity.RECOVERY_RECHECK,
                (currentSlot + 100) * WorkerScoreCore.SLOT_MILLIS,
                5,
                1
        );
        assertScoreShape(
                finalStates.get("pause"),
                WorkerScorePolarity.RECOVERY_RECHECK,
                WorkerScoreCore.PAUSE_TIME_MILLIS,
                6,
                1
        );
        assertScoreShape(
                finalStates.get("old-hot"),
                WorkerScorePolarity.HOT_ACQUIRE,
                currentSlot * WorkerScoreCore.SLOT_MILLIS,
                8,
                1
        );
    }

    @org.junit.jupiter.api.RepeatedTest(3)
    void serviceabilityDisconnectRemainsValidInTheConfirmableLeaseSlot() throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            long now = redisTimeMillis();
            long target = (now / 100 + 10) * 100;
            long due = workerScore(1, now / 100 - 2, 7, 0);
            var observed = Map.of("early", due, "boundary", due, "confirm", due);
            observed.forEach((id, score) -> redis.zadd(scoreKey("g"), score, id));
            var held = scoreCore.acquireObservedHotScoreLeases("g", observed, target);
            assertThat(transitionedScores(held)).hasSize(3);
            long evidence = target - 100;

            awaitRedisTime(target - 60);
            long earlyBefore = redisTimeMillis();
            var early = scoreCore.rewriteCurrentPolarityWithinTimeFence("g", Map.of("early", evidence),
                    WorkerScorePolarity.RECOVERY_RECHECK, false).get("early");
            long earlyAfter = redisTimeMillis();
            awaitRedisTime(target + 2);
            long before = redisTimeMillis();
            var disconnected = scoreCore.rewriteCurrentPolarityWithinTimeFence("g", Map.of("boundary", evidence),
                    WorkerScorePolarity.RECOVERY_RECHECK, false).get("boundary");
            var confirmations = scoreCore.confirmActiveHotScoreLeases("g", Map.of(
                    "boundary", held.get("boundary").score(), "confirm", held.get("confirm").score()), target + 5_000);
            long after = redisTimeMillis();
            // Resample only a missed timing window, never an unexpected transition result.
            if (earlyBefore / 100 != target / 100 - 1 || earlyAfter / 100 != target / 100 - 1
                    || before / 100 != target / 100 || after / 100 != target / 100) continue;

            assertThat(early.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
            assertThat(disconnected.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
            assertThat(disconnected.score()).isEqualTo(-held.get("boundary").score());
            assertThat(confirmations.get("boundary").status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
            assertThat(confirmations.get("confirm").status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);

            long execution = confirmations.get("confirm").score();
            var laterDisconnect = scoreCore.rewriteCurrentPolarityWithinTimeFence("g", Map.of("confirm", evidence),
                    WorkerScorePolarity.RECOVERY_RECHECK, false).get("confirm");
            assertThat(laterDisconnect.score()).isEqualTo(-execution);
            assertThat(Math.abs(laterDisconnect.score()) % 2).isEqualTo(1);
            var released = scoreCore.releaseObservedHotScoreHolds("g", Map.of("confirm", execution),
                    target + 500).get("confirm");
            assertThat(released.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
            assertThat(released.score()).isEqualTo(workerScore(1, target / 100 + 5, 7, 1));
            return;
        }
        throw new AssertionError("Could not observe the lease boundary within eight Redis-timed attempts");
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
                    long score = workerScore(-target.value(), slot, index, index % 2);
                    original.put(id, score);
                    evidence.put(id, (slot - 1) * 100);
                    redis.zadd(scoreKey("g"), score, id);
                }
                // Warm the lazy Owner connection before measuring commands and the target slot.
                scoreCore.getScoreStates("g", List.of("worker-0"));
                awaitRedisTime(slot * 100 + 2);
                long before = redisTimeMillis();
                Map<String, WorkerScoreTransitionResult> first, repeated, currentPreserved, reversed;
                var ahead = new LinkedHashMap<>(evidence);
                ahead.replaceAll((id, timestamp) -> (slot + 1) * 100);
                WorkerScorePolarity opposite = target == WorkerScorePolarity.HOT_ACQUIRE
                        ? WorkerScorePolarity.RECOVERY_RECHECK : WorkerScorePolarity.HOT_ACQUIRE;
                commands.clear();
                first = scoreCore.rewriteCurrentPolarityWithinTimeFence("g", evidence, target, target == WorkerScorePolarity.HOT_ACQUIRE);
                repeated = scoreCore.rewriteCurrentPolarityWithinTimeFence("g", evidence, target, target == WorkerScorePolarity.HOT_ACQUIRE);
                // The Owner preserves current coordinates; future-report admission remains upstream.
                currentPreserved = scoreCore.rewriteCurrentPolarityWithinTimeFence("g", ahead, target, target == WorkerScorePolarity.HOT_ACQUIRE);
                reversed = scoreCore.rewriteCurrentPolarityWithinTimeFence("g", evidence, opposite, opposite == WorkerScorePolarity.HOT_ACQUIRE);
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
        long past = workerScore(-target.value(), pastSlot, 9, 1);
        long pause = workerScore(-target.value(), WorkerScoreCore.PAUSE_TIME_SLOT, 9, 1);
        for (String id : List.of("older", "same", "newer")) redis.zadd(scoreKey("g"), past, id);
        redis.zadd(scoreKey("g"), pause, "pause");
        var result = scoreCore.rewriteCurrentPolarityWithinTimeFence("g", Map.of(
                "older", (pastSlot - 1) * 100,
                "same", pastSlot * 100,
                "newer", (pastSlot + 1) * 100,
                "pause", pastSlot * 100,
                "missing", pastSlot * 100), target, target == WorkerScorePolarity.HOT_ACQUIRE);

        assertThat(result.get("older").status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(redis.zscore(scoreKey("g"), "older")).isEqualTo((double) past);
        assertThat(result.get("same").status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(result.get("same").score()).isEqualTo(-past);
        assertThat(result.get("newer").status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(result.get("newer").score()).isEqualTo(workerScore(target.value(),
                target == WorkerScorePolarity.HOT_ACQUIRE ? pastSlot + 1 : pastSlot, 9, 1));
        assertThat(result.get("pause").score()).isEqualTo(-pause);
        assertThat(result.get("missing").status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(redis.zscore(scoreKey("g"), "missing")).isNull();
    }

    @Test
    void serviceabilityCurrentSlotDisconnectAndConfirmationHaveOnlySerializedOutcomes() throws Exception {
        try (var competing = new RedisWorkerScoreCore(redisClient, keyspace);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            competing.getScoreStates("g", List.of("w"));
            for (int attempt = 0; attempt < 8; attempt++) {
                long slot = redisTimeMillis() / 100 + 10;
                long held = workerScore(1, slot, 7, 0);
                redis.zadd(scoreKey("g"), held, "w");
                var start = new CountDownLatch(1);
                var confirmation = executor.submit(() -> {
                    start.await();
                    return competing.confirmActiveHotScoreLeases("g", Map.of("w", held),
                            (slot + 50) * 100).get("w");
                });
                var disconnection = executor.submit(() -> {
                    start.await();
                    return scoreCore.rewriteCurrentPolarityWithinTimeFence("g", Map.of("w", (slot - 1) * 100),
                            WorkerScorePolarity.RECOVERY_RECHECK, false).get("w");
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
        long nowSlot = redisTimeMillis() / WorkerScoreCore.SLOT_MILLIS;
        long hotCutoffMillis = (nowSlot - 20) * WorkerScoreCore.SLOT_MILLIS;
        long hotCutoffSlot = hotCutoffMillis / WorkerScoreCore.SLOT_MILLIS;
        long hotHigher = workerScore(
                WorkerScoreCore.HOT_ACQUIRE_POLARITY,
                hotCutoffSlot - 1,
                0,
                0
        );
        long hotLower = workerScore(
                WorkerScoreCore.HOT_ACQUIRE_POLARITY,
                hotCutoffSlot - 2,
                0,
                0
        );
        redis.zadd(scoreKey("group-range"), hotHigher, "hot-higher");
        redis.zadd(scoreKey("group-range"), hotLower, "hot-lower");
        redis.zadd(scoreKey("group-range"), workerScore(
                WorkerScoreCore.HOT_ACQUIRE_POLARITY,
                hotCutoffSlot,
                0,
                0
        ), "hot-at-floor");

        var firstHot = scoreCore.acquireHotCandidatesBefore(
                "group-range", hotCutoffMillis, 1
        );
        assertThat(scoreCore.acquireHotCandidatesBefore("group-range", hotCutoffMillis, 1))
                .isEqualTo(firstHot);
        assertThat(scoreCore.deferObservedToRecovery("group-range",
                Map.of("hot-higher", new WorkerScoreDelayTarget(hotHigher, 60_000, 0)))
                .get("hot-higher").status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        var secondHot = scoreCore.acquireHotCandidatesBefore("group-range", hotCutoffMillis, 2);
        assertThat(firstHot).extracting(
                WorkerScoreCore.WorkerScoreObservation::workerId
        ).containsExactly("hot-higher");
        assertThat(secondHot).extracting(
                WorkerScoreCore.WorkerScoreObservation::workerId
        ).containsExactly("hot-lower");

        long recoveryOlder = workerScore(
                WorkerScoreCore.RECOVERY_RECHECK_POLARITY,
                nowSlot - 100,
                0,
                0
        );
        long recoveryNewer = workerScore(
                WorkerScoreCore.RECOVERY_RECHECK_POLARITY,
                nowSlot - 50,
                0,
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
                WorkerScoreCore.RECOVERY_RECHECK_POLARITY,
                1,
                5,
                0
        ), "cold");

        var firstRecovery = scoreCore.acquireRecoveryRecheckCandidates(
                "group-range", 1
        );
        assertThat(scoreCore.acquireRecoveryRecheckCandidates("group-range", 1))
                .isEqualTo(firstRecovery);
        assertThat(scoreCore.deferObservedToRecovery("group-range",
                Map.of("recovery-older", new WorkerScoreDelayTarget(recoveryOlder, 60_000, 1)))
                .get("recovery-older").status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        var secondRecovery = scoreCore.acquireRecoveryRecheckCandidates("group-range", 2);
        assertThat(firstRecovery).extracting(
                WorkerScoreCore.WorkerScoreObservation::workerId
        ).containsExactly("recovery-older");
        assertThat(secondRecovery).extracting(
                WorkerScoreCore.WorkerScoreObservation::workerId
        ).containsExactly("recovery-newer");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, -1})
    void serviceabilityAdvancesAllEqualScoreWorkersFromTheSameHead(int polarity) {
        long nowSlot = redisTimeMillis() / 100;
        long observed = workerScore(polarity, nowSlot - 100, 2, 1);
        var remaining = new java.util.HashSet<String>();
        for (int i = 0; i < 250; i++) {
            String id = "worker-" + i;
            remaining.add(id);
            redis.zadd(scoreKey("group-recheck-head"), observed, id);
        }
        for (int expectedSize : new int[]{100, 100, 50}) {
            var head = polarity == 1
                    ? scoreCore.acquireHotCandidatesBefore("group-recheck-head", nowSlot * 100, 100)
                    : scoreCore.acquireRecoveryRecheckCandidates("group-recheck-head", 100);
            assertThat(head).hasSize(expectedSize);
            var targets = new LinkedHashMap<String, WorkerScoreDelayTarget>();
            head.forEach(row -> {
                assertThat(remaining.remove(row.workerId())).isTrue();
                assertThat(row.score()).isEqualTo(observed);
                targets.put(row.workerId(), new WorkerScoreDelayTarget(row.score(), 60_000, polarity == 1 ? 0 : 3));
            });
            var results = scoreCore.deferObservedToRecovery("group-recheck-head", targets);
            assertThat(results).hasSize(expectedSize);
            assertThat(results.values()).allSatisfy(result ->
                    assertThat(result.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED));
        }
        assertThat(remaining).isEmpty();
        assertThat(scoreCore.acquireHotCandidatesBefore("group-recheck-head", nowSlot * 100, 100)).isEmpty();
        assertThat(scoreCore.acquireRecoveryRecheckCandidates("group-recheck-head", 100)).isEmpty();
    }

    @Test
    void recoveryReadAndAdvanceRespectTheCurrentSlotAndLookbackFloor() throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            awaitRedisTime((redisTimeMillis() / 100 + 1) * 100);
            long now = redisTimeMillis();
            long slot = now / 100;
            String group = "recheck-boundary-" + attempt;
            long floor = slot - 864_000;
            var scores = Map.of(
                    "before-floor", workerScore(-1, floor - 1, 0, 0),
                    "at-floor", workerScore(-1, floor, 0, 0),
                    "due-high-rank", workerScore(-1, slot - 1, 4, 1),
                    "current", workerScore(-1, slot, 0, 0),
                    "future", workerScore(-1, slot + 100, 0, 0),
                    "pause", workerScore(-1, WorkerScoreCore.PAUSE_TIME_SLOT, 0, 0),
                    "cold", workerScore(-1, 1, 5, 0));
            scores.forEach((id, score) -> redis.zadd(scoreKey(group), score, id));
            var head = scoreCore.acquireRecoveryRecheckCandidates(group, 100);
            var rejected = scoreCore.deferObservedToRecovery(group, Map.of(
                    "current", new WorkerScoreDelayTarget(scores.get("current"), 1_000, 1),
                    "future", new WorkerScoreDelayTarget(scores.get("future"), 1_000, 1),
                    "pause", new WorkerScoreDelayTarget(scores.get("pause"), 1_000, 1),
                    "due-high-rank", new WorkerScoreDelayTarget(scores.get("due-high-rank"),
                            WorkerScoreCore.PAUSE_TIME_MILLIS - now + 100, 5)));
            if (redisTimeMillis() / 100 != slot) continue;
            assertThat(head).extracting(WorkerScoreCore.WorkerScoreObservation::workerId)
                    .containsExactly("at-floor", "due-high-rank");
            for (String id : List.of("current", "future", "pause")) {
                assertThat(rejected.get(id).status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
            }
            assertThat(rejected.get("due-high-rank").status()).isEqualTo(WorkerScoreTransitionStatus.INVALID);
            scores.forEach((id, score) -> assertThat(redis.zscore(scoreKey(group), id)).isEqualTo(score.doubleValue()));
            // No sweep restart is required: the unchanged current-slot member becomes visible once due.
            awaitRedisTime((slot + 1) * 100);
            assertThat(scoreCore.acquireRecoveryRecheckCandidates(group, 100))
                    .extracting(WorkerScoreCore.WorkerScoreObservation::workerId).contains("current");
            return;
        }
        throw new AssertionError("Could not check recheck boundaries within one Redis slot in eight attempts");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, -1})
    void concurrentServiceabilityHoldsHaveOneWinnerAndCannotAdvanceAgainBeforeDue(int polarity)
            throws Exception {
        long observed = workerScore(polarity, redisTimeMillis() / 100 - 10, 2, 1);
        redis.zadd(scoreKey("recheck-race"), observed, "w");
        var target = Map.of("w", new WorkerScoreDelayTarget(observed, 60_000, polarity == 1 ? 0 : 3));
        var start = new CountDownLatch(1);
        try (var competing = new RedisWorkerScoreCore(redisClient, keyspace);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await();
                return scoreCore.deferObservedToRecovery("recheck-race", target);
            });
            var second = executor.submit(() -> {
                start.await();
                return competing.deferObservedToRecovery("recheck-race", target);
            });
            start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS).get("w").status(),
                    second.get(5, TimeUnit.SECONDS).get("w").status()))
                    .containsExactlyInAnyOrder(WorkerScoreTransitionStatus.TRANSITIONED, WorkerScoreTransitionStatus.STALE);
        }
        long held = scoreCore.getScoreStates("recheck-race", List.of("w")).get("w").score();
        assertThat(scoreCore.deferObservedToRecovery("recheck-race",
                Map.of("w", new WorkerScoreDelayTarget(held, 60_000, 1))).get("w").status())
                .isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(redis.zscore(scoreKey("recheck-race"), "w")).isEqualTo((double) held);
        scoreCore.rewriteCurrentPolarityWithinTimeFence("recheck-race", Map.of("w", redisTimeMillis()),
                WorkerScorePolarity.HOT_ACQUIRE, true);
        assertThat(scoreCore.observeDueHotScoreCandidates("recheck-race", null, 100)).isEmpty();
        assertThat(scoreCore.deferObservedToRecovery("recheck-race",
                Map.of("w", new WorkerScoreDelayTarget(-held, 60_000, 0))).get("w").status())
                .isEqualTo(WorkerScoreTransitionStatus.STALE);
    }

    @Test
    void recheckDelayStartsAtRedisExecutionEvenWhenSubmissionIsDelayed() {
        long before = redisTimeMillis();
        long oldRecovery = workerScore(-1, before / 100 - 10, 0, 1);
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
            assertThat(scoreCore.deferObservedToRecovery("recheck-time",
                    Map.of("w", new WorkerScoreDelayTarget(oldRecovery, 60_000, 1))).get("w").status())
                    .isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        } finally {
            measuring.set(false);
            redisClient.removeListener(listener);
        }
        long after = redisTimeMillis();
        var held = scoreCore.getScoreStates("recheck-time", List.of("w")).get("w");
        assertThat(commands).containsExactly("EVAL");
        assertThat(held.timeMillis()).isBetween((before + 300 + 60_000) / 100 * 100,
                (after + 60_000) / 100 * 100);
        assertThat(held.laneRank()).isEqualTo(1);
        assertThat(held.dirty()).isEqualTo(1);
    }

    @Test
    void polarityToggleAndColdParkUseExactObservedScoreCas() {
        long timeSlot = redisTimeMillis() / WorkerScoreCore.SLOT_MILLIS;
        long hot = workerScore(
                WorkerScoreCore.HOT_ACQUIRE_POLARITY,
                timeSlot,
                7,
                1
        );
        long recovery = workerScore(
                WorkerScoreCore.RECOVERY_RECHECK_POLARITY,
                timeSlot,
                3,
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
                scoreCore.getScoreStates(
                        "group-1",
                        List.of("toggle-worker")
                ).get("toggle-worker"),
                WorkerScorePolarity.RECOVERY_RECHECK,
                timeSlot * WorkerScoreCore.SLOT_MILLIS,
                0,
                1
        );
        assertThat(scoreCore.toggleCurrentPolarity(
                "group-1",
                "toggle-worker",
                hot
        ).status()).isEqualTo(WorkerScoreTransitionStatus.STALE);

        var exhausted = scoreCore.parkObservedRecoveryScore(
                "group-1",
                "cold-worker",
                recovery,
                5
        );
        assertThat(exhausted.status()).isEqualTo(
                WorkerScoreTransitionStatus.TRANSITIONED
        );
        assertScoreShape(
                scoreCore.getScoreStates(
                        "group-1",
                        List.of("cold-worker")
                ).get("cold-worker"),
                WorkerScorePolarity.RECOVERY_RECHECK,
                WorkerScoreCore.SLOT_MILLIS,
                5,
                1
        );
        assertThat(scoreCore.parkObservedRecoveryScore(
                "group-1",
                "toggle-worker",
                toggled.score(),
                0
        ).status()).isEqualTo(WorkerScoreTransitionStatus.INVALID);
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
    void dirtyInvalidationUsesOneCommandAndNeverCreatesMembers(int count) {
        List<String> ids = java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> "w" + i).toList();
        var commands = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var listener = new io.lettuce.core.event.command.CommandListener() {
            @Override public void commandStarted(io.lettuce.core.event.command.CommandStartedEvent event) {
                commands.add(event.getCommand().getType().toString());
            }
        };
        redisClient.addListener(listener);
        try {
            scoreCore.initializeRegisteredScores("g", ids);
            commands.clear();
            assertThat(scoreCore.markCurrentLeasesDirty("g", ids).values()).allSatisfy(result -> {
                assertThat(result.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
                assertThat(result.score()).isEqualTo(-201L);
            });
            assertThat(commands).containsExactly("EVAL");
            commands.clear();
            assertThat(scoreCore.markCurrentLeasesDirty("g", ids).values()).allSatisfy(result -> {
                assertThat(result.status()).isEqualTo(WorkerScoreTransitionStatus.NOOP);
                assertThat(result.score()).isEqualTo(-201L);
            });
            assertThat(commands).containsExactly("EVAL");
        } finally {
            redisClient.removeListener(listener);
        }
        assertThat(scoreCore.markCurrentLeasesDirty("g", List.of("missing")).get("missing"))
                .isEqualTo(new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.STALE, null));
        assertThat(redis.zcard(scoreKey("g"))).isEqualTo(count);
    }

    @Test
    void dirtyInvalidationPreservesCoordinatesAndRejectsCorruptScoresPerMember() {
        long now = redisTimeMillis() / WorkerScoreCore.SLOT_MILLIS;
        for (int sign : List.of(-1, 1)) {
            for (long slot : List.of(0L, 1L, now - 1, now + 100, WorkerScoreCore.PAUSE_TIME_SLOT)) {
                for (int dirty : List.of(0, 1)) {
                    long original = workerScore(sign, slot, 43, dirty);
                    redis.zadd(scoreKey("g"), original, "w");
                    var result = scoreCore.markCurrentLeasesDirty("g", List.of("w")).get("w");
                    assertThat(result.status()).isEqualTo(dirty == 0
                            ? WorkerScoreTransitionStatus.TRANSITIONED : WorkerScoreTransitionStatus.NOOP);
                    assertThat(result.score()).isEqualTo(workerScore(sign, slot, 43, 1));
                    assertThat(redis.zscore(scoreKey("g"), "w")).isEqualTo(result.score().doubleValue());
                }
            }
        }
        for (double invalid : List.of(0.0, 200.5, -200.5, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, (double) workerScore(1, WorkerScoreCore.MAX_TIME_SLOT + 1, 0, 0))) {
            redis.zadd(scoreKey("g"), invalid, "bad");
            redis.zadd(scoreKey("g"), 200, "valid");
            var results = scoreCore.markCurrentLeasesDirty("g", List.of("bad", "valid"));
            assertThat(results.get("bad").status()).isEqualTo(WorkerScoreTransitionStatus.INVALID);
            assertThat(redis.zscore(scoreKey("g"), "bad")).isEqualTo(invalid);
            assertThat(results.get("valid").score()).isEqualTo(201L);
        }
    }

    @Test
    void invalidationBeforeConfirmationRejectsBothCachedAndLateMatchingFences() {
        long now = redisTimeMillis();
        long due = workerScore(1, now / WorkerScoreCore.SLOT_MILLIS - 1, 7, 1);
        redis.zadd(scoreKey("g"), due, "w");
        var held = scoreCore.acquireObservedHotScoreLeases("g", Map.of("w", due), now + 30_000).get("w");
        assertThat(held.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(held.score() % 2).isZero();
        scoreCore.markCurrentLeasesDirty("g", List.of("w"));
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThat(scoreCore.confirmActiveHotScoreLeases("g", Map.of("w", held.score()), now + 40_000)
                    .get("w").status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
        }
        assertThat(scoreCore.confirmActiveHotScoreLeases("g", Map.of("w", held.score() + 1), now + 40_000)
                .get("w").status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(redis.zscore(scoreKey("g"), "w")).isEqualTo((double) (held.score() + 1));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void confirmationConsumesEligibilityEvenWhenDeadlineAlreadyCoversClaim(boolean extend) {
        long now = redisTimeMillis();
        long held = workerScore(1, (now + 30_000) / WorkerScoreCore.SLOT_MILLIS, 7, 0);
        long target = now + (extend ? 40_000 : 20_000);
        redis.zadd(scoreKey("g"), held, "w");
        var result = scoreCore.confirmActiveHotScoreLeases("g", Map.of("w", held), target).get("w");
        long execution = workerScore(1, (extend ? target : now + 30_000) / WorkerScoreCore.SLOT_MILLIS, 7, 1);
        assertThat(result).isEqualTo(new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.TRANSITIONED, execution));
        assertThat(scoreCore.confirmActiveHotScoreLeases("g", Map.of("w", held), target).get("w").status())
                .isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(scoreCore.markCurrentLeasesDirty("g", List.of("w")).get("w"))
                .isEqualTo(new WorkerScoreTransitionResult(WorkerScoreTransitionStatus.NOOP, execution));
        long releaseAt = redisTimeMillis() + 500;
        assertThat(scoreCore.releaseObservedHotScoreHolds("g", Map.of("w", held), releaseAt)
                .get("w").status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
        var released = scoreCore.releaseObservedHotScoreHolds("g", Map.of("w", execution), releaseAt).get("w");
        assertThat(released.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(released.score() % 2).isEqualTo(1);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).until(() ->
                !scoreCore.observeDueHotScoreCandidates("g", null, 100).isEmpty());
        assertThat(scoreCore.observeDueHotScoreCandidates("g", null, 10)).containsKey("w");
        var reacquired = scoreCore.acquireObservedHotScoreLeases("g", Map.of("w", released.score()),
                redisTimeMillis() + 30_000).get("w");
        assertThat(reacquired.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(reacquired.score() % 2).isZero();
        assertThat(reacquired.score()).isNotEqualTo(held);
    }

    @Test
    void executionFencePreservesExistingFailureAndSignFlippedSuccessReleaseRules() {
        long now = redisTimeMillis();
        long held = workerScore(1, (now + 30_000) / WorkerScoreCore.SLOT_MILLIS, 7, 0);
        redis.zadd(scoreKey("g"), held, "w");
        long execution = scoreCore.confirmActiveHotScoreLeases("g", Map.of("w", held), now + 20_000).get("w").score();
        redis.zadd(scoreKey("g"), -execution, "w");
        scoreCore.markCurrentLeasesDirty("g", List.of("w"));
        long releaseAt = redisTimeMillis() + 500;
        assertThat(scoreCore.releaseScoreHolds("g", Map.of("w", execution), releaseAt).get("w").status())
                .isEqualTo(WorkerScoreTransitionStatus.STALE);
        var completed = scoreCore.releaseObservedHotScoreHolds("g", Map.of("w", execution), releaseAt).get("w");
        assertThat(completed.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(completed.score()).isPositive();
        assertThat(completed.score() % 2).isEqualTo(1);
        redis.zadd(scoreKey("g"), execution, "w");
        assertThat(scoreCore.releaseScoreHolds("g", Map.of("w", execution), releaseAt).get("w").status())
                .isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
    }

    @Test
    void concurrentConfirmationsHaveOneWinnerAndCannotConfirmPauseOrExpiredHolds() throws Exception {
        long now = redisTimeMillis();
        long held = workerScore(1, (now + 30_000) / WorkerScoreCore.SLOT_MILLIS, 0, 0);
        redis.zadd(scoreKey("g"), held, "w");
        CountDownLatch start = new CountDownLatch(1);
        try (var competing = new RedisWorkerScoreCore(redisClient, keyspace);
             var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await();
                return scoreCore.confirmActiveHotScoreLeases("g", Map.of("w", held), now + 20_000).get("w").status(); });
            var second = executor.submit(() -> { start.await();
                return competing.confirmActiveHotScoreLeases("g", Map.of("w", held), now + 20_000).get("w").status(); });
            start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(WorkerScoreTransitionStatus.TRANSITIONED, WorkerScoreTransitionStatus.STALE);
        }
        long pause = workerScore(1, WorkerScoreCore.PAUSE_TIME_SLOT, 0, 0);
        long expired = workerScore(1, now / WorkerScoreCore.SLOT_MILLIS - 1, 0, 0);
        redis.zadd(scoreKey("g"), pause, "paused");
        redis.zadd(scoreKey("g"), expired, "expired");
        assertThat(scoreCore.confirmActiveHotScoreLeases("g", Map.of("paused", pause, "expired", expired), now + 20_000)
                .values()).allSatisfy(result -> assertThat(result.status()).isEqualTo(WorkerScoreTransitionStatus.STALE));
        assertThat(redis.zscore(scoreKey("g"), "paused")).isEqualTo((double) pause);
    }

    @Test
    void candidateAcquisitionAndExecutionConfirmationUseOneLuaEachAndInvalidateOldFences() {
        long now=redisTimeMillis();
        var due=new LinkedHashMap<String,Long>();
        for(int i=0;i<100;i++) {
            long value=workerScore(1,now/100-10,i%100,i%2);
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
            var candidate=transitionedScores(scoreCore.acquireObservedHotScoreLeases("g",due,now+5000));
            assertThat(candidate).hasSize(100);assertThat(commands).containsExactly("EVAL");commands.clear();
            var execution=transitionedScores(scoreCore.confirmActiveHotScoreLeases("g",candidate,now+3000));
            assertThat(execution).hasSize(100);assertThat(commands).containsExactly("EVAL");
            for(String id:due.keySet()) {
                assertThat(candidate.get(id)%2).isZero();
                assertThat(candidate.get(id)%200).isEqualTo(due.get(id)%200-due.get(id)%2);
                assertThat(execution.get(id)).isEqualTo(candidate.get(id)+1);
            }
            assertThat(scoreCore.acquireObservedHotScoreLeases("g",due,now+6000).values())
                    .allSatisfy(r->assertThat(r.status()).isEqualTo(WorkerScoreTransitionStatus.STALE));
            assertThat(scoreCore.confirmActiveHotScoreLeases("g",candidate,now+6000).values())
                    .allSatisfy(r->assertThat(r.status()).isEqualTo(WorkerScoreTransitionStatus.STALE));
            assertThat(scoreCore.releaseObservedHotScoreHolds("g",Map.of("w0",candidate.get("w0")),now+500).get("w0").status())
                    .isEqualTo(WorkerScoreTransitionStatus.STALE);
            assertThat(scoreCore.releaseObservedHotScoreHolds("g",Map.of("w0",execution.get("w0")),now+500).get("w0").status())
                    .isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        } finally { redisClient.removeListener(listener); }
    }

    private static Map<String,Long> transitionedScores(Map<String,WorkerScoreTransitionResult> results) {
        var scores=new LinkedHashMap<String,Long>();
        results.forEach((id,result)-> {if(result.status()==WorkerScoreTransitionStatus.TRANSITIONED)scores.put(id,result.score());});
        return scores;
    }

    @Test
    void firstAcquisitionAcceptsDueDirtyScoresButRejectsOccupiedPausedOrChangedObservations() {
        long now=redisTimeMillis(),slot=now/100;
        var observed=new LinkedHashMap<String,Long>();
        observed.put("clean",workerScore(1,slot-10,17,0));
        observed.put("dirty",workerScore(1,slot-10,17,1));
        observed.put("pause",workerScore(1,WorkerScoreCore.PAUSE_TIME_SLOT,17,0));
        observed.put("occupied",workerScore(1,slot+100,17,0));
        observed.put("recovery",workerScore(-1,slot-10,17,0));
        observed.put("changed",workerScore(1,slot-10,17,0));
        observed.forEach((id,score)->redis.zadd(scoreKey("g"),score,id));
        redis.zadd(scoreKey("g"),observed.get("changed")+1,"changed");
        observed.put("missing",observed.get("clean"));
        var result=scoreCore.acquireObservedHotScoreLeases("g",observed,now+1000);
        assertThat(transitionedScores(result)).containsOnlyKeys("clean","dirty");
        assertThat(transitionedScores(result).values()).allSatisfy(value->assertThat(value%2).isZero());
        for(String id:List.of("pause","occupied","recovery"))assertThat(redis.zscore(scoreKey("g"),id)).isEqualTo(observed.get(id).doubleValue());
        assertThat(redis.zscore(scoreKey("g"),"missing")).isNull();
    }

    @Test
    void concurrentFirstAcquisitionsOfOneObservationHaveOneWinner() throws Exception {
        long now=redisTimeMillis(),observed=workerScore(1,(now-1000)/100,7,1);
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
    @org.junit.jupiter.params.provider.ValueSource(strings={"acquire","confirm"})
    void leaseTimeIsCheckedAtLuaSubmissionAfterTheCallerCrossesTheSlot(String mode) {
        long now=redisTimeMillis(),deadline=now+300;
        long fence=workerScore(1,(mode.equals("acquire")?now-1000:deadline)/100,7,0);
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
                case "acquire" -> scoreCore.acquireObservedHotScoreLeases("g",Map.of("w",fence),deadline).get("w");
                default -> scoreCore.confirmActiveHotScoreLeases("g",Map.of("w",fence),now+5000).get("w");
            };
        } finally {redisClient.removeListener(listener);}
        assertThat(result.status()).isEqualTo(mode.equals("acquire")?WorkerScoreTransitionStatus.INVALID:WorkerScoreTransitionStatus.STALE);
        assertThat(redis.zscore(scoreKey("g"),"w")).isEqualTo((double)fence);
    }

    @Test
    void existingRawScoreEchoesStillExposeCorruptFractionalMembersWithoutWriting() {
        long slot = redisTimeMillis() / 100;
        long observation = workerScore(1, slot - 10, 7, 0);
        double corrupt = observation + 0.5;
        redis.zadd(scoreKey("corrupt-echo"), corrupt, "w");
        assertThatThrownBy(() -> scoreCore.deferObservedToRecovery("corrupt-echo",
                Map.of("w", new WorkerScoreDelayTarget(observation, 1_000, 0))))
                .isInstanceOf(IllegalStateException.class).hasMessage("Worker score must be an integer");
        assertThatThrownBy(() -> scoreCore.rewriteCurrentPolarityWithinTimeFence("corrupt-echo",
                Map.of("w", (slot - 5) * 100), WorkerScorePolarity.HOT_ACQUIRE, false))
                .isInstanceOf(IllegalStateException.class).hasMessage("Worker score must be an integer");
        assertThatThrownBy(() -> scoreCore.rewriteCurrentPolarityWithinTimeFence("corrupt-echo",
                Map.of("w", (slot - 20) * 100), WorkerScorePolarity.RECOVERY_RECHECK, false))
                .isInstanceOf(IllegalStateException.class).hasMessage("Worker score must be an integer");
        assertThat(redis.zscore(scoreKey("corrupt-echo"), "w")).isEqualTo(corrupt);
        // Ordinary CAS already uses Redis numeric replies; keep its separate result convention too.
        var exact = scoreCore.toggleCurrentPolarity("corrupt-echo", "w", observation);
        assertThat(exact.status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
        assertThat(exact.score()).isEqualTo(observation);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, -1})
    void pastTimeRefreshIsExplicitAndIndependentOfTargetPolarity(int polarity) {
        long slot = redisTimeMillis() / 100;
        var target = polarity == 1 ? WorkerScorePolarity.HOT_ACQUIRE : WorkerScorePolarity.RECOVERY_RECHECK;
        for (boolean refresh : new boolean[]{false, true}) {
            String id = "refresh-" + refresh;
            long observed = workerScore(-polarity, slot - 10, 99, 1);
            redis.zadd(scoreKey("mechanical-polarity"), observed, id);
            var times = Map.of(id, (slot - 5) * 100);
            var result = scoreCore.rewriteCurrentPolarityWithinTimeFence(
                    "mechanical-polarity", times, target, refresh).get(id);
            assertThat(result.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
            assertThat(result.score()).isEqualTo(workerScore(polarity, slot - (refresh ? 5 : 10), 99, 1));
            assertThat(scoreCore.rewriteCurrentPolarityWithinTimeFence(
                    "mechanical-polarity", times, target, refresh).get(id).status())
                    .isEqualTo(WorkerScoreTransitionStatus.NOOP);
        }
    }

    @Test
    void confirmationRejectsExpiredRequestedTargetEvenWhenObservedDeadlineIsLater() {
        long now = redisTimeMillis();
        long request = now + 300;
        long observed = workerScore(1, (now + 60_000) / 100, 99, 0);
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
            result = scoreCore.confirmActiveHotScoreLeases("requested-target", Map.of("w", observed), request).get("w");
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
        long observed = workerScore(1, slot, 42, 1);
        var changed = Map.of("time", observed + 200, "rank", observed + 2,
                "dirty", observed - 1, "polarity", -observed);
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
        for (String id : List.of("time", "rank", "dirty")) {
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
    void subSlotDelayAddsBeforeRoundingAndKeepsCallerSuppliedRank() throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            awaitRedisTime((redisTimeMillis() / 100 + 1) * 100 + 75);
            long before = redisTimeMillis();
            long observed = workerScore(1, before / 100 - 10, 7, 1);
            String group = "relative-rounding-" + attempt;
            redis.zadd(scoreKey(group), observed, "w");
            var result = scoreCore.deferObservedToRecovery(group,
                    Map.of("w", new WorkerScoreDelayTarget(observed, 37, 99))).get("w");
            long after = redisTimeMillis();
            if (before / 100 != after / 100 || before % 100 < 75) continue;
            assertThat(result.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
            assertThat(result.score()).isEqualTo(workerScore(-1, before / 100 + 1, 99, 1));
            return;
        }
        throw new AssertionError("Could not check sub-slot delay within one Redis slot in eight attempts");
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
            int laneRank,
            int dirty
    ) {
        long absoluteScore = timeSlot * WorkerScoreCore.SLOT_FACTOR
                + (long) laneRank * WorkerScoreCore.DIRTY_FACTOR
                + dirty;
        return polarity * absoluteScore;
    }

    private static void assertScoreShape(
            WorkerScoreState state,
            WorkerScorePolarity polarity,
            long timeMillis,
            int laneRank,
            int dirty
    ) {
        assertThat(state).isNotNull();
        assertThat(state.polarity()).isEqualTo(polarity);
        assertThat(state.timeMillis()).isEqualTo(timeMillis);
        assertThat(state.laneRank()).isEqualTo(laneRank);
        assertThat(state.dirty()).isEqualTo(dirty);
    }
}
