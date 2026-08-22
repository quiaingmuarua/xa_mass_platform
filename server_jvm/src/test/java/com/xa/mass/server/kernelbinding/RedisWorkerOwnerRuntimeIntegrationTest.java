package com.xa.mass.server.kernelbinding;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.score.redis.RedisWorkerScoreCore;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDeclaration;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import com.xa.mass.kernel.worker.redis.RedisWorkerResourceCatalog;
import com.xa.mass.kernel.worker.redis.RedisWorkerRuntime;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("redis-owner")
class RedisWorkerOwnerRuntimeIntegrationTest {

    private RedisTestScope testScope;
    private RedisKeyspace keyspace;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private RedisWorkerScoreCore scoreCore;
    private RedisWorkerRuntime runtime;
    private RedisWorkerResourceCatalog catalog;

    @BeforeEach
    void setUp() {
        testScope = RedisTestScope.create("java_worker_owner");
        keyspace = testScope.keyspace();
        redisClient = RedisClient.create(REDIS_URL);
        connection = redisClient.connect(StringCodec.UTF8);
        redis = connection.sync();
        scoreCore = new RedisWorkerScoreCore(redisClient, keyspace);
        runtime = new RedisWorkerRuntime(redisClient, scoreCore, keyspace);
        catalog = new RedisWorkerResourceCatalog(redisClient, keyspace);
    }

