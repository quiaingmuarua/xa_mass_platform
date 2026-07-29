package com.xa.mass.server.kernelbinding;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.kernel.delivery.redis.RedisWorkerResultRuntime;
import com.xa.mass.kernel.delivery.redis.RedisWorkerCommandRuntime;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private RedisWorkerResultRuntime resultRuntime;

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
        resultRuntime = new RedisWorkerResultRuntime(
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
        String messageId = UUID.randomUUID().toString();
        String encoded = commandJson(
                messageId,
                System.currentTimeMillis() + 30_000
        );
        redis.hset(commandKey("endpoint-1"), "worker-1", encoded);
        redis.hset(commandKey("endpoint-2"), "worker-1", encoded);

        WorkerCommand consumed =
                commandRuntime.consumeWorkerCommand(
                        "endpoint-1",
                        "worker-1"
                );
        assertThat(consumed.messageId()).isEqualTo(messageId);
        assertThat(commandRuntime.consumeWorkerCommand(
                "endpoint-1",
                "worker-1"
        )).isNull();
        assertThat(commandRuntime.consumeWorkerCommand(
                "endpoint-2",
                "worker-1"
        )).isNotNull();

        List<WorkerResult> results = List.of(
                result(messageId, "success", "200"),
                new WorkerResult(
                        UUID.randomUUID().toString(),
                        WorkerMessageEndpoint.TASK,
                        "test.event",
                        "1500",
                        "null",
                        "failure"
                ),
                new WorkerResult(
                        UUID.randomUUID().toString(),
                        WorkerMessageEndpoint.TASK,
                        "test.event",
                        "3001",
                        "null",
                        "rejection"
                )
        );
        assertThat(resultRuntime.appendWorkerResults(results)).isEqualTo(3);
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

        var commands = commandRuntime.consumeWorkerCommands(
                "endpoint-1",
                100
        );
        assertThat(commands).containsOnlyKeys("worker-active");
        assertThat(redis.hlen(key)).isZero();
    }

    @Test
    void repeatedNoCursorBatchConsumeDrainsTheObservedHash() {
        String key = commandKey("endpoint-1");
        Set<String> expectedWorkerIds = new HashSet<>();
        for (int index = 0; index < 41; index++) {
            String workerId = "worker-" + index;
            expectedWorkerIds.add(workerId);
            redis.hset(
                    key,
                    workerId,
                    commandJson(
                            UUID.randomUUID().toString(),
                            System.currentTimeMillis() + 30_000
                    )
            );
        }

        Set<String> consumedWorkerIds = new HashSet<>();
        while (redis.hlen(key) > 0) {
            var commands = commandRuntime.consumeWorkerCommands(
                    "endpoint-1",
                    7
            );
            assertThat(commands).hasSizeLessThanOrEqualTo(7);
            assertThat(consumedWorkerIds)
                    .doesNotContainAnyElementsOf(commands.keySet());
            consumedWorkerIds.addAll(commands.keySet());
        }

        assertThat(consumedWorkerIds).isEqualTo(expectedWorkerIds);
        assertThat(redis.hlen(key)).isZero();
    }

    @Test
    void batchConsumeReadsRedisTimeOnce() {
        String key = commandKey("endpoint-1");
        for (int index = 0; index < 3; index++) {
            redis.hset(
                    key,
                    "worker-" + index,
                    commandJson(
                            UUID.randomUUID().toString(),
                            System.currentTimeMillis() + 30_000
                    )
            );
        }

        long callsBefore = commandCalls("time");
        var commands = commandRuntime.consumeWorkerCommands(
                "endpoint-1",
                100
        );
        long callsAfter = commandCalls("time");

        assertThat(commands).hasSize(3);
        assertThat(callsAfter - callsBefore).isEqualTo(1);
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
        return "rr:" + prefix + ":worker-results:" + outcomeClass;
    }

    private long commandCalls(String command) {
        String prefix = "cmdstat_" + command + ":";
        for (String line : redis.info("commandstats").split("\\R")) {
            if (!line.startsWith(prefix)) {
                continue;
            }
            for (String field : line.substring(prefix.length()).split(",")) {
                if (field.startsWith("calls=")) {
                    return Long.parseLong(field.substring("calls=".length()));
                }
            }
        }
        return 0;
    }

    private static String commandJson(
            String messageId,
            long executeBeforeMillis
    ) {
        return "{\"dst\":\"WORKER\","
                + "\"executeBeforeMillis\":" + executeBeforeMillis + ","
                + "\"forward\":\"context\","
                + "\"messageId\":\"" + messageId + "\","
                + "\"messageType\":\"test.event\","
                + "\"payload\":\"opaque-item\","
                + "\"src\":\"TASK\"}";
    }

    private static WorkerResult result(
            String messageId,
            String forward,
            String outcomeCode
    ) {
        return new WorkerResult(
                messageId,
                WorkerMessageEndpoint.TASK,
                "test.event",
                outcomeCode,
                "null",
                forward
        );
    }
}
