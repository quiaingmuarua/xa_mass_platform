package com.xa.mass.server.kernelbinding;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.kernel.delivery.redis.RedisWorkerResultRuntime;
import com.xa.mass.kernel.delivery.redis.RedisWorkerCommandRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime.WorkerCommandOfferStatus;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.serviceability.redis.RedisWorkerServiceabilityRuntime;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime
        .ProbeRequestOfferStatus;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol
        .DeliveryReportOutcomeClass;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("redis-owner")
class RedisWorkerDeliveryOwnerRuntimeIntegrationTest {

    private RedisTestScope testScope;
    private RedisKeyspace keyspace;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private RedisWorkerCommandRuntime commandRuntime;
    private RedisWorkerResultRuntime resultRuntime;
    private RedisWorkerServiceabilityRuntime serviceabilityRuntime;
    private WorkerDeliveryCodec codec;

    @BeforeEach
    void setUp() {
        testScope = RedisTestScope.create("java_worker_delivery");
        keyspace = testScope.keyspace();
        redisClient = RedisClient.create(REDIS_URL);
        connection = redisClient.connect(StringCodec.UTF8);
        redis = connection.sync();
        codec = new WorkerDeliveryCodec();
        commandRuntime = new RedisWorkerCommandRuntime(
                redisClient,
                codec,
                keyspace
        );
        resultRuntime = new RedisWorkerResultRuntime(
                redisClient,
                codec,
                keyspace
        );
        serviceabilityRuntime = new RedisWorkerServiceabilityRuntime(
                redisClient,
                codec,
                keyspace,
                2,
                2
        );
    }

    @AfterEach
    void tearDown() {
        if (redis != null) {
            testScope.cleanup(redis);
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

        assertThat(resultRuntime.consumeWorkerResults(
                DeliveryReportOutcomeClass.SUCCESS,
                100
        )).containsExactly(results.get(0));
        assertThat(resultRuntime.consumeWorkerResults(
                DeliveryReportOutcomeClass.WORKER_FAILURE,
                100
        )).containsExactly(results.get(1));
        assertThat(resultRuntime.consumeWorkerResults(
                DeliveryReportOutcomeClass.ADAPTER_REJECTION,
                100
        )).containsExactly(results.get(2));
        assertThat(redis.llen(resultKey("success"))).isZero();
    }

    @Test
    void resultConsumeIsFifoAndDropsConsumedMalformedMembers() {
        DeliveryReport first = result("first", "200");
        DeliveryReport second = result("second", "200");
        redis.rpush(
                resultKey("success"),
                codec.encodeDeliveryReport(first),
                "{bad-json",
                codec.encodeDeliveryReport(second)
        );

        assertThat(resultRuntime.consumeWorkerResults(
                DeliveryReportOutcomeClass.SUCCESS,
                100
        )).containsExactly(first, second);
        assertThat(redis.llen(resultKey("success"))).isZero();
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
    void batchConsumeReturnsAllActiveObservedCommands() {
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

        var commands = commandRuntime.consumeWorkerCommands(
                "endpoint-1",
                100
        );

        assertThat(commands).hasSize(3);
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
        assertThat(serviceabilityRuntime.offerProbeRequests(
                "endpoint-1",
                List.of("worker-1", "worker-2")
        )).containsExactly(
                Map.entry("worker-1", ProbeRequestOfferStatus.OFFERED),
                Map.entry("worker-2", ProbeRequestOfferStatus.OFFERED)
        );
        assertThat(serviceabilityRuntime.offerProbeRequests(
                "endpoint-1",
                List.of("worker-1", "worker-3")
        )).containsExactly(
                Map.entry(
                        "worker-1",
                        ProbeRequestOfferStatus.ALREADY_REQUESTED
                ),
                Map.entry("worker-3", ProbeRequestOfferStatus.CAPACITY)
        );
        assertThat(serviceabilityRuntime.offerProbeRequests(
                "endpoint-2",
                List.of("worker-3")
        )).containsEntry("worker-3", ProbeRequestOfferStatus.OFFERED);

        List<String> consumed = serviceabilityRuntime.consumeProbeRequests(
                "endpoint-1",
                2
        );
        assertThat(consumed).hasSize(2).doesNotHaveDuplicates();
        assertThat(redis.hlen(requestKey)).isZero();

        DeliveryReport first = serviceabilityResult("worker-1", "CONNECTED");
        DeliveryReport second = serviceabilityResult("worker-2", "UNKNOWN");
        DeliveryReport rejected = serviceabilityResult(
                "worker-3",
                "DISCONNECTED"
        );
        assertThat(serviceabilityRuntime.appendAdapterEvidenceResults(List.of(
                first,
                second,
                rejected
        ))).isEqualTo(2);
        assertThat(redis.lrange(serviceabilityResultKey(), 0, -1))
                .extracting(codec::decodeDeliveryReport)
                .containsExactly(first, second);
        assertThat(serviceabilityRuntime.appendAdapterEvidenceResults(
                List.of(rejected)
        )).isZero();

        assertThat(serviceabilityRuntime.consumeAdapterEvidenceResults(2))
                .containsExactly(first, second);
        assertThat(redis.llen(serviceabilityResultKey())).isZero();

        DeliveryReport wrongEndpoint = DeliveryReport.create(
                DeliveryEndpoint.ADAPTER,
                "endpoint-1",
                DeliveryEndpoint.SYSTEM,
                "platform.adapter.worker-connections.snapshot",
                "200",
                "{}",
                "worker-serviceability:v1:1"
        );
        redis.rpush(
                serviceabilityResultKey(),
                "{bad-json",
                codec.encodeDeliveryReport(wrongEndpoint),
                codec.encodeDeliveryReport(first)
        );
        assertThat(serviceabilityRuntime.consumeAdapterEvidenceResults(100))
                .containsExactly(first);
        assertThat(redis.llen(serviceabilityResultKey())).isZero();
    }

    private String commandKey(String endpointManagerId) {
        return keyspace.base() + ":delivery:commands:" + endpointManagerId;
    }

    private String resultKey(String outcomeClass) {
        return keyspace.base() + ":result:routing:" + outcomeClass;
    }

    private String serviceabilityRequestKey(String adapterId) {
        return keyspace.base() + ":worker:serviceability:adapter:"
                + adapterId + ":probe_requests";
    }

    private String serviceabilityResultKey() {
        return keyspace.base()
                + ":worker:serviceability:evidence_results";
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
