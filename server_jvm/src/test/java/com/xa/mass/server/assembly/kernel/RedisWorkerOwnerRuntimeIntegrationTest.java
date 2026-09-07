package com.xa.mass.server.assembly.kernel;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.score.WorkerScoreCore;
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
        assertThat(scoreCore.acquireHotCandidatesBefore("group-1", redisTimeMillis(), Long.MAX_VALUE, 100)).isEmpty();
        assertThat(scoreCore.acquireRecoveryRecheckCandidates("group-1", 0, 100)).isEmpty();
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
        var results = scoreCore.releaseCompletedHotScoreHolds(
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
    void activeHotLeaseObservationReturnsOnlyCurrentCleanExpectedFences() {
        long nowSlot = redisTimeMillis() / WorkerScoreCore.SLOT_MILLIS;
        long expectedSlot = nowSlot + 100;
        long expectedLeaseUntilMillis = expectedSlot
                * WorkerScoreCore.SLOT_MILLIS;
        long active = workerScore(
                WorkerScoreCore.HOT_ACQUIRE_POLARITY,
                expectedSlot,
                7,
                WorkerScoreCore.MIN_DIRTY
        );
        redis.zadd(scoreKey("group-lease-observe"), active, "active");
        redis.zadd(scoreKey("group-lease-observe"), workerScore(
                WorkerScoreCore.HOT_ACQUIRE_POLARITY,
                expectedSlot,
                7,
                WorkerScoreCore.MAX_DIRTY
        ), "dirty");
        redis.zadd(scoreKey("group-lease-observe"), workerScore(
                WorkerScoreCore.RECOVERY_RECHECK_POLARITY,
                expectedSlot,
                7,
                WorkerScoreCore.MIN_DIRTY
        ), "recovery");
        redis.zadd(scoreKey("group-lease-observe"), workerScore(
                WorkerScoreCore.HOT_ACQUIRE_POLARITY,
                expectedSlot + 1,
                7,
                WorkerScoreCore.MIN_DIRTY
        ), "different-slot");

        Map<String, Long> observed = scoreCore.observeActiveHotScoreLeases(
                "group-lease-observe",
                List.of(
                        "dirty",
                        "active",
                        "recovery",
                        "different-slot",
                        "missing"
                ),
                expectedLeaseUntilMillis
        );

        assertThat(observed).containsExactlyEntriesOf(Map.of(
                "active", active
        ));

        redis.zadd(
                scoreKey("group-lease-observe"),
                active + WorkerScoreCore.SLOT_FACTOR,
                "active"
        );
        assertThat(scoreCore.observeActiveHotScoreLeases(
                "group-lease-observe",
                List.of("active"),
                expectedLeaseUntilMillis
        )).isEmpty();
    }

    @Test
    void boundedMatchHoldsAdvanceAcrossAWorkerPoolWithoutRelease() {
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
                .holdObservedHotForServiceabilityProbes(
                        "group-serviceability",
                        Map.of(
                                "hot", hot,
                                "stale", hot
                        )
                );
        var recoveryResults = scoreCore.advanceObservedRecoveryRechecks(
                "group-serviceability",
                Map.of(
                        "recovery", recovery,
                        "exhausted", exhausted
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
                        beforeSlot,
                        afterSlot
                );
        assertThat(held.get("recovery").timeMillis()
                / WorkerScoreCore.SLOT_MILLIS).isBetween(
                        beforeSlot,
                        afterSlot
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
        evidence.put("hot", held.get("hot").timeMillis());
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
        var evidenceResults = scoreCore.applyServiceabilityEvidence(
                "group-serviceability",
                evidence,
                WorkerScorePolarity.HOT_ACQUIRE
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

        var unavailable = scoreCore.applyServiceabilityEvidence(
                "group-serviceability",
                Map.of(
                        "future",
                        (currentSlot - 100) * WorkerScoreCore.SLOT_MILLIS,
                        "pause",
                        (currentSlot - 100) * WorkerScoreCore.SLOT_MILLIS
                ),
                WorkerScorePolarity.RECOVERY_RECHECK
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
                "group-range", hotCutoffMillis, 0, 1
        );
        var secondHot = scoreCore.acquireHotCandidatesBefore(
                "group-range",
                hotCutoffMillis,
                firstHot.getFirst().score(),
                2
        );
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
                "group-range", 0, 1
        );
        var secondRecovery = scoreCore.acquireRecoveryRecheckCandidates(
                "group-range", firstRecovery.getFirst().score(), 2
        );
        assertThat(firstRecovery).extracting(
                WorkerScoreCore.WorkerScoreObservation::workerId
        ).containsExactly("recovery-older");
        assertThat(secondRecovery).extracting(
                WorkerScoreCore.WorkerScoreObservation::workerId
        ).containsExactly("recovery-newer");
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

        var exhausted = scoreCore.exhaustRecoveryRecheck(
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
        assertThat(scoreCore.exhaustRecoveryRecheck(
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
        assertThat(scoreCore.releaseCompletedHotScoreHolds("g", Map.of("w", held), releaseAt)
                .get("w").status()).isEqualTo(WorkerScoreTransitionStatus.STALE);
        var released = scoreCore.releaseCompletedHotScoreHolds("g", Map.of("w", execution), releaseAt).get("w");
        assertThat(released.status()).isEqualTo(WorkerScoreTransitionStatus.TRANSITIONED);
        assertThat(released.score() % 2).isEqualTo(1);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3)).until(() ->
                !scoreCore.observeDueHotScores("g", List.of("w"), null).isEmpty());
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
        var completed = scoreCore.releaseCompletedHotScoreHolds("g", Map.of("w", execution), releaseAt).get("w");
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
