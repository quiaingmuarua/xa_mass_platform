package com.xa.mass.server.assembly.kernel;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.kernel.assignment.CandidateWorkerCache.CandidateWorkerEntry;
import com.xa.mass.kernel.assignment.redis.RedisCandidateWorkerCache;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime.WorkerCommandAppendStatus;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime.WorkerCommandOfferStatus;
import com.xa.mass.kernel.delivery.redis.RedisWorkerCommandRuntime;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("redis-owner")
class RedisAssignmentDispatchRuntimeIntegrationTest {

    private RedisTestScope testScope;
    private RedisKeyspace keyspace;
    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private RedisCommands<String, String> redis;
    private RedisCandidateWorkerCache candidateCache;
    private RedisWorkerCommandRuntime commands;

    @BeforeEach
    void setUp() {
        testScope = RedisTestScope.create("java_assignment_dispatch");
        keyspace = testScope.keyspace();
        redisClient = RedisClient.create(REDIS_URL);
        connection = redisClient.connect(StringCodec.UTF8);
        redis = connection.sync();
        candidateCache = new RedisCandidateWorkerCache(redisClient, keyspace);
        commands = new RedisWorkerCommandRuntime(
                redisClient,
                new WorkerDeliveryCodec(),
                keyspace
        );
    }

    @AfterEach
    void tearDown() {
        if (redis != null) {
            testScope.cleanup(redis);
        }
        if (candidateCache != null) {
            candidateCache.close();
        }
        if (commands != null) {
            commands.close();
        }
        if (connection != null) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void candidateEvidenceIsBoundedExpiringAndDestructivelyConsumed() {
        long nowMillis = redisTimeMillis();
        CandidateWorkerEntry first = new CandidateWorkerEntry(
                "worker-1",
                "group-1",
                101L
        );
        CandidateWorkerEntry second = new CandidateWorkerEntry(
                "worker-2",
                "group-1",
                102L
        );
        candidateCache.appendCandidateWorkers(
                "candidate-1",
                List.of(first, second),
                nowMillis + 60_000
        );
        assertThat(candidateCache.candidateWorkerCounts(List.of(
                "candidate-1",
                "candidate-1",
                "missing"
        ))).containsExactly(
                Map.entry("candidate-1", 2),
                Map.entry("missing", 0)
        );

        List<CandidateWorkerEntry> firstPage = candidateCache
                .consumeCandidateWorkers("candidate-1", 1);
        List<CandidateWorkerEntry> secondPage = candidateCache
                .consumeCandidateWorkers("candidate-1", 10);
        assertThat(firstPage).hasSize(1);
        assertThat(secondPage).hasSize(1);
        assertThat(List.of(firstPage.getFirst(), secondPage.getFirst()))
                .containsExactlyInAnyOrder(first, second);
        assertThat(candidateCache.consumeCandidateWorkers(
                "candidate-1",
                10
        )).isEmpty();

        redis.zadd(
                keyspace.base()
                        + ":dispatch:candidate:old-shape:workers",
                nowMillis + 60_000,
                """
                        {"workerId":"worker-old",\
                        "workerGroupId":"group-1",\
                        "endpointManagerId":"adapter-old",\
                        "workerLeaseScore":103}
                        """
        );
        assertThat(candidateCache.consumeCandidateWorkers(
                "old-shape",
                10
        )).isEmpty();

        redis.zadd(
                keyspace.base()
                        + ":dispatch:candidate:corrupt:workers",
                nowMillis + 60_000,
                "{not-json"
        );
        assertThat(candidateCache.consumeCandidateWorkers(
                "corrupt",
                10
        )).isEmpty();
        assertThat(redis.exists(
                keyspace.base()
                        + ":dispatch:candidate:corrupt:workers"
        )).isZero();
    }

    @Test
    void authoritativeTaskAppendReplacesAnUnconsumedDirectCommand() {
        long deadline = redisTimeMillis() + 60_000;
        DeliveryCommand direct = DeliveryCommand.create(
                DeliveryEndpoint.SYSTEM,
                DeliveryEndpoint.WORKER,
                "extension.worker.direct",
                deadline,
                "{}",
                "direct-call:v1:1"
        );
        DeliveryCommand task = DeliveryCommand.create(
                DeliveryEndpoint.TASK,
                DeliveryEndpoint.WORKER,
                "extension.worker.task",
                deadline,
                "{\"value\":1}",
                "{\"taskId\":\"task-1\"}"
        );

        assertThat(commands.offerWorkerCommands(
                "adapter-1",
                Map.of("worker-1", direct)
        )).containsEntry("worker-1", WorkerCommandOfferStatus.OFFERED);
        assertThat(commands.appendWorkerCommands(
                "adapter-1",
                Map.of("worker-1", task)
        )).containsEntry("worker-1", WorkerCommandAppendStatus.REPLACED);

        DeliveryCommand consumed = commands.consumeWorkerCommand(
                "adapter-1",
                "worker-1"
        );
        assertThat(consumed).isEqualTo(task);
        assertThat(commands.consumeWorkerCommand(
                "adapter-1",
                "worker-1"
        )).isNull();

        assertThat(commands.appendWorkerCommands(
                "adapter-2",
                Map.of("worker-1", task)
        )).containsEntry("worker-1", WorkerCommandAppendStatus.APPENDED);
        assertThat(commands.consumeWorkerCommand(
                "adapter-1",
                "worker-1"
        )).isNull();
        assertThat(commands.consumeWorkerCommand(
                "adapter-2",
                "worker-1"
        )).isEqualTo(task);
    }

    private long redisTimeMillis() {
        List<String> parts = redis.time();
        return Long.parseLong(parts.get(0)) * 1_000L
                + Long.parseLong(parts.get(1)) / 1_000L;
    }
}
