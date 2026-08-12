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
import com.xa.mass.kernel.worker.redis.RedisHashWorkerPropertyIndexProvider;
import com.xa.mass.kernel.worker.redis.RedisWorkerResourceCatalog;
import com.xa.mass.kernel.worker.redis.RedisWorkerRuntime;
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

@Tag("redis-owner")
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
        runtime = new RedisWorkerRuntime(redisClient, scoreCore, prefix);
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
    void upsertSplitsMetadataAndPropertiesWithoutResettingOtherTruth() {
        WorkerGroupDescriptor initialGroup = group(
                "group-1",
                Map.of("kind", "initial"),
                Set.of("event.b", "event.a")
        );
        assertThat(catalog.upsertWorkerGroup(initialGroup).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        WorkerGroupDescriptor updatedGroup = group(
                "group-1",
                Map.of("kind", "updated"),
                Set.of("event.other")
        );
        assertThat(catalog.upsertWorkerGroup(updatedGroup).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(catalog.upsertWorkerGroup(updatedGroup).status())
                .isEqualTo(WorkerRuntimeStatus.NOOP);

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
                .isEqualTo(RedisWorkerRuntime.DEFAULT_INITIAL_LANE_RANK);

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
    void ownerFenceAndMissingStagesConvergeThroughUpsert() {
        catalog.upsertWorkerGroup(group("group-1", Map.of(), Set.of("event")));
        catalog.upsertWorkerGroup(group("group-2", Map.of(), Set.of("event")));
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
        catalog.upsertWorkerGroup(group("group-1", Map.of(), Set.of("event")));
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
    void snapshotsAndExplicitIndexProjectionsRemainIndependent() {
        catalog.upsertWorkerGroup(group("group-1", Map.of(), Set.of("event")));
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
        WorkerDescriptor descriptor = catalog.getWorkerDescriptors(
                "group-1",
                List.of("worker-1")
        ).get("worker-1");
        assertThat(descriptor.workerProperties())
                .containsExactlyEntriesOf(Map.of("region", "snapshot"));
        assertThat(descriptor.platformProperties())
                .containsExactlyEntriesOf(Map.of("viewOnly", "shown"));

        var deletes = new LinkedHashMap<String, Object>();
        deletes.put("index.worker.region", null);
        assertThat(propertyIndex.updateIndexedProperties(
                "group-1",
                "worker-1",
                deletes
        ).get("index.worker.region").status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(propertyIndex.loadIndexedPropertyValues(
                "group-1",
                "index.worker.region",
                List.of("worker-1")
        )).isEmpty();
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
        return "wr:" + prefix + ":groups";
    }

    private String metadataKey(String workerGroupId) {
        return "wr:" + prefix + ":worker-metadata:" + workerGroupId;
    }

    private String propertiesKey(String workerGroupId) {
        return "wr:" + prefix + ":worker-properties:" + workerGroupId;
    }

    private String workerIdOwnersKey() {
        return "wr:" + prefix + ":worker-id-owners";
    }

    private String scoreKey(String workerGroupId) {
        return "wr:" + prefix + ":score:" + workerGroupId;
    }
}
