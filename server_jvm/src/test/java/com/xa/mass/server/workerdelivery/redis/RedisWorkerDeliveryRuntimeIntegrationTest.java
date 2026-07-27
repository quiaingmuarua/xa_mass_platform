package com.xa.mass.server.workerdelivery.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.server.kernelredis.KernelRedisConfiguration;
import com.xa.mass.server.kernelredis.KernelRedisProperties;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class RedisWorkerDeliveryRuntimeIntegrationTest {

    private static final String REDIS_URL =
            System.getenv("KERNEL_DESIGN_REDIS_URL");
    private String prefix;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private RedisWorkerDeliveryRuntime runtime;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(
                REDIS_URL != null && !REDIS_URL.isBlank(),
                "KERNEL_DESIGN_REDIS_URL is not configured"
        );
        prefix = "java-worker-delivery-" + UUID.randomUUID();
        var properties = new KernelRedisProperties(
                URI.create(REDIS_URL),
                prefix
        );
        var configuration = new KernelRedisConfiguration();
        redisClient = configuration.kernelRedisClient(properties);
        connection = redisClient.connect(StringCodec.UTF8);
        redis = connection.sync();
        runtime = new RedisWorkerDeliveryRuntime(
                redisClient,
                new WorkerDeliveryCodec(),
                properties
        );
    }

    @AfterEach
    void tearDown() {
        if (redis != null) {
            deleteKeys(redis.keys("wd:" + prefix + ":*"));
            deleteKeys(redis.keys("rr:" + prefix + ":*"));
        }
        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    private void deleteKeys(java.util.Collection<String> keys) {
        if (!keys.isEmpty()) {
            redis.del(keys.toArray(String[]::new));
        }
    }

    @Test
    void pointConsumeIsAtomicAndAdapterBucketsAreIsolated() {
        String commandId = UUID.randomUUID().toString();
        String encoded = commandJson(
                commandId,
                System.currentTimeMillis() + 30_000
        );
        redis.hset(commandKey("endpoint-1"), "worker-1", encoded);
        redis.hset(commandKey("endpoint-2"), "worker-1", encoded);

        WorkerCommandEnvelope consumed = runtime.consumeWorkerCommand(
                "endpoint-1",
                "worker-1"
        );

        assertThat(consumed.commandId()).isEqualTo(commandId);
        assertThat(runtime.consumeWorkerCommand(
                "endpoint-1",
                "worker-1"
        )).isNull();
        assertThat(runtime.consumeWorkerCommand(
                "endpoint-2",
                "worker-1"
        )).isNotNull();
    }

    @Test
    void batchConsumeFiltersCorruptAndExpiredCommands() {
        String key = commandKey("endpoint-1");
        redis.hset(
                key,
                "worker-active",
                commandJson(
                        UUID.randomUUID().toString(),
                        System.currentTimeMillis() + 30_000
                )
        );
        redis.hset(
                key,
                "worker-expired",
                commandJson(
                        UUID.randomUUID().toString(),
                        System.currentTimeMillis() - 1
                )
        );
        redis.hset(key, "worker-corrupt", "{bad-json");

        var page = runtime.consumeWorkerCommands(
                "endpoint-1",
                null,
                100
        );

        assertThat(page.workerCommandsByWorkerId())
                .containsOnlyKeys("worker-active");
        assertThat(redis.hlen(key)).isZero();
    }

    @Test
    void resultAppendUsesThreeOutcomeQueuesAndStableJson() {
        String successId = UUID.randomUUID().toString();
        String failureId = UUID.randomUUID().toString();
        String rejectionId = UUID.randomUUID().toString();
        List<SeedResult> results = List.of(
                new SeedResult(successId, "success", "200", "null"),
                new SeedResult(failureId, "failure", "1500", null),
                new SeedResult(rejectionId, "rejection", "3001", null)
        );

        assertThat(runtime.appendSeedResults(results)).isEqualTo(3);

        assertThat(redis.lrange(
                resultKey("success"),
                0,
                -1
        )).containsExactly(
                "{\"commandId\":\"" + successId + "\","
                        + "\"opaqueResultContext\":\"success\","
                        + "\"opaqueResultPayload\":\"null\","
                        + "\"outcomeCode\":\"200\"}"
        );
        assertThat(redis.llen(resultKey("worker-failure")))
                .isEqualTo(1);
        assertThat(redis.llen(resultKey("adapter-rejection")))
                .isEqualTo(1);
        assertThat(redis.exists("rr:" + prefix + ":seed-results"))
                .isZero();
    }

    private String commandKey(String endpointManagerId) {
        return "wd:" + prefix + ":endpoint-manager:"
                + endpointManagerId + ":worker-commands";
    }

    private String resultKey(String outcomeClass) {
        return "rr:" + prefix + ":seed-results:" + outcomeClass;
    }

    private static String commandJson(
            String commandId,
            long executeBeforeMillis
    ) {
        return "{\"commandId\":\"" + commandId + "\","
                + "\"executeBeforeMillis\":" + executeBeforeMillis + ","
                + "\"messageType\":\"TASK_ITEM\","
                + "\"opaqueItem\":\"opaque-item\"}";
    }
}