    @AfterEach
    void tearDown() {
        if (redis != null) {
            testScope.cleanup(redis);
        }
        if (runtime != null) {
            runtime.close();
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
    void groupRegistrationIsCreateOnlyAndWorkerUpsertPreservesOtherTruth() {
        WorkerGroupDescriptor initialGroup = group(
                "group-1",
                Map.of("kind", "initial"),
                Set.of("event.b", "event.a")
        );
        assertThat(catalog.registerWorkerGroup(initialGroup).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        WorkerGroupDescriptor updatedGroup = group(
                "group-1",
                Map.of("kind", "updated"),
                Set.of("event.other")
        );
        assertThat(catalog.registerWorkerGroup(initialGroup).status())
                .isEqualTo(WorkerRuntimeStatus.NOOP);
        assertThat(catalog.registerWorkerGroup(updatedGroup).status())
                .isEqualTo(WorkerRuntimeStatus.CONFLICT);
        assertThat(catalog.getWorkerGroupDescriptors(List.of("group-1")))
                .containsEntry("group-1", initialGroup);

        WorkerDeclaration first = worker(
                "worker-1",
                "group-1",
                "endpoint-1",
                Map.of("runtime", "first")
        );
        assertThat(runtime.upsertWorker(first).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(redis.hget(metadataKey("group-1"), "worker-1"))
                .isEqualTo(metadataJson(
                        "worker-1",
                        "group-1",
                        "endpoint-1",
                        "{}"
                ));
        assertThat(redis.hget(propertiesKey("group-1"), "worker-1"))
                .isEqualTo("{\"runtime\":\"first\"}");

        var initialScore = scoreCore.getScoreStates(
                "group-1",
                List.of("worker-1")
        ).get("worker-1");
        assertThat(initialScore).isNotNull();
        assertThat(initialScore.polarity())
                .isEqualTo(WorkerScorePolarity.HOT_ACQUIRE);
        assertThat(initialScore.laneRank())
                .isEqualTo(WorkerScoreCore.MIN_LANE_RANK);

        assertThat(catalog.patchWorkerPlatformProperties(
                "group-1",
                "worker-1",
                Map.of("tier", "premium")
        ).status()).isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(runtime.upsertWorker(worker(
                "worker-1",
                "group-1",
                "endpoint-1",
                Map.of("runtime", "second")
        )).status()).isEqualTo(WorkerRuntimeStatus.OK);

        assertThat(redis.hget(metadataKey("group-1"), "worker-1"))
                .isEqualTo(metadataJson(
                        "worker-1",
                        "group-1",
                        "endpoint-1",
                        "{\"tier\":\"premium\"}"
                ));
        assertThat(redis.hget(propertiesKey("group-1"), "worker-1"))
                .isEqualTo("{\"runtime\":\"second\"}");
        assertThat(scoreCore.getScoreStates(
                "group-1",
                List.of("worker-1")
        ).get("worker-1").score()).isEqualTo(initialScore.score());
        assertThat(runtime.upsertWorker(worker(
                "worker-1",
                "group-1",
                "endpoint-1",
                Map.of("runtime", "second")
        )).status()).isEqualTo(WorkerRuntimeStatus.NOOP);
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
                    WorkerRuntimeStatus.OK,
                    WorkerRuntimeStatus.CONFLICT
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
        )).status()).isEqualTo(WorkerRuntimeStatus.INVALID);
        assertThat(redis.hget(groupsKey(), "group-1")).isEqualTo(stored);
    }

    @Test
    void samplesWorkerGroupsWithOneBoundedHashOperationSemantics() {
        for (int index = 0; index < 120; index++) {
            assertThat(catalog.registerWorkerGroup(group(
                    "group-%03d".formatted(index),
                    Map.of("index", index),
                    Set.of("event")
            )).status()).isEqualTo(WorkerRuntimeStatus.OK);
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
    void ownerFenceAndMissingStagesConvergeThroughUpsert() {
        catalog.registerWorkerGroup(group("group-1", Map.of(), Set.of("event")));
        catalog.registerWorkerGroup(group("group-2", Map.of(), Set.of("event")));
        WorkerDeclaration declaration = worker(
                "worker-1",
                "group-1",
                "endpoint-1",
                Map.of("runtime", "java")
        );
        assertThat(runtime.upsertWorker(declaration).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        double score = redis.zscore(scoreKey("group-1"), "worker-1");

        assertThat(runtime.upsertWorker(worker(
                "worker-1",
                "group-2",
                "endpoint-1",
                Map.of()
        )).status()).isEqualTo(WorkerRuntimeStatus.CONFLICT);
        assertThat(runtime.upsertWorker(worker(
                "worker-1",
                "group-1",
                "endpoint-other",
                Map.of()
        )).status()).isEqualTo(WorkerRuntimeStatus.CONFLICT);
        assertThat(redis.zscore(scoreKey("group-1"), "worker-1"))
                .isEqualTo(score);

        redis.zrem(scoreKey("group-1"), "worker-1");
        assertThat(runtime.upsertWorker(declaration).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(redis.zscore(scoreKey("group-1"), "worker-1"))
                .isNotNull();

        redis.hdel(propertiesKey("group-1"), "worker-1");
        assertThat(runtime.upsertWorker(declaration).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(redis.hget(propertiesKey("group-1"), "worker-1"))
                .isEqualTo("{\"runtime\":\"java\"}");

        redis.hdel(metadataKey("group-1"), "worker-1");
        assertThat(runtime.upsertWorker(declaration).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(redis.hget(metadataKey("group-1"), "worker-1"))
                .isNotNull();

        redis.hdel(workerIdOwnersKey(), "worker-1");
        assertThat(runtime.upsertWorker(declaration).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(redis.hget(workerIdOwnersKey(), "worker-1"))
                .isEqualTo("group-1");
    }

    @Test
    void resolvesBoundedWorkerGroupOwnersWithoutDescriptorScan() {
        assertThat(catalog.registerWorkerGroup(group(
                "group-1",
                Map.of(),
                Set.of()
        )).status()).isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(catalog.registerWorkerGroup(group(
                "group-2",
                Map.of(),
                Set.of()
        )).status()).isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(runtime.upsertWorker(worker(
                "worker-1",
                "group-1",
                "endpoint-1",
                Map.of()
        )).status()).isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(runtime.upsertWorker(worker(
                "worker-2",
                "group-2",
                "endpoint-1",
                Map.of()
        )).status()).isEqualTo(WorkerRuntimeStatus.OK);

        Map<String, String> owners = catalog.getWorkerGroupIds(List.of(
                "worker-2",
                "missing",
                "worker-1"
        ));

        assertThat(owners.keySet()).containsExactly(
                "worker-2",
                "missing",
                "worker-1"
        );
        assertThat(owners)
                .containsEntry("worker-2", "group-2")
                .containsEntry("missing", null)
                .containsEntry("worker-1", "group-1");
    }

    @Test
    void catalogComposesAndSamplesTheTwoWorkerHashes() {
        Map<String, String> metadata = new LinkedHashMap<>();
        Map<String, String> properties = new LinkedHashMap<>();
        for (int index = 0; index < 120; index++) {
            String workerId = "worker-%03d".formatted(index);
            metadata.put(
                    workerId,
                    metadataJson(
                            workerId,
                            "group-1",
                            "endpoint-1",
                            "{}"
                    )
            );
            properties.put(workerId, "{\"index\":" + index + "}");
        }
        redis.hset(metadataKey("group-1"), metadata);
        redis.hset(propertiesKey("group-1"), properties);
        redis.hset(
                metadataKey("group-2"),
                "other-worker",
                metadataJson(
                        "other-worker",
                        "group-2",
                        "endpoint-1",
                        "{}"
                )
        );
        redis.hset(propertiesKey("group-2"), "other-worker", "{}");

        Map<String, WorkerDescriptor> one =
                catalog.sampleWorkerDescriptors("group-1", 1);
        Map<String, WorkerDescriptor> hundred =
                catalog.sampleWorkerDescriptors("group-1", 100);

        assertThat(one).hasSize(1);
        assertThat(hundred).hasSize(100);
        assertThat(hundred).doesNotContainKey("other-worker");
        assertThat(hundred.values()).allSatisfy(descriptor -> {
            assertThat(descriptor).isNotNull();
            assertThat(descriptor.workerGroupId()).isEqualTo("group-1");
        });

        redis.hset(metadataKey("invalid-group"), Map.of(
                "broken", "{not-json",
                "wrong-id", metadataJson(
                        "another-id",
                        "invalid-group",
                        "endpoint-1",
                        "{}"
                ),
                "missing-properties", metadataJson(
                        "missing-properties",
                        "invalid-group",
                        "endpoint-1",
                        "{}"
                )
        ));
        redis.hset(propertiesKey("invalid-group"), Map.of(
                "broken", "{}",
                "wrong-id", "{}"
        ));
        Map<String, WorkerDescriptor> unreadable =
                catalog.sampleWorkerDescriptors("invalid-group", 100);
        assertThat(unreadable)
                .containsOnlyKeys("broken", "missing-properties", "wrong-id");
        assertThat(unreadable.values()).containsOnlyNulls();

        assertThatThrownBy(() -> catalog.sampleWorkerDescriptors("", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                catalog.sampleWorkerDescriptors("group-1", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                catalog.sampleWorkerDescriptors("group-1", 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void repeatedUpsertPreservesAllExistingScoreShapes() {
        catalog.registerWorkerGroup(group("group-1", Map.of(), Set.of("event")));
        WorkerDeclaration declaration = worker(
                "worker-1",
                "group-1",
                "endpoint-1",
                Map.of()
        );
        runtime.upsertWorker(declaration);
        long initialScore = redis.zscore(
                scoreKey("group-1"),
                "worker-1"
        ).longValue();
        long[] existingScores = {
                initialScore,
                initialScore + 1,
                -initialScore,
                initialScore + WorkerScoreCore.SLOT_FACTOR
        };
        for (long existingScore : existingScores) {
            redis.zadd(scoreKey("group-1"), existingScore, "worker-1");
            assertThat(runtime.upsertWorker(worker(
                    "worker-1",
                    "group-1",
                    "endpoint-1",
                    Map.of("score-proof", existingScore)
            )).status()).isIn(
                    WorkerRuntimeStatus.OK,
                    WorkerRuntimeStatus.NOOP
            );
            assertThat(redis.zscore(scoreKey("group-1"), "worker-1"))
                    .isEqualTo((double) existingScore);
        }
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
    void workerAndPlatformPropertiesRemainIndependentAndScoreNeutral() {
        catalog.registerWorkerGroup(group("group-1", Map.of(), Set.of("event")));
        runtime.upsertWorker(worker(
                "worker-1",
                "group-1",
                "endpoint-1",
                Map.of("region", "snapshot")
        ));
        assertThat(catalog.patchWorkerPlatformProperties(
                "group-1",
                "worker-1",
                Map.of("viewOnly", "shown")
        ).status()).isEqualTo(WorkerRuntimeStatus.OK);
        WorkerScoreState scoreBefore = scoreCore.getScoreStates(
                "group-1",
                List.of("worker-1")
        ).get("worker-1");
        assertThat(runtime.upsertWorker(worker(
                "worker-1",
                "group-1",
                "endpoint-1",
                Map.of("region", "cn-east", "battery", 87)
        )).status()).isEqualTo(WorkerRuntimeStatus.OK);
        WorkerDescriptor descriptor = catalog.getWorkerDescriptors(
                "group-1",
                List.of("worker-1")
        ).get("worker-1");
        assertThat(descriptor.workerProperties())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "region", "cn-east",
                        "battery", 87L
                ));
        assertThat(descriptor.platformProperties())
                .containsExactlyEntriesOf(Map.of("viewOnly", "shown"));
        assertThat(scoreCore.getScoreStates(
                "group-1",
                List.of("worker-1")
        ).get("worker-1")).isEqualTo(scoreBefore);
    }

    @Test
    void rejectsStoredIdentityMismatchAndMissingComposedRows() {
        redis.hset(
                groupsKey(),
                "group-1",
                "{\"attributes\":{},\"eventCodes\":[\"event\"],"
                        + "\"workerGroupId\":\"group-2\"}"
        );
        assertThat(catalog.getWorkerGroupDescriptors(List.of("group-1")))
                .containsEntry("group-1", null);

        redis.hset(
                metadataKey("group-1"),
                "worker-1",
                metadataJson(
                        "worker-1",
                        "group-1",
                        "endpoint-1",
                        "{}"
                )
        );
        assertThat(catalog.getWorkerDescriptors(
                "group-1",
                List.of("worker-1")
        )).containsEntry("worker-1", null);
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

    private static WorkerDeclaration worker(
            String workerId,
            String workerGroupId,
            String endpointManagerId,
            Map<String, Object> workerProperties
    ) {
        return new WorkerDeclaration(
                workerId,
                workerGroupId,
                endpointManagerId,
                workerProperties
        );
    }

    private static String metadataJson(
            String workerId,
            String workerGroupId,
            String endpointManagerId,
            String platformPropertiesJson
    ) {
        return "{\"endpointManagerId\":\"" + endpointManagerId + "\","
                + "\"platformProperties\":" + platformPropertiesJson + ","
                + "\"workerGroupId\":\"" + workerGroupId + "\","
                + "\"workerId\":\"" + workerId + "\"}";
    }

    private String groupsKey() {
        return keyspace.base() + ":worker:groups";
    }

    private String metadataKey(String workerGroupId) {
        return keyspace.base() + ":worker:metadata:" + workerGroupId;
    }

    private String propertiesKey(String workerGroupId) {
        return keyspace.base() + ":worker:properties:" + workerGroupId;
    }

    private String workerIdOwnersKey() {
        return keyspace.base() + ":worker:id_owners";
    }

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
