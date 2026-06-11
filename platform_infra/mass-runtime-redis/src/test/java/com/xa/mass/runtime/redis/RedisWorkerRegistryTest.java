package com.xa.mass.runtime.redis;

import com.xa.mass.runtime.contract.WorkerRegistryContractTest;
import com.xa.mass.runtime.worker.CleanupSummary;
import com.xa.mass.runtime.worker.DispatchAvailabilitySource;
import com.xa.mass.runtime.worker.EventKey;
import com.xa.mass.runtime.worker.ReserveStatus;
import com.xa.mass.runtime.worker.WorkerCandidateSamplingPolicy;
import com.xa.mass.runtime.worker.DefaultWorkerCandidateBucketPolicy;
import com.xa.mass.runtime.worker.WorkerMeta;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.runtime.worker.WorkerCandidateBucketPolicy;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class RedisWorkerRegistryTest extends WorkerRegistryContractTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> observerConnection;
    private RedisCommands<String, String> commands;
    private RedisWorkerRegistry registry;
    private RedisWorkerRegistryKeyspace keyspace;

    @Override
    protected WorkerRegistry createRegistry(WorkerCandidateSamplingPolicy samplingPolicy) {
        redisClient = RedisRuntimeTestSupport.createClientOrSkip("worker registry contract test");
        observerConnection = redisClient.connect();
        commands = observerConnection.sync();
        keyspace = new RedisWorkerRegistryKeyspace(RedisRuntimeTestSupport.namespace("worker-registry"));
        registry = new RedisWorkerRegistry(
                redisClient,
                keyspace,
                samplingPolicy,
                DefaultWorkerCandidateBucketPolicy.defaultPolicy(),
                false
        );
        return registry;
    }

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.close();
            registry = null;
        }
        if (commands != null && keyspace != null) {
            RedisRuntimeTestSupport.cleanupNamespace(commands, keyspace.namespace());
        }
        if (observerConnection != null && observerConnection.isOpen()) {
            observerConnection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
        commands = null;
        keyspace = null;
        observerConnection = null;
        redisClient = null;
    }

    @Test
    void storesWorkerSlotsInGroupPartitionedHashes() {
        WorkerRegistry workerRegistry = createRegistry();
        workerRegistry.upsertSlot(meta("worker-1", "group-a"), 2, Set.of(eventKey()));
        workerRegistry.upsertSlot(meta("worker-2", "group-b"), 2, Set.of(eventKey()));

        assertEquals(Set.of("worker-1"), Set.copyOf(commands.hkeys(keyspace.groupSlotsHash("group-a"))));
        assertEquals(Set.of("worker-2"), Set.copyOf(commands.hkeys(keyspace.groupSlotsHash("group-b"))));
        assertEquals("group-a", commands.hget(keyspace.workerGroupHash(), "worker-1"));
        assertEquals(Set.of("group-a", "group-b"), commands.smembers(keyspace.workerGroupsSet()));
        assertEquals(List.of("worker-1"), commands.zrange(keyspace.groupHeartbeatDeadlinesZset("group-a"), 0, -1));
        assertEquals(List.of("worker-2"), commands.zrange(keyspace.groupHeartbeatDeadlinesZset("group-b"), 0, -1));
        assertEquals(Set.of("worker-1"),
                commands.smembers(keyspace.groupCandidateBucket("group-a", RedisWorkerRegistry.DEFAULT_CANDIDATE_BUCKET_KEY)));
    }

    @Test
    void concurrentReserveAcrossRedisConnectionsDoesNotExceedCapacity() throws InterruptedException {
        WorkerRegistry workerRegistry = createRegistry();
        workerRegistry.upsertSlot(meta("worker-1", "group-a"), 4, Set.of(eventKey()));
        int contenders = 16;
        List<RedisWorkerRegistry> contenderRegistries = new ArrayList<>();
        try {
            for (int index = 0; index < contenders; index++) {
                contenderRegistries.add(new RedisWorkerRegistry(
                        redisClient,
                        keyspace,
                        (context, workerIds, maxCandidateCount) -> workerIds.stream().limit(maxCandidateCount).toList(),
                        DefaultWorkerCandidateBucketPolicy.defaultPolicy(),
                        false
                ));
            }
            CountDownLatch ready = new CountDownLatch(contenders);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger accepted = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Thread> threads = new ArrayList<>();
            for (int index = 0; index < contenders; index++) {
                int taskIndex = index;
                WorkerRegistry target = contenderRegistries.get(index);
                Thread thread = new Thread(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        if (target.tryReserve("group-a", "worker-1", "task-" + taskIndex, 1, 1000).accepted()) {
                            accepted.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable t) {
                        failure.compareAndSet(null, t);
                    }
                });
                threads.add(thread);
                thread.start();
            }

            ready.await();
            start.countDown();
            for (Thread thread : threads) {
                thread.join();
            }

            if (failure.get() != null) {
                fail(failure.get());
            }
            assertEquals(4, accepted.get());
            assertEquals(4, workerRegistry.slot("group-a", "worker-1").orElseThrow().reservedCount());
        } finally {
            contenderRegistries.forEach(RedisWorkerRegistry::close);
        }
    }

    @Test
    void concurrentReserveOnSharedRedisRegistryInstanceDoesNotExceedCapacity() throws InterruptedException {
        WorkerRegistry workerRegistry = createRegistry();
        workerRegistry.upsertSlot(meta("worker-1", "group-a"), 4, Set.of(eventKey()));
        int contenders = 16;
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < contenders; index++) {
            int taskIndex = index;
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (workerRegistry.tryReserve("group-a", "worker-1", "shared-task-" + taskIndex, 1, 1000)
                            .accepted()) {
                        accepted.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            threads.add(thread);
            thread.start();
        }

        ready.await();
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        if (failure.get() != null) {
            fail(failure.get());
        }
        assertEquals(4, accepted.get());
        assertEquals(4, workerRegistry.slot("group-a", "worker-1").orElseThrow().reservedCount());
    }

    @Test
    void namespacePrefixIsolatesWorkerRegistryKeys() {
        WorkerRegistry workerRegistry = createRegistry();
        workerRegistry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));
        RedisWorkerRegistryKeyspace otherKeyspace =
                new RedisWorkerRegistryKeyspace(RedisRuntimeTestSupport.namespace("worker-registry-other"));
        RedisWorkerRegistry otherRegistry = new RedisWorkerRegistry(
                redisClient,
                otherKeyspace,
                (context, workerIds, maxCandidateCount) -> workerIds.stream().limit(maxCandidateCount).toList(),
                DefaultWorkerCandidateBucketPolicy.defaultPolicy(),
                false
        );
        try {
            otherRegistry.upsertSlot(meta("worker-2", "group-a"), 1, Set.of(eventKey()));

            assertEquals(Set.of("worker-1"), Set.copyOf(commands.hkeys(keyspace.groupSlotsHash("group-a"))));
            assertEquals(Set.of("worker-2"), Set.copyOf(commands.hkeys(otherKeyspace.groupSlotsHash("group-a"))));
        } finally {
            otherRegistry.close();
            RedisRuntimeTestSupport.cleanupNamespace(commands, otherKeyspace.namespace());
        }
    }

    @Test
    void taskWorkerActiveCountHashOwnsActiveWorkerProjectionWithoutActiveWorkerSet() {
        WorkerRegistry workerRegistry = createRegistry();
        workerRegistry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));

        assertTrue(workerRegistry.tryReserve("group-a", "worker-1", "task-1", 1, 1000).accepted());
        assertTrue(workerRegistry.confirmReservation("group-a", "worker-1", "task-1", 1));

        assertEquals(Set.of("worker-1"), workerRegistry.activeWorkerIdsByTask("task-1"));
        assertEquals(1, workerRegistry.activeWorkerCountForTask("task-1"));
        assertEquals("1", commands.hget(keyspace.taskWorkerActiveCountsHash("task-1"), "worker-1"));
        assertEquals(0L, commands.exists(oldTaskActiveWorkersKey("task-1")));

        workerRegistry.recordWorkFinal("group-a", "worker-1", "task-1", 1);

        assertTrue(workerRegistry.activeWorkerIdsByTask("task-1").isEmpty());
        assertEquals(0, workerRegistry.activeWorkerCountForTask("task-1"));
        assertEquals(0, workerRegistry.activeLeaseCountByTaskWorker("task-1", "worker-1"));
        assertFalse(commands.hexists(keyspace.taskWorkerActiveCountsHash("task-1"), "worker-1"));
    }

    @Test
    void acceptsSharedWorkerCandidateBucketPolicySeam() {
        createRegistry();
        registry.close();
        registry = new RedisWorkerRegistry(
                redisClient,
                keyspace,
                (context, workerIds, maxCandidateCount) -> workerIds.stream().limit(maxCandidateCount).toList(),
                regionRoutePolicy(),
                false
        );
        registry.upsertSlot(
                new WorkerMeta("worker-1", "group-a", null, null, null,
                        Map.of("region", "us"), null, null, 1_000, "ONLINE"),
                1,
                Set.of(eventKey())
        );

        assertEquals(List.of("worker-1"), registry.acquireCandidates("group-a", "attr:region=us", 10));
    }

    @Test
    void routeAttributeUpdateRemovesOnlyKnownPreviousBucketMembership() {
        createRegistry();
        registry.close();
        registry = new RedisWorkerRegistry(
                redisClient,
                keyspace,
                (context, workerIds, maxCandidateCount) -> workerIds.stream().limit(maxCandidateCount).toList(),
                regionRoutePolicy(),
                false
        );
        registry.upsertSlot(metaWithRegion("worker-1", "group-a", "us"), 1, Set.of(eventKey()));
        registry.upsertSlot(metaWithRegion("worker-2", "group-a", "us"), 1, Set.of(eventKey()));

        assertEquals(List.of("worker-1", "worker-2"),
                registry.acquireCandidates("group-a", "attr:region=us", 10));

        registry.upsertSlot(metaWithRegion("worker-1", "group-a", "eu"), 1, Set.of(eventKey()));

        assertEquals(List.of("worker-2"),
                registry.acquireCandidates("group-a", "attr:region=us", 10));
        assertEquals(List.of("worker-1"),
                registry.acquireCandidates("group-a", "attr:region=eu", 10));
    }

    @Test
    void bucketMembershipUsesGroupLocalHashInsteadOfPerWorkerKeys() {
        createRegistry();
        registry.close();
        registry = new RedisWorkerRegistry(
                redisClient,
                keyspace,
                (context, workerIds, maxCandidateCount) -> workerIds.stream().limit(maxCandidateCount).toList(),
                regionRoutePolicy(),
                false
        );

        registry.upsertSlot(metaWithRegion("worker-1", "group-a", "us"), 1, Set.of(eventKey()));
        registry.upsertSlot(metaWithRegion("worker-2", "group-a", "eu"), 1, Set.of(eventKey()));

        String membershipHash = keyspace.groupBucketMembershipHash("group-a");
        assertEquals(2L, commands.hlen(membershipHash));
        assertTrue(commands.hexists(membershipHash, "worker-1"));
        assertTrue(commands.hexists(membershipHash, "worker-2"));
        assertEquals(0L, commands.exists(oldWorkerBucketMembershipKey("group-a", "worker-1")));

        registry.markSlotRemoving("group-a", "worker-1", "test cleanup");

        assertFalse(commands.hexists(membershipHash, "worker-1"));
        assertEquals(List.of("worker-2"),
                registry.acquireCandidates("group-a", RedisWorkerRegistry.DEFAULT_CANDIDATE_BUCKET_KEY, 10));
        assertEquals(List.of(),
                registry.acquireCandidates("group-a", "attr:region=us", 10));
    }


    @Test
    void staleHeartbeatCandidateIsRejectedThenCleanedFromCandidateBucket() {
        WorkerRegistry workerRegistry = createRegistry();
        workerRegistry.upsertSlot(meta("worker-stale", "group-a", 1_000), 1, Set.of(eventKey()));

        assertEquals(List.of("worker-stale"),
                workerRegistry.acquireCandidates("group-a", RedisWorkerRegistry.DEFAULT_CANDIDATE_BUCKET_KEY, 10));
        assertEquals(ReserveStatus.STALE_HEARTBEAT,
                workerRegistry.tryReserve("group-a", "worker-stale", "task-1", 1, 31_001).status());

        CleanupSummary cleanup = workerRegistry.cleanupExpiredHeartbeats(31_001, 10);

        assertEquals(1, cleanup.scanned());
        assertEquals(1, cleanup.removed());
        assertTrue(workerRegistry.slot("group-a", "worker-stale").orElseThrow().removing());
        assertEquals(1, workerRegistry.cleanupRemovedSlots("group-a", 10).removed());
        assertTrue(workerRegistry.slot("group-a", "worker-stale").isEmpty());
        assertTrue(workerRegistry.acquireCandidates(
                "group-a",
                RedisWorkerRegistry.DEFAULT_CANDIDATE_BUCKET_KEY,
                10
        ).isEmpty());
    }

    @Test
    void workerReconnectRefreshesHeartbeatAndBecomesReservableAgain() {
        WorkerRegistry workerRegistry = createRegistry();
        workerRegistry.upsertSlot(meta("worker-reconnect", "group-a", 1_000), 1, Set.of(eventKey()));
        assertEquals(ReserveStatus.STALE_HEARTBEAT,
                workerRegistry.tryReserve("group-a", "worker-reconnect", "task-1", 1, 31_001).status());

        workerRegistry.upsertSlot(meta("worker-reconnect", "group-a", 2_000), 1, Set.of(eventKey()));

        assertTrue(workerRegistry.tryReserve("group-a", "worker-reconnect", "task-2", 1, 31_001).accepted());
    }

    @Test
    void boundedStaleBucketCleanupRemovesMembersWithoutSlot() {
        createRegistry();
        registry.close();
        registry = new RedisWorkerRegistry(
                redisClient,
                keyspace,
                (context, workerIds, maxCandidateCount) -> workerIds.stream().limit(maxCandidateCount).toList(),
                regionRoutePolicy(),
                false
        );
        WorkerRegistry workerRegistry = registry;
        workerRegistry.upsertSlot(metaWithRegion("worker-gone", "group-a", "us"), 1, Set.of(eventKey()));
        commands.hdel(keyspace.groupSlotsHash("group-a"), "worker-gone");

        assertEquals(List.of("worker-gone"),
                workerRegistry.acquireCandidates("group-a", RedisWorkerRegistry.DEFAULT_CANDIDATE_BUCKET_KEY, 10));

        CleanupSummary cleanup = workerRegistry.cleanupStaleBucketMembers("group-a", 10);

        assertEquals(2, cleanup.scanned());
        assertEquals(2, cleanup.removed());
        assertTrue(workerRegistry.acquireCandidates(
                "group-a",
                RedisWorkerRegistry.DEFAULT_CANDIDATE_BUCKET_KEY,
                10
        ).isEmpty());
        assertTrue(workerRegistry.acquireCandidates("group-a", "attr:region=us", 10).isEmpty());
    }

    @Test
    void concurrentReadsDoNotEnterSharedConnectionTransaction() throws InterruptedException {
        WorkerRegistry workerRegistry = createRegistry();
        workerRegistry.upsertSlot(metaWithRegion("worker-1", "group-a", "us"), 1, Set.of(eventKey()));
        int readers = 4;
        int iterations = 100;
        CountDownLatch ready = new CountDownLatch(readers + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();

        Thread mutator = new Thread(() -> {
            ready.countDown();
            try {
                start.await();
                for (int index = 0; index < iterations; index++) {
                    workerRegistry.disableDispatch("group-a", "worker-1", DispatchAvailabilitySource.NODE_GROUP_BINDING);
                    workerRegistry.clearDispatchDisable("group-a", "worker-1", DispatchAvailabilitySource.NODE_GROUP_BINDING);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                stop.set(true);
            }
        });
        threads.add(mutator);
        mutator.start();

        for (int readerIndex = 0; readerIndex < readers; readerIndex++) {
            Thread reader = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                    while (!stop.get()) {
                        workerRegistry.slotByWorkerId("worker-1");
                        workerRegistry.workerIdsByAdapterNodeGroup("node-a", "group-a");
                        workerRegistry.acquireCandidates("group-a", RedisWorkerRegistry.DEFAULT_CANDIDATE_BUCKET_KEY, 10);
                        workerRegistry.hasExclusiveLease("worker-1");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                    stop.set(true);
                }
            });
            threads.add(reader);
            reader.start();
        }

        ready.await();
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
        if (failure.get() != null) {
            fail(failure.get());
        }
        assertEquals(List.of("worker-1"),
                workerRegistry.acquireCandidates("group-a", RedisWorkerRegistry.DEFAULT_CANDIDATE_BUCKET_KEY, 10));
    }

    private WorkerMeta meta(String workerId, String groupId, long lastHeartbeatMillis) {
        return new WorkerMeta(
                workerId,
                groupId,
                "node-a",
                "polling",
                "polling",
                Map.of("region", "us"),
                "agent-1",
                "runtime-1",
                lastHeartbeatMillis,
                "AVAILABLE"
        );
    }

    private WorkerMeta metaWithRegion(String workerId, String groupId, String region) {
        return new WorkerMeta(
                workerId,
                groupId,
                "node-a",
                "polling",
                "polling",
                Map.of("region", region),
                "agent-1",
                "runtime-1",
                1_000,
                "AVAILABLE"
        );
    }

    private static WorkerCandidateBucketPolicy regionRoutePolicy() {
        return meta -> {
            String region = meta == null || meta.attributes() == null ? null : meta.attributes().get("region");
            if (region == null || region.isBlank()) {
                return Set.of(WorkerCandidateBucketPolicy.DEFAULT_CANDIDATE_BUCKET_KEY);
            }
            return Set.of(WorkerCandidateBucketPolicy.DEFAULT_CANDIDATE_BUCKET_KEY, "attr:region=" + region.trim());
        };
    }

    private String oldWorkerBucketMembershipKey(String groupId, String workerId) {
        return keyspace.namespace() + ":group:" + groupId + ":worker:" + workerId + ":bucket-membership";
    }

    private String oldTaskActiveWorkersKey(String taskId) {
        return keyspace.namespace() + ":task:" + taskId + ":active-workers";
    }
}
