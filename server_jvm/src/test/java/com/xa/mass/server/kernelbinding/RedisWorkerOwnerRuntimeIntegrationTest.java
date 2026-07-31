package com.xa.mass.server.kernelbinding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.redis.RedisWorkerScoreCore;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDeclaration;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
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
                        + "\"itemAllocationFields\":[\"workerId\"],"
                        + "\"workerGroupId\":\"group-1\"}"
        );

        WorkerDeclaration declaration = worker(
                "worker-1",
                "group-1",
                "endpoint-1",
                Map.of("runtime", "first")
        );
        assertThat(runtime.upsertWorker(declaration).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(redis.hget(workersKey("group-1"), "worker-1"))
                .isEqualTo(
                        "{\"attributes\":{\"runtime\":\"first\"},"
                                + "\"dynamicAttributeNames\":[],"
                                + "\"endpointManagerId\":\"endpoint-1\","
                                + "\"platformAttributes\":{},"
                                + "\"workerGroupId\":\"group-1\","
                                + "\"workerId\":\"worker-1\"}"
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
                "{\"attributes\":{\"runtime\":\"first\"},"
                        + "\"dynamicAttributeNames\":[],"
                        + "\"endpointManagerId\":\"endpoint-1\","
                        + "\"platformAttributes\":{\"tier\":\"premium\"},"
                        + "\"workerGroupId\":\"group-1\","
                        + "\"workerId\":\"worker-1\"}"
        );
        assertThat(runtime.upsertWorker(worker(
                "worker-1",
                "group-1",
                "endpoint-1",
                Map.of("runtime", "second")
        )).status()).isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(redis.hget(workersKey("group-1"), "worker-1"))
                .isEqualTo(
                        "{\"attributes\":{\"runtime\":\"second\"},"
                                + "\"dynamicAttributeNames\":[],"
                                + "\"endpointManagerId\":\"endpoint-1\","
                                + "\"platformAttributes\":"
                                + "{\"tier\":\"premium\"},"
                                + "\"workerGroupId\":\"group-1\","
                                + "\"workerId\":\"worker-1\"}"
                );
        assertThat(scoreCore.getScoreStates(
                "group-1",
                java.util.List.of("worker-1")
        ).get("worker-1").dirty()).isEqualTo(1);
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
        assertThat(runtime.upsertWorker(declaration).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        double scoreBeforeConflict = redis.zscore(
                scoreKey("group-1"),
                "worker-1"
        );
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
                .isEqualTo(scoreBeforeConflict);

        redis.zrem(scoreKey("group-1"), "worker-1");
        assertThat(runtime.upsertWorker(declaration).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(redis.zscore(scoreKey("group-1"), "worker-1"))
                .isNotNull();

        redis.hdel(workersKey("group-1"), "worker-1");
        assertThat(runtime.upsertWorker(declaration).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        assertThat(redis.hget(workersKey("group-1"), "worker-1"))
                .isNotNull();
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
    void reconnectReconcilesRecoveryScoreToHotAcquire() {
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
        runtime.upsertWorker(declaration);
        long currentScore = redis.zscore(
                scoreKey("group-1"),
                "worker-1"
        ).longValue();
        redis.zadd(scoreKey("group-1"), -currentScore, "worker-1");

        assertThat(runtime.upsertWorker(declaration).status())
                .isEqualTo(WorkerRuntimeStatus.OK);
        var reconciled = scoreCore.getScoreStates(
                "group-1",
                java.util.List.of("worker-1")
        ).get("worker-1");
        assertThat(reconciled.polarity())
                .isEqualTo(WorkerScorePolarity.HOT_ACQUIRE);
        assertThat(reconciled.score()).isEqualTo(currentScore + 1);
        assertThat(reconciled.dirty()).isEqualTo(1);
    }

    private static WorkerGroupDescriptor group(
            String workerGroupId,
            Map<String, Object> attributes,
            Set<String> eventCodes
    ) {
        return new WorkerGroupDescriptor(
                workerGroupId,
                attributes,
                eventCodes,
                Set.of("workerId")
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
                attributes,
                Set.of()
        );
    }

    private static String workerJson(
            String workerId,
            String workerGroupId,
            int index
    ) {
        return "{\"attributes\":{\"index\":" + index + "},"
                + "\"dynamicAttributeNames\":[],"
                + "\"endpointManagerId\":\"endpoint-1\","
                + "\"platformAttributes\":{},"
                + "\"workerGroupId\":\"" + workerGroupId + "\","
                + "\"workerId\":\"" + workerId + "\"}";
    }

    private String groupsKey() {
        return "wr:" + prefix + ":groups";
    }

    private String workersKey(String workerGroupId) {
        return "wr:" + prefix + ":workers:" + workerGroupId;
    }

    private String scoreKey(String workerGroupId) {
        return "wr:" + prefix + ":score:" + workerGroupId;
    }
}
