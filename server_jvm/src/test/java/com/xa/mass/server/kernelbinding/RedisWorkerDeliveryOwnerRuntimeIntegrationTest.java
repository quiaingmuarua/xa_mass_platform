package com.xa.mass.server.kernelbinding;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.kernel.delivery.redis.RedisWorkerResultRuntime;
import com.xa.mass.kernel.delivery.redis.RedisWorkerCommandRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime.WorkerCommandOfferStatus;
import com.xa.mass.kernel.serviceability.redis.RedisWorkerServiceabilityRuntime;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("redis-owner")
class RedisWorkerDeliveryOwnerRuntimeIntegrationTest {

    private String prefix;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private RedisWorkerCommandRuntime commandRuntime;
    private RedisWorkerResultRuntime resultRuntime;
    private RedisWorkerServiceabilityRuntime serviceabilityRuntime;
    private WorkerDeliveryCodec codec;

    @BeforeEach
    void setUp() {
        prefix = "java-worker-delivery-" + UUID.randomUUID();
        redisClient = RedisClient.create(REDIS_URL);
        connection = redisClient.connect(StringCodec.UTF8);
        redis = connection.sync();
        codec = new WorkerDeliveryCodec();
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
        serviceabilityRuntime = new RedisWorkerServiceabilityRuntime(
                redisClient,
                codec,
                prefix,
                2
        );
    }

    @AfterEach
    void tearDown() {
        if (redis != null) {
            deleteKeys(redis.keys("wd:" + prefix + ":*"));
            deleteKeys(redis.keys("rr:" + prefix + ":*"));
            deleteKeys(redis.keys("ws:{" + prefix + "}:*"));
        }
        if (commandRuntime != null) {
            commandRuntime.close();
        }
        if (resultRuntime != null) {
            resultRuntime.close();
        }
        if (serviceabilityRuntime != null) {
            serviceabilityRuntime.close();
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
        String encoded = commandJson(
                System.currentTimeMillis() + 30_000
        );
        redis.hset(commandKey("endpoint-1"), "worker-1", encoded);
        redis.hset(commandKey("endpoint-2"), "worker-1", encoded);

        DeliveryCommand consumed =
                commandRuntime.consumeWorkerCommand(
                        "endpoint-1",
                        "worker-1"
                );
        assertThat(consumed.messageType()).isEqualTo("test.event");
        assertThat(commandRuntime.consumeWorkerCommand(
                "endpoint-1",
                "worker-1"
        )).isNull();
        assertThat(commandRuntime.consumeWorkerCommand(
                "endpoint-2",
                "worker-1"
        )).isNotNull();

        List<DeliveryReport> results = List.of(
                result("success", "200"),
                DeliveryReport.create(
                        DeliveryEndpoint.WORKER,
                        "worker-1",
                        DeliveryEndpoint.TASK,
                        "test.event",
                        "3500",
                        "null",
                        "failure"
                ),
                DeliveryReport.create(
                        DeliveryEndpoint.ADAPTER,
                        "endpoint-1",
                        DeliveryEndpoint.TASK,
                        "test.event",
                        "23002",
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
                        System.currentTimeMillis() + 30_000
                )
        );
        redis.hset(
                key,
                "worker-expired",
                commandJson(
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

    @Test
    void workerCommandOfferUsesTheExistingHashWithoutReplacingSlots() {
        String key = commandKey("endpoint-1");
        String existing = commandJson(System.currentTimeMillis() + 30_000);
        redis.hset(key, "worker-occupied", existing);
        DeliveryCommand direct = DeliveryCommand.create(
                DeliveryEndpoint.SYSTEM,
                DeliveryEndpoint.WORKER,
                "platform.worker.probe",
                System.currentTimeMillis() + 30_000,
                "null",
                "direct-call:v1:test"
        );

        assertThat(commandRuntime.offerWorkerCommands(
                "endpoint-1",
                Map.of(
                        "worker-occupied", direct,
                        "worker-empty", direct
                )
        )).containsExactlyInAnyOrderEntriesOf(Map.of(
                "worker-occupied", WorkerCommandOfferStatus.OCCUPIED,
                "worker-empty", WorkerCommandOfferStatus.OFFERED
        ));
        assertThat(redis.hget(key, "worker-occupied")).isEqualTo(existing);
        assertThat(commandRuntime.consumeWorkerCommand(
                "endpoint-1",
                "worker-empty"
        )).isEqualTo(direct);
    }

    @Test
    void serviceabilityBridgeConsumesRequestsAndAppendsTheRemainingPrefix() {
        String requestKey = serviceabilityRequestKey("endpoint-1");
        redis.hset(requestKey, "worker-1", "1");
        redis.hset(requestKey, "worker-2", "1");
        redis.hset(requestKey, "worker-3", "1");

        List<String> consumed = serviceabilityRuntime.consumeProbeRequests(
                "endpoint-1",
                2
        );
        assertThat(consumed).hasSize(2).doesNotHaveDuplicates();
        assertThat(redis.hlen(requestKey)).isEqualTo(1);

        DeliveryReport first = serviceabilityResult("worker-1", "CONNECTED");
        DeliveryReport second = serviceabilityResult("worker-2", "UNKNOWN");
        DeliveryReport rejected = serviceabilityResult(
                "worker-3",
                "DISCONNECTED"
        );
        assertThat(serviceabilityRuntime.appendProbeResults(List.of(
                first,
                second,
                rejected
        ))).isEqualTo(2);
        assertThat(redis.lrange(serviceabilityResultKey(), 0, -1))
                .extracting(codec::decodeDeliveryReport)
                .containsExactly(first, second);
        assertThat(serviceabilityRuntime.appendProbeResults(
                List.of(rejected)
        )).isZero();
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

    private String serviceabilityRequestKey(String adapterId) {
        return "ws:{" + prefix + "}:adapter:"
                + adapterId + ":probe-requests";
    }

    private String serviceabilityResultKey() {
        return "ws:{" + prefix + "}:probe-results";
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

    private static String commandJson(long executeBeforeMillis) {
        return "{\"dst\":\"WORKER\","
                + "\"executeBeforeMillis\":" + executeBeforeMillis + ","
                + "\"forward\":\"context\","
                + "\"messageType\":\"test.event\","
                + "\"payload\":\"opaque-item\","
                + "\"src\":\"TASK\"}";
    }

    private static DeliveryReport result(
            String forward,
            String outcomeCode
    ) {
        DeliveryEndpoint source = !"200".equals(outcomeCode)
                && outcomeCode.startsWith("2")
                ? DeliveryEndpoint.ADAPTER
                : DeliveryEndpoint.WORKER;
        return DeliveryReport.create(
                source,
                source == DeliveryEndpoint.ADAPTER
                        ? "endpoint-1"
                        : "worker-1",
                DeliveryEndpoint.TASK,
                "test.event",
                outcomeCode,
                "null",
                forward
        );
    }

    private static DeliveryReport serviceabilityResult(
            String workerId,
            String state
    ) {
        return DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                "endpoint-1",
                DeliveryEndpoint.KERNEL,
                "platform.adapter.worker-connections.snapshot",
                "200",
                "{\"stateByWorkerId\":{\""
                        + workerId
                        + "\":\""
                        + state
                        + "\"}}",
                "worker-serviceability:v1:123"
        );
    }
}
