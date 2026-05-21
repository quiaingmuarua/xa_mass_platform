package com.xa.mass.runtime.redis;

import com.xa.mass.runtime.api.ClaimedTaskWork;
import com.xa.mass.runtime.api.ResultApplyOutcome;
import com.xa.mass.runtime.api.ResultApplyStatus;
import com.xa.mass.runtime.api.TaskWorkEnvelope;
import com.xa.mass.runtime.api.TaskWorkResult;
import com.xa.mass.runtime.api.WorkEnqueueOutcome;
import com.xa.mass.runtime.api.WorkEnqueueOptions;
import com.xa.mass.runtime.api.WorkEnqueueStatus;
import com.xa.mass.runtime.api.WorkerClaimTarget;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
        redisClient = RedisRuntimeTestSupport.createClientOrSkip("runtime test");
        connection = redisClient.connect();
        observerConnection = redisClient.connect();
        commands = observerConnection.sync();
        now = new AtomicReference<>(Instant.parse("2026-05-06T00:00:00Z"));
        keyspace = new RedisTaskWorkKeyspace(RedisRuntimeTestSupport.namespace("redis-runtime"));
        runtime = new RedisTaskWorkRuntime(connection, keyspace, 1024, now::get);
    }

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.shutdown();
        }
        RedisRuntimeTestSupport.cleanupNamespace(commands, keyspace == null ? null : keyspace.namespace());
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
                List.of(WorkerClaimTarget.workerLevel("worker-1", "batch-1", 1)),
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
        assertEquals(com.xa.mass.runtime.api.TaskWorkFinalStatus.SUCCESS,
                runtime.getRecentFinalReceipt("task-lease", "msg-1").orElseThrow().status());
    }

    @Test
    void pollExpiredLeasesRemovesOnlyExpiryIndexMembershipUntilEngineFinalizes() {
        runtime.enqueue(item("task-expiry", "msg-1"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork claimed = runtime.claimReady(
                "task-expiry",
                List.of(WorkerClaimTarget.workerLevel("worker-1", "batch-1", 1)),
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
    void shutdownPreservesRedisRuntimeStateForRestartRecovery() {
        RedisTaskWorkKeyspace other = new RedisTaskWorkKeyspace(RedisRuntimeTestSupport.namespace("redis-runtime-other"));
        try {
            commands.sadd(other.taskRegistrySet(), "foreign-task");
            commands.rpush(other.taskReadyQueue("foreign-task"), "foreign-msg");
            commands.zadd(other.readyTasksZset(), now.get().toEpochMilli(), "foreign-task");

            runtime.enqueue(item("task-shutdown", "msg-1"), WorkEnqueueOptions.DEFAULT);
            ClaimedTaskWork claimed = runtime.claimReady(
                    "task-shutdown",
                    List.of(WorkerClaimTarget.workerLevel("worker-1", "batch-1", 1)),
                    1,
                    10
            ).get(0);
            runtime.enqueue(delayedItem("task-shutdown", "msg-2", now.get().plusSeconds(60)), WorkEnqueueOptions.DEFAULT);

            runtime.shutdown();

            String workMember = keyspace.workMember("task-shutdown", "msg-1");
            assertTrue(commands.exists(
                    keyspace.taskRegistrySet(),
                    keyspace.delayedWorkZset(),
                    keyspace.taskDelayedZset("task-shutdown"),
                    keyspace.taskMembersSet("task-shutdown"),
                    keyspace.taskWorkHash("task-shutdown", "msg-1"),
                    keyspace.taskLeaseHash("task-shutdown", "msg-1")
            ) > 0);
            assertEquals(Set.of(workMember), commands.smembers(keyspace.taskActiveSet("task-shutdown")));
            assertEquals(Set.of(workMember), commands.smembers(keyspace.workerActiveSet("worker-1")));
            assertEquals(List.of(workMember), commands.zrange(keyspace.leaseExpiryZset(), 0, -1));

            StatefulRedisConnection<String, String> restartedConnection = redisClient.connect();
            RedisTaskWorkRuntime restarted = new RedisTaskWorkRuntime(restartedConnection, keyspace, 1024, now::get);
            try {
                assertTrue(restarted.hasActiveLeaseForWorker("task-shutdown", "worker-1"));
                assertEquals(List.of(claimed.leaseToken()),
                        restarted.pollExpiredLeases(10, now.get().plusSeconds(11)).stream()
                                .map(lease -> lease.leaseToken())
                                .toList());
                assertEquals(ResultApplyStatus.RETRY_SCHEDULED, restarted.applyResult(TaskWorkResult.expired(
                        "task-shutdown",
                        "msg-1",
                        claimed.leaseToken(),
                        "restart recovery lease expiry",
                        true
                )).status());
                assertFalse(restarted.hasActiveLeaseForWorker("task-shutdown", "worker-1"));
                assertEquals(List.of("msg-1"), restarted.claimReady(
                                "task-shutdown",
                                List.of(WorkerClaimTarget.workerLevel("worker-2", "batch-2", 1)),
                                1,
                                10
                        ).stream()
                        .map(ClaimedTaskWork::messageId)
                        .toList());
            } finally {
                restarted.shutdown();
                if (restartedConnection.isOpen()) {
                    restartedConnection.close();
                }
            }

            assertEquals(Set.of("foreign-task"), commands.smembers(other.taskRegistrySet()));
            assertEquals(List.of("foreign-msg"), commands.lrange(other.taskReadyQueue("foreign-task"), 0, -1));
            assertEquals(List.of("foreign-task"), commands.zrange(other.readyTasksZset(), 0, -1));
        } finally {
            RedisRuntimeTestSupport.cleanupNamespace(commands, other.namespace());
        }
    }

    @Test
    void namespacesDoNotShareRuntimeStateOrCleanup() {
        RedisTaskWorkKeyspace otherKeyspace = new RedisTaskWorkKeyspace(RedisRuntimeTestSupport.namespace("redis-runtime-isolated"));
        StatefulRedisConnection<String, String> otherConnection = redisClient.connect();
        RedisTaskWorkRuntime otherRuntime = new RedisTaskWorkRuntime(otherConnection, otherKeyspace, 1024, now::get);
        try {
            runtime.enqueue(item("shared-task", "main-msg"), WorkEnqueueOptions.DEFAULT);
            otherRuntime.enqueue(item("shared-task", "other-msg"), WorkEnqueueOptions.DEFAULT);

            assertEquals(List.of("main-msg"), commands.lrange(keyspace.taskReadyQueue("shared-task"), 0, -1));
            assertEquals(List.of("other-msg"), commands.lrange(otherKeyspace.taskReadyQueue("shared-task"), 0, -1));

            RedisRuntimeTestSupport.cleanupNamespace(commands, keyspace.namespace());

            assertEquals(0, commands.exists(keyspace.taskReadyQueue("shared-task"), keyspace.taskRegistrySet()));
            assertEquals(List.of("other-msg"), commands.lrange(otherKeyspace.taskReadyQueue("shared-task"), 0, -1));
            assertEquals(Set.of("shared-task"), commands.smembers(otherKeyspace.taskRegistrySet()));
        } finally {
            otherRuntime.shutdown();
            if (otherConnection.isOpen()) {
                otherConnection.close();
            }
            RedisRuntimeTestSupport.cleanupNamespace(commands, otherKeyspace.namespace());
        }
    }

    @Test
    void discardTask_clearsTaskBoundedIndexesWithoutTouchingOtherTask() {
        runtime.enqueue(item("task-a", "msg-ready"), WorkEnqueueOptions.DEFAULT);
        runtime.enqueue(delayedItem("task-a", "msg-delayed", now.get().plusSeconds(30)), WorkEnqueueOptions.DEFAULT);
        runtime.enqueue(item("task-b", "msg-foreign"), WorkEnqueueOptions.DEFAULT);
        ClaimedTaskWork claimed = runtime.claimReady(
                "task-a",
                List.of(WorkerClaimTarget.workerLevel("worker-1", "batch-1", 1)),
                1,
                45
        ).get(0);
        runtime.applyResult(TaskWorkResult.success("task-a", claimed.messageId(), claimed.leaseToken(), "done", Map.of()));

        long discarded = runtime.discardTask("task-a");

        assertEquals(1L, discarded);
        assertEquals(0, commands.exists(
                keyspace.taskReadyQueue("task-a"),
                keyspace.taskDelayedZset("task-a"),
                keyspace.taskMembersSet("task-a"),
                keyspace.taskStatsHash("task-a")
        ));
        assertTrue(commands.smembers(keyspace.taskRegistrySet()).contains("task-b"));
        assertTrue(runtime.hasReadyWork("task-b"));
        assertTrue(runtime.getRecentFinalReceipt("task-a", claimed.messageId()).isEmpty());
    }

    @Test
    void highVolumeSingleTaskKeepsTaskLevelReadyIndexCompact() {
        for (int i = 0; i < 200; i++) {
            runtime.enqueue(item("bulk-task", "msg-" + i), WorkEnqueueOptions.DEFAULT);
        }

        assertEquals(List.of("bulk-task"), runtime.readyTaskIds(10));
        assertEquals(200L, runtime.stats("bulk-task").readyCount());
        assertEquals(1L, commands.zcard(keyspace.readyTasksZset()));
    }

    @Test
    void competingRuntimeInstancesClaimOnlyOneCopyOfTheSameWork() throws Exception {
        runtime.enqueue(item("task-race", "msg-1"), WorkEnqueueOptions.DEFAULT);

        StatefulRedisConnection<String, String> contenderConnection = redisClient.connect();
        RedisTaskWorkRuntime contender = new RedisTaskWorkRuntime(contenderConnection, keyspace, 1024, now::get);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<List<ClaimedTaskWork>> firstClaim = new AtomicReference<>(List.of());
            AtomicReference<List<ClaimedTaskWork>> secondClaim = new AtomicReference<>(List.of());
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Thread first = new Thread(() -> runClaim(runtime, ready, start, firstClaim, failure), "redis-claim-first");
            Thread second = new Thread(() -> runClaim(contender, ready, start, secondClaim, failure), "redis-claim-second");
            first.start();
            second.start();

            assertTrue(ready.await(5, TimeUnit.SECONDS), "claim workers did not become ready");
            start.countDown();
            first.join(5000);
            second.join(5000);

            if (failure.get() != null) {
                fail(failure.get());
            }

            int claimedCopies = firstClaim.get().size() + secondClaim.get().size();
            assertEquals(1, claimedCopies);
            assertTrue(firstClaim.get().isEmpty() || secondClaim.get().isEmpty());
            assertEquals(1, commands.smembers(keyspace.taskActiveSet("task-race")).size());
            assertTrue(commands.lrange(keyspace.taskReadyQueue("task-race"), 0, -1).isEmpty());
        } finally {
            contender.shutdown();
            if (contenderConnection.isOpen()) {
                contenderConnection.close();
            }
        }
    }

    @Test
    void closedRedisConnectionDegradesToRuntimeLevelUnavailableSemantics() {
        StatefulRedisConnection<String, String> brokenConnection = redisClient.connect();
        RedisTaskWorkRuntime brokenRuntime = new RedisTaskWorkRuntime(
                brokenConnection,
                new RedisTaskWorkKeyspace(RedisRuntimeTestSupport.namespace("redis-runtime-broken")),
                1024,
                now::get
        );
        brokenConnection.close();

        WorkEnqueueOutcome enqueueOutcome = brokenRuntime.enqueue(item("broken-task", "msg-1"), WorkEnqueueOptions.DEFAULT);
        ResultApplyOutcome resultOutcome = brokenRuntime.applyResult(
                TaskWorkResult.success("broken-task", "msg-1", "lease-1", "done", Map.of())
        );

        assertEquals(WorkEnqueueStatus.STORE_UNAVAILABLE, enqueueOutcome.status());
        assertEquals(ResultApplyStatus.FAILED, resultOutcome.status());
        assertTrue(brokenRuntime.readyTaskIds(10).isEmpty());
        assertTrue(brokenRuntime.claimReady("broken-task", List.of(WorkerClaimTarget.workerLevel("worker-1", "batch-1", 1)), 1, 30).isEmpty());
        assertTrue(brokenRuntime.pollExpiredLeases(10, now.get()).isEmpty());
        assertEquals(0L, brokenRuntime.stats().readyItems());
        assertEquals(0L, brokenRuntime.stats("broken-task").totalCount());
        assertEquals(0L, brokenRuntime.discardTask("broken-task"));
        brokenRuntime.shutdown();
    }

    private TaskWorkEnvelope item(String taskId, String messageId) {
        return new TaskWorkEnvelope(taskId, messageId, "demo.event",
                Map.of("target", messageId), null, 0, 3, null, null, now.get());
    }

    private TaskWorkEnvelope delayedItem(String taskId, String messageId, Instant nextVisibleAt) {
        return new TaskWorkEnvelope(taskId, messageId, "demo.event",
                Map.of("target", messageId), null, 0, 3, null, nextVisibleAt, now.get());
    }

    private void runClaim(RedisTaskWorkRuntime runtime,
                          CountDownLatch ready,
                          CountDownLatch start,
                          AtomicReference<List<ClaimedTaskWork>> claimHolder,
                          AtomicReference<Throwable> failure) {
        ready.countDown();
        try {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            claimHolder.set(runtime.claimReady(
                    "task-race",
                    List.of(WorkerClaimTarget.workerLevel("worker-1", "batch-1", 1)),
                    1,
                    30
            ));
        } catch (Throwable throwable) {
            failure.compareAndSet(null, throwable);
        }
    }

}
