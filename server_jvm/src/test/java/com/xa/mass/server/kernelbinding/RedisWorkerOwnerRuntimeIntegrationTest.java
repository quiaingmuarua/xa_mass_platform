package com.xa.mass.server.kernelbinding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.redis.RedisWorkerScoreCore;
import com.xa.mass.kernel.worker.MappedWorkerPropertyIndexRuntime;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDeclaration;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import com.xa.mass.kernel.worker.redis.RedisWorkerResourceCatalog;
import com.xa.mass.kernel.worker.redis.RedisWorkerRuntime;
import com.xa.mass.kernel.worker.redis.RedisHashWorkerPropertyIndexProvider;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class RedisWorkerOwnerRuntimeIntegrationTest {

    private static final String REDIS_URL =
            System.getenv("KERNEL_DESIGN_REDIS_URL");
    private String prefix;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private RedisWorkerScoreCore scoreCore;
    private RedisWorkerRuntime runtime;
    private RedisWorkerResourceCatalog catalog;
    private RedisHashWorkerPropertyIndexProvider indexProvider;
    private MappedWorkerPropertyIndexRuntime propertyIndex;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(
                REDIS_URL != null && !REDIS_URL.isBlank(),
                "KERNEL_DESIGN_REDIS_URL is not configured"
        );
        prefix = "java-worker-owner-" + UUID.randomUUID();
        redisClient = RedisClient.create(REDIS_URL);
        connection = redisClient.connect(StringCodec.UTF8);
        redis = connection.sync();
        scoreCore = new RedisWorkerScoreCore(redisClient, prefix);
        runtime = new RedisWorkerRuntime(
                redisClient,
                scoreCore,
                prefix
        );
        catalog = new RedisWorkerResourceCatalog(redisClient, prefix);
        indexProvider = new RedisHashWorkerPropertyIndexProvider(
                redisClient,
                prefix
        );
        propertyIndex = new MappedWorkerPropertyIndexRuntime(
                catalog,
                Map.of(
                        "index.worker.region",
                        indexProvider.create("index.worker.region"),
                        "index.platform.pool",
                        indexProvider.create("index.platform.pool")
                )
        );
    }

    @AfterEach
    void tearDown() {
        if (redis != null) {
            var keys = redis.keys("wr:" + prefix + ":*");
            if (!keys.isEmpty()) {
                redis.del(keys.toArray(String[]::new));
            }
        }
        if (runtime != null) {
            runtime.close();
        }
        if (catalog != null) {
            catalog.close();
        }
        if (indexProvider != null) {
            indexProvider.close();
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
    void upsertsMatchCanonicalResourceAndScoreShape() {
        WorkerGroupDescriptor initialGroup = group(
                "group-1",
                Map.of("kind", "initial"),
                Set.of("event.b", "event.a")
        );
        assertThat(catalog.upsertWorkerGroup(initialGroup).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        WorkerGroupDescriptor updatedGroup = group(
                "group-1",
                Map.of("kind", "\u66f4\u65b0"),
                initialGroup.eventCodes()
        );
        assertThat(catalog.upsertWorkerGroup(updatedGroup).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(catalog.upsertWorkerGroup(group(
                "group-1",
                Map.of(),
                Set.of("event.other")
        )).status()).isEqualTo(WorkerRuntimeStatus.CONFLICT);
        assertThat(redis.hget(groupsKey(), "group-1")).isEqualTo(
                "{\"attributes\":{\"kind\":\"\\u66f4\\u65b0\"},"
                        + "\"eventCodes\":[\"event.a\",\"event.b\"],"
                        + "\"workerGroupId\":\"group-1\"}"
        );

        WorkerDeclaration declaration = worker(
                "worker-1",
                "group-1",
                "endpoint-1",
                Map.of("runtime", "first")
        );
        assertThat(runtime.registerWorker(declaration).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(redis.hget(workersKey("group-1"), "worker-1"))
                .isEqualTo(
                        "{\"endpointManagerId\":\"endpoint-1\","
                                + "\"platformProperties\":{},"
                                + "\"workerGroupId\":\"group-1\","
                                + "\"workerId\":\"worker-1\","
                                + "\"workerProperties\":"
                                + "{\"runtime\":\"first\"}}"
                );
        var initialScore = scoreCore.getScoreStates(
                "group-1",
                java.util.List.of("worker-1")
        ).get("worker-1");
        assertThat(initialScore).isNotNull();
        assertThat(initialScore.polarity())
                .isEqualTo(WorkerScorePolarity.HOT_ACQUIRE);
        assertThat(initialScore.laneRank())
                .isEqualTo(RedisWorkerRuntime.DEFAULT_INITIAL_LANE_RANK);
        assertThat(initialScore.dirty()).isZero();

        redis.hset(
                workersKey("group-1"),
                "worker-1",
                "{\"endpointManagerId\":\"endpoint-1\","
                        + "\"platformProperties\":{\"tier\":\"premium\"},"
                        + "\"workerGroupId\":\"group-1\","
                        + "\"workerId\":\"worker-1\","
                        + "\"workerProperties\":"
                        + "{\"runtime\":\"first\"}}"
        );
        assertThat(runtime.registerWorker(worker(
                "worker-1",
                "group-1",
                "endpoint-1",
                Map.of("runtime", "second")
        )).status()).isEqualTo(WorkerRuntimeStatus.NOOP);
        assertThat(runtime.updateWorkerProperties(
                "group-1",
                "worker-1",
                Map.of("runtime", "second")
        ).status()).isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(redis.hget(workersKey("group-1"), "worker-1"))
                .isEqualTo(
                        "{\"endpointManagerId\":\"endpoint-1\","
                                + "\"platformProperties\":"
                                + "{\"tier\":\"premium\"},"
                                + "\"workerGroupId\":\"group-1\","
                                + "\"workerId\":\"worker-1\","
                                + "\"workerProperties\":"
                                + "{\"runtime\":\"second\"}}"
                );
        assertThat(scoreCore.getScoreStates(
                "group-1",
                java.util.List.of("worker-1")
        ).get("worker-1").score()).isEqualTo(initialScore.score());
    }

    @Test
    void rejectsWorkerGroupFieldIdentityMismatchOnRead() {
        redis.hset(
                groupsKey(),
                "group-1",
                "{\"attributes\":{},"
                        + "\"eventCodes\":[\"event\"],"
                        + "\"workerGroupId\":\"group-2\"}"
        );

        assertThat(catalog.getWorkerGroupDescriptors(List.of("group-1")))
                .containsEntry("group-1", null);
    }

    @Test
    void ownerFenceAndPartialWriteRecoveryMatchPythonRuntime() {
        assertThat(catalog.upsertWorkerGroup(group(
                "group-1",
                Map.of(),
                Set.of("event")
        )).status()).isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(catalog.upsertWorkerGroup(group(
                "group-2",
                Map.of(),
                Set.of("event")
        )).status()).isEqualTo(WorkerRuntimeStatus.OK);

        WorkerDeclaration declaration = worker(
                "worker-1",
                "group-1",
                "endpoint-1",
                Map.of()
        );
        assertThat(runtime.registerWorker(declaration).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        double scoreBeforeConflict = redis.zscore(
                scoreKey("group-1"),
                "worker-1"
        );
        assertThat(runtime.registerWorker(worker(
                "worker-1",
                "group-2",
                "endpoint-1",
                Map.of()
        )).status()).isEqualTo(WorkerRuntimeStatus.CONFLICT);
        assertThat(runtime.registerWorker(worker(
                "worker-1",
                "group-1",
                "endpoint-other",
                Map.of()
        )).status()).isEqualTo(WorkerRuntimeStatus.CONFLICT);
        assertThat(redis.zscore(scoreKey("group-1"), "worker-1"))
                .isEqualTo(scoreBeforeConflict);

        redis.zrem(scoreKey("group-1"), "worker-1");
        assertThat(runtime.registerWorker(declaration).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(redis.zscore(scoreKey("group-1"), "worker-1"))
                .isNotNull();

        redis.hdel(workersKey("group-1"), "worker-1");
        assertThat(runtime.registerWorker(declaration).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(redis.hget(workersKey("group-1"), "worker-1"))
                .isNotNull();

        redis.hdel(workerIdOwnersKey(), "worker-1");
        assertThat(runtime.registerWorker(declaration).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(redis.hget(workerIdOwnersKey(), "worker-1"))
                .isEqualTo("group-1");

        redis.hset(
                workersKey("group-1"),
                "corrupt-field",
                workerJson("another-worker", "group-1", 1)
        );
        assertThat(catalog.patchWorkerPlatformProperties(
                "group-1",
                "corrupt-field",
                Map.of("pool", "batch")
        ).status()).isEqualTo(WorkerRuntimeStatus.CONFLICT);
    }

    @Test
    void samplesOneWorkerHashWithoutCompletenessOrStabilityPromise() {
        Map<String, String> rows = new LinkedHashMap<>();
        for (int index = 0; index < 120; index++) {
            String workerId = "worker-%03d".formatted(index);
            rows.put(
                    workerId,
                    workerJson(workerId, "group-1", index)
            );
        }
        redis.hset(workersKey("group-1"), rows);
        redis.hset(
                workersKey("group-2"),
                "other-worker",
                workerJson("other-worker", "group-2", 999)
        );

        Map<String, WorkerDescriptor> one =
                catalog.sampleWorkerDescriptors("group-1", 1);
        Map<String, WorkerDescriptor> hundred =
                catalog.sampleWorkerDescriptors("group-1", 100);
        var repeatedSamples = new java.util.HashSet<Set<String>>();
        for (int iteration = 0; iteration < 10; iteration++) {
            repeatedSamples.add(Set.copyOf(
                    catalog.sampleWorkerDescriptors(
                            "group-1",
                            5
                    ).keySet()
            ));
        }

        assertThat(one).hasSize(1);
        assertThat(hundred).hasSize(100);
        assertThat(hundred.keySet()).doesNotHaveDuplicates();
        assertThat(hundred).doesNotContainKey("other-worker");
        assertThat(hundred.values()).allSatisfy(descriptor -> {
            assertThat(descriptor).isNotNull();
            assertThat(descriptor.workerGroupId()).isEqualTo("group-1");
        });
        assertThat(repeatedSamples).hasSizeGreaterThan(1);
        assertThat(catalog.sampleWorkerDescriptors(
                "empty-group",
                100
        )).isEmpty();

        redis.hset(
                workersKey("invalid-group"),
                Map.of(
                        "broken",
                        "{not-json",
                        "wrong-id",
                        workerJson("another-id", "invalid-group", 1),
                        "wrong-group",
                        workerJson("wrong-group", "group-2", 2)
                )
        );
        Map<String, WorkerDescriptor> unreadable =
                catalog.sampleWorkerDescriptors(
                "invalid-group",
                100
        );
        assertThat(unreadable)
                .containsOnlyKeys("broken", "wrong-group", "wrong-id");
        assertThat(unreadable.values()).containsOnlyNulls();

        assertThatThrownBy(() ->
                catalog.sampleWorkerDescriptors("", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                catalog.sampleWorkerDescriptors("group-1", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                catalog.sampleWorkerDescriptors("group-1", 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void repeatedRegisterAndPropertyUpdatePreserveExistingScoreStates() {
        catalog.upsertWorkerGroup(group(
                "group-1",
                Map.of(),
                Set.of("event")
        ));
        WorkerDeclaration declaration = worker(
                "worker-1",
                "group-1",
                "endpoint-1",
                Map.of()
        );
        runtime.registerWorker(declaration);
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

            assertThat(runtime.registerWorker(declaration).status())
                    .isEqualTo(WorkerRuntimeStatus.NOOP);
            assertThat(runtime.updateWorkerProperties(
                    "group-1",
                    "worker-1",
                    Map.of("score-proof", existingScore)
            ).status()).isIn(
                    WorkerRuntimeStatus.OK,
                    WorkerRuntimeStatus.NOOP
            );
            assertThat(redis.zscore(scoreKey("group-1"), "worker-1"))
                    .isEqualTo((double) existingScore);
        }
    }

    @Test
    void propertyUpdateDoesNotInitializeMissingScore() {
        catalog.upsertWorkerGroup(group(
                "group-1",
                Map.of(),
                Set.of("event")
        ));
        WorkerDeclaration declaration = worker(
                "worker-1",
                "group-1",
                "endpoint-1",
                Map.of("runtime", "initial")
        );
        runtime.registerWorker(declaration);
        redis.zrem(scoreKey("group-1"), "worker-1");

        assertThat(runtime.updateWorkerProperties(
                "group-1",
                "worker-1",
                Map.of("runtime", "updated")
        ).status()).isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(redis.zscore(scoreKey("group-1"), "worker-1"))
                .isNull();

        assertThat(runtime.registerWorker(declaration).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(catalog.getWorkerDescriptors(
                "group-1",
                List.of("worker-1")
        ).get("worker-1").workerProperties())
                .containsExactlyEntriesOf(Map.of("runtime", "updated"));
    }

    @Test
    void snapshotsAndExplicitIndexProjectionsRemainIndependent() {
        catalog.upsertWorkerGroup(group(
                "group-1",
                Map.of(),
                Set.of("event")
        ));
        runtime.registerWorker(worker(
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
        assertThat(propertyIndex.updateIndexedProperties(
                "group-1",
                "worker-1",
                Map.of(
                        "index.worker.region", "cn-east",
                        "index.platform.pool", "batch"
                )
        )).allSatisfy((field, result) ->
                assertThat(result.status()).isEqualTo(WorkerRuntimeStatus.OK));

        assertThat(propertyIndex.loadIndexedPropertyValues(
                "group-1",
                "index.worker.region",
                List.of("worker-1")
        )).containsExactlyEntriesOf(Map.of("worker-1", "cn-east"));
        assertThat(propertyIndex.loadIndexedPropertyValues(
                "group-1",
                "index.platform.pool",
                List.of("worker-1")
        )).containsExactlyEntriesOf(Map.of("worker-1", "batch"));
        WorkerDescriptor descriptor = catalog.getWorkerDescriptors(
                "group-1",
                List.of("worker-1")
        ).get("worker-1");
        assertThat(descriptor.workerProperties())
                .containsExactlyEntriesOf(Map.of("region", "snapshot"));
        assertThat(descriptor.platformProperties())
                .containsExactlyEntriesOf(Map.of("viewOnly", "shown"));

        var disabled = new MappedWorkerPropertyIndexRuntime(
                catalog,
                Map.of()
        );
        assertThatThrownBy(() -> disabled.loadIndexedPropertyValues(
                "group-1",
                "index.worker.region",
                List.of("worker-1")
        )).isInstanceOf(IllegalStateException.class);
        var reenabled = new MappedWorkerPropertyIndexRuntime(
                catalog,
                Map.of(
                        "index.worker.region",
                        indexProvider.create("index.worker.region")
                )
        );
        assertThat(reenabled.loadIndexedPropertyValues(
                "group-1",
                "index.worker.region",
                List.of("worker-1")
        )).containsExactlyEntriesOf(Map.of("worker-1", "cn-east"));

        var deletes = new LinkedHashMap<String, Object>();
        deletes.put("index.worker.region", null);
        assertThat(reenabled.updateIndexedProperties(
                "group-1",
                "worker-1",
                deletes
        ).get("index.worker.region").status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(reenabled.loadIndexedPropertyValues(
                "group-1",
                "index.worker.region",
                List.of("worker-1")
        )).isEmpty();
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
            Map<String, Object> attributes
    ) {
        return new WorkerDeclaration(
                workerId,
                workerGroupId,
                endpointManagerId,
                attributes
        );
    }

    private static String workerJson(
            String workerId,
            String workerGroupId,
            int index
    ) {
        return "{\"endpointManagerId\":\"endpoint-1\","
                + "\"platformProperties\":{},"
                + "\"workerGroupId\":\"" + workerGroupId + "\","
                + "\"workerId\":\"" + workerId + "\","
                + "\"workerProperties\":{\"index\":" + index + "}}";
    }

    private String groupsKey() {
        return "wr:" + prefix + ":groups";
    }

    private String workersKey(String workerGroupId) {
        return "wr:" + prefix + ":workers:" + workerGroupId;
    }

    private String workerIdOwnersKey() {
        return "wr:" + prefix + ":worker-id-owners";
    }

    private String scoreKey(String workerGroupId) {
        return "wr:" + prefix + ":score:" + workerGroupId;
    }
}
