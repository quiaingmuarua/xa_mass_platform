package com.xa.mass.server.kernelbinding;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.kernel.delivery.redis.RedisSeedResultRuntime;
import com.xa.mass.kernel.delivery.redis.RedisWorkerCommandRuntime;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class RedisWorkerDeliveryOwnerRuntimeIntegrationTest {

    private static final String REDIS_URL =
            System.getenv("KERNEL_DESIGN_REDIS_URL");
    private String prefix;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private RedisWorkerCommandRuntime commandRuntime;
    private RedisSeedResultRuntime resultRuntime;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(
                REDIS_URL != null && !REDIS_URL.isBlank(),
                "KERNEL_DESIGN_REDIS_URL is not configured"
        );
        prefix = "java-worker-delivery-" + UUID.randomUUID();
        redisClient = RedisClient.create(REDIS_URL);
        connection = redisClient.connect(StringCodec.UTF8);
        redis = connection.sync();
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        commandRuntime = new RedisWorkerCommandRuntime(
                redisClient,
                codec,
                prefix
        );
        resultRuntime = new RedisSeedResultRuntime(
                redisClient,
                codec,
                prefix
        );
    }

    @AfterEach
    void tearDown() {
        if (redis != null) {
            deleteKeys(redis.keys("wd:" + prefix + ":*"));
            deleteKeys(redis.keys("rr:" + prefix + ":*"));
        }
        if (commandRuntime != null) {
            commandRuntime.close();
        }
        if (resultRuntime != null) {
            resultRuntime.close();
        }
        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void commandConsumeAndResultAppendRemainSeparateOwners() {
        String commandId = UUID.randomUUID().toString();
        String encoded = commandJson(
                commandId,
                System.currentTimeMillis() + 30_000
        );
        redis.hset(commandKey("endpoint-1"), "worker-1", encoded);
        redis.hset(commandKey("endpoint-2"), "worker-1", encoded);

        WorkerCommandEnvelope consumed =
                commandRuntime.consumeWorkerCommand(
                        "endpoint-1",
                        "worker-1"
                );
        assertThat(consumed.commandId()).isEqualTo(commandId);
        assertThat(commandRuntime.consumeWorkerCommand(
                "endpoint-1",
                "worker-1"
        )).isNull();
        assertThat(commandRuntime.consumeWorkerCommand(
                "endpoint-2",
                "worker-1"
        )).isNotNull();

        List<SeedResult> results = List.of(
                new SeedResult(commandId, "success", "200", "null"),
                new SeedResult(
                        UUID.randomUUID().toString(),
                        "failure",
                        "1500",
                        null
                ),
                new SeedResult(
                        UUID.randomUUID().toString(),
                        "rejection",
                        "3001",
                        null
                )
        );
        assertThat(resultRuntime.appendSeedResults(results)).isEqualTo(3);
        assertThat(redis.llen(resultKey("success"))).isEqualTo(1);
        assertThat(redis.llen(resultKey("worker-failure"))).isEqualTo(1);
        assertThat(redis.llen(resultKey("adapter-rejection"))).isEqualTo(1);
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

        var page = commandRuntime.consumeWorkerCommands(
                "endpoint-1",
                null,
                100
        );
        assertThat(page.workerCommandsByWorkerId())
                .containsOnlyKeys("worker-active");
        assertThat(redis.hlen(key)).isZero();
    }

    private void deleteKeys(java.util.Collection<String> keys) {
        if (!keys.isEmpty()) {
            redis.del(keys.toArray(String[]::new));
        }
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
