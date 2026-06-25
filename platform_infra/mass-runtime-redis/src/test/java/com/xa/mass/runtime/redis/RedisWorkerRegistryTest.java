package com.xa.mass.runtime.redis;

import com.xa.mass.runtime.contract.WorkerRegistryContractTest;
import com.xa.mass.runtime.worker.DispatchAvailabilitySource;
import com.xa.mass.runtime.worker.WorkerDispatchBlockRecord;
import com.xa.mass.runtime.worker.WorkerRegistry;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
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
    protected WorkerRegistry createRegistry() {
        redisClient = RedisRuntimeTestSupport.createClientOrSkip("worker registry contract test");
        observerConnection = redisClient.connect();
        commands = observerConnection.sync();
        keyspace = new RedisWorkerRegistryKeyspace(RedisRuntimeTestSupport.namespace("worker-registry"));
        registry = new RedisWorkerRegistry(redisClient, keyspace, false);
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
    }

    @Test
    void upsertDoesNotCreateCandidateBucketKeyspace() {
        WorkerRegistry workerRegistry = createRegistry();
        workerRegistry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));
        workerRegistry.upsertSlot(meta("worker-2", "group-a"), 1, Set.of(eventKey()));

        assertTrue(commands.keys(keyspace.namespace() + ":*bucket*").isEmpty());
        assertTrue(commands.keys(keyspace.namespace() + ":*worker-active-count*").isEmpty());
    }

    @Test
    void movingWorkerBetweenGroupsDeletesOldGroupSlot() {
        WorkerRegistry workerRegistry = createRegistry();
        workerRegistry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));
        workerRegistry.upsertSlot(meta("worker-1", "group-b"), 1, Set.of(eventKey()));

        assertTrue(commands.hkeys(keyspace.groupSlotsHash("group-a")).isEmpty());
        assertEquals(Set.of("worker-1"), Set.copyOf(commands.hkeys(keyspace.groupSlotsHash("group-b"))));
        assertEquals("group-b", commands.hget(keyspace.workerGroupHash(), "worker-1"));
    }

    @Test
    void concurrentExclusiveLeaseAcrossRedisConnectionsAllowsOneWinner() throws InterruptedException {
        WorkerRegistry workerRegistry = createRegistry();
        workerRegistry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));
        int contenders = 16;
        List<RedisWorkerRegistry> contenderRegistries = new ArrayList<>();
        try {
            for (int index = 0; index < contenders; index++) {
                contenderRegistries.add(new RedisWorkerRegistry(redisClient, keyspace, false));
            }
            CountDownLatch ready = new CountDownLatch(contenders);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger accepted = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Thread> threads = new ArrayList<>();
            for (int index = 0; index < contenders; index++) {
                WorkerRegistry target = contenderRegistries.get(index);
                Thread thread = new Thread(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        if (target.tryAcquireExclusiveLease("group-a", "worker-1")) {
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

            assertEquals(1, accepted.get());
            assertTrue(workerRegistry.hasExclusiveLease("worker-1"));
        } finally {
            for (RedisWorkerRegistry contenderRegistry : contenderRegistries) {
                contenderRegistry.close();
            }
        }
    }

    @Test
    void dispatchBlockRecordRejectsStaleSignalsAcrossConnections() {
        WorkerRegistry workerRegistry = createRegistry();
        workerRegistry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));
        try (RedisWorkerRegistry otherRegistry = new RedisWorkerRegistry(redisClient, keyspace, false)) {
            WorkerDispatchBlockRecord newer = new WorkerDispatchBlockRecord(
                    DispatchAvailabilitySource.TRANSPORT_DISCONNECTED,
                    "newer",
                    2_000L,
                    0L
            );
            WorkerDispatchBlockRecord older = new WorkerDispatchBlockRecord(
                    DispatchAvailabilitySource.TRANSPORT_DISCONNECTED,
                    "older",
                    1_000L,
                    0L
            );

            assertTrue(workerRegistry.blockDispatch("group-a", "worker-1", newer));
            assertFalse(otherRegistry.blockDispatch("group-a", "worker-1", older));
            assertEquals(newer, otherRegistry.dispatchBlockRecord(
                    "group-a",
                    "worker-1",
                    DispatchAvailabilitySource.TRANSPORT_DISCONNECTED
            ).orElseThrow());
        }
    }
}
