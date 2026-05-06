package com.xa.mass.runtime.redis;

import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.ResultApplyStatus;
import com.xa.mass.runtime.api.TaskWorkEnvelope;
import com.xa.mass.runtime.api.TaskWorkResult;
import com.xa.mass.runtime.api.WorkEnqueueOptions;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisTaskWorkRuntimeTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private StatefulRedisConnection<String, String> observerConnection;
    private RedisCommands<String, String> commands;
    private RedisTaskWorkRuntime runtime;
    private RedisTaskWorkKeyspace keyspace;
    private AtomicReference<Instant> now;

    @BeforeEach
    void setUp() {
        String redisUri = System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
        try {
            redisClient = RedisClient.create(redisUri);
            connection = redisClient.connect();
            observerConnection = redisClient.connect();
            commands = observerConnection.sync();
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for runtime test: " + ex.getMessage());
            throw ex;
        }
        now = new AtomicReference<>(Instant.parse("2026-05-06T00:00:00Z"));
        keyspace = new RedisTaskWorkKeyspace("xa:mass:test:redis-runtime:" + UUID.randomUUID());
        runtime = new RedisTaskWorkRuntime(connection, keyspace, 1024, now::get);
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.shutdown();
        }
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
        if (observerConnection != null && observerConnection.isOpen()) {
            observerConnection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void enqueueStoresReadyIndexesAndWorkHashInRedis() {
        runtime.enqueue(item("task-1", "msg-1"), WorkEnqueueOptions.DEFAULT);

        assertEquals(List.of("task-1"), commands.zrange(keyspace.readyTasksZset(), 0, -1));
        assertEquals(List.of("msg-1"), commands.lrange(keyspace.taskReadyQueue("task-1"), 0, -1));
        assertEquals(Set.of("msg-1"), commands.smembers(keyspace.taskMembersSet("task-1")));
        assertEquals(Set.of("task-1"), commands.smembers(keyspace.taskRegistrySet()));
        assertEquals("demo.event", commands.hget(keyspace.taskWorkHash("task-1", "msg-1"), RedisTaskWorkKeyspace.FIELD_EVENT_CODE));
        assertEquals("1", commands.hget(keyspace.taskStatsHash("task-1"), RedisTaskWorkKeyspace.COUNTER_TOTAL_COUNT));
        assertEquals("1", commands.hget(keyspace.runtimeStatsHash(), RedisTaskWorkKeyspace.COUNTER_READY_COUNT));
    }

    @Test
    void delayedWorkRemainsInDelayedIndexesUntilVisible() {
        runtime.enqueue(delayedItem("task-delayed", "msg-delay", now.get().plusSeconds(30)), WorkEnqueueOptions.DEFAULT);

        assertEquals(List.of(keyspace.workMember("task-delayed", "msg-delay")),
                commands.zrange(keyspace.delayedWorkZset(), 0, -1));
        assertEquals(List.of("msg-delay"), commands.zrange(keyspace.taskDelayedZset("task-delayed"), 0, -1));
        assertTrue(commands.zrange(keyspace.readyTasksZset(), 0, -1).isEmpty());
        assertFalse(runtime.hasReadyWork("task-delayed"));

        now.set(now.get().plusSeconds(31));

        assertTrue(runtime.hasReadyWork("task-delayed"));
        assertEquals(List.of("task-delayed"), runtime.readyTaskIds(10));
        assertTrue(commands.zrange(keyspace.delayedWorkZset(), 0, -1).isEmpty());
        assertTrue(commands.zrange(keyspace.taskDelayedZset("task-delayed"), 0, -1).isEmpty());
        assertEquals(List.of("msg-delay"), commands.lrange(keyspace.taskReadyQueue("task-delayed"), 0, -1));
    }

    @Test
    void claimCreatesLeaseAndWorkerIndexesAndApplySuccessRemovesThem() {
        runtime.enqueue(item("task-lease", "msg-1"), WorkEnqueueOptions.DEFAULT);

        ClaimedTaskWork claimed = runtime.claimReady(
                "task-lease",
                List.of(new WorkerClaimTarget("worker-1", "ctx-1", "batch-1", 1)),
                1,
                45
        ).get(0);

        String workMember = keyspace.workMember("task-lease", "msg-1");
        assertEquals(claimed.leaseToken(),
                commands.hget(keyspace.taskLeaseHash("task-lease", "msg-1"), RedisTaskWorkKeyspace.FIELD_LEASE_TOKEN));
        assertEquals(Set.of(workMember), commands.smembers(keyspace.taskActiveSet("task-lease")));
        assertEquals(Set.of(workMember), commands.smembers(keyspace.workerActiveSet("worker-1")));
        assertEquals(List.of(workMember), commands.zrange(keyspace.leaseExpiryZset(), 0, -1));

        assertEquals(ResultApplyStatus.SUCCESS_APPLIED, runtime.applyResult(
                TaskWorkResult.success("task-lease", "msg-1", claimed.leaseToken(), "done", Map.of("ok", true))
        ).status());

        assertTrue(commands.smembers(keyspace.taskActiveSet("task-lease")).isEmpty());
        assertTrue(commands.smembers(keyspace.workerActiveSet("worker-1")).isEmpty());
        assertTrue(commands.zrange(keyspace.leaseExpiryZset(), 0, -1).isEmpty());
        assertNull(commands.hget(keyspace.taskLeaseHash("task-lease", "msg-1"), RedisTaskWorkKeyspace.FIELD_LEASE_TOKEN));
        assertTrue(commands.smembers(keyspace.taskMembersSet("task-lease")).isEmpty());
    }

    @Test
    void pollExpiredLeasesRemovesOnlyExpiryIndexMembershipUntilEngineFinalizes() {
        runtime.enqueue(item("task-expiry", "msg-1"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork claimed = runtime.claimReady(
                "task-expiry",
                List.of(new WorkerClaimTarget("worker-1", "ctx-1", "batch-1", 1)),
                1,
                10
        ).get(0);

        assertEquals(List.of(claimed.leaseToken()),
                runtime.pollExpiredLeases(10, now.get().plusSeconds(11)).stream()
                        .map(lease -> lease.leaseToken())
                        .toList());

        assertTrue(commands.zrange(keyspace.leaseExpiryZset(), 0, -1).isEmpty());
        assertEquals(claimed.leaseToken(),
                commands.hget(keyspace.taskLeaseHash("task-expiry", "msg-1"), RedisTaskWorkKeyspace.FIELD_LEASE_TOKEN));
        assertTrue(runtime.hasActiveLeaseForWorker("task-expiry", "worker-1"));
    }

    @Test
    void shutdownClearsOnlyCurrentNamespaceResidue() {
        RedisTaskWorkKeyspace other = new RedisTaskWorkKeyspace("xa:mass:test:redis-runtime:other:" + UUID.randomUUID());
        commands.sadd(other.taskRegistrySet(), "foreign-task");
        commands.rpush(other.taskReadyQueue("foreign-task"), "foreign-msg");
        commands.zadd(other.readyTasksZset(), now.get().toEpochMilli(), "foreign-task");

        runtime.enqueue(item("task-shutdown", "msg-1"), WorkEnqueueOptions.DEFAULT);
        runtime.enqueue(delayedItem("task-shutdown", "msg-2", now.get().plusSeconds(60)), WorkEnqueueOptions.DEFAULT);

        runtime.shutdown();

        assertTrue(commands.exists(
                keyspace.taskRegistrySet(),
                keyspace.readyTasksZset(),
                keyspace.delayedWorkZset(),
                keyspace.taskReadyQueue("task-shutdown"),
                keyspace.taskDelayedZset("task-shutdown"),
                keyspace.taskMembersSet("task-shutdown"),
                keyspace.taskWorkHash("task-shutdown", "msg-1"),
                keyspace.taskWorkHash("task-shutdown", "msg-2")
        ) == 0);

        assertEquals(Set.of("foreign-task"), commands.smembers(other.taskRegistrySet()));
        assertEquals(List.of("foreign-msg"), commands.lrange(other.taskReadyQueue("foreign-task"), 0, -1));
        assertEquals(List.of("foreign-task"), commands.zrange(other.readyTasksZset(), 0, -1));
    }

    private TaskWorkEnvelope item(String taskId, String messageId) {
        return new TaskWorkEnvelope(taskId, messageId, "demo.event",
                Map.of("target", messageId), null, 0, 3, null, null, now.get());
    }

    private TaskWorkEnvelope delayedItem(String taskId, String messageId, Instant nextVisibleAt) {
        return new TaskWorkEnvelope(taskId, messageId, "demo.event",
                Map.of("target", messageId), null, 0, 3, null, nextVisibleAt, now.get());
    }
}
