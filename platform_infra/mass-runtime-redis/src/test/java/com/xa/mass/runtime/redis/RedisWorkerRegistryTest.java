package com.xa.mass.runtime.redis;

import com.xa.mass.runtime.contract.WorkerRegistryContractTest;
import com.xa.mass.runtime.worker.WorkerCandidateSamplingPolicy;
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
                meta -> Set.of(RedisWorkerRegistry.DEFAULT_ROUTE_BUCKET_KEY),
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
        assertEquals(Set.of("worker-1"),
                commands.smembers(keyspace.groupRouteBucket("group-a", RedisWorkerRegistry.DEFAULT_ROUTE_BUCKET_KEY)));
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
                        meta -> Set.of(RedisWorkerRegistry.DEFAULT_ROUTE_BUCKET_KEY),
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
    void namespacePrefixIsolatesWorkerRegistryKeys() {
        WorkerRegistry workerRegistry = createRegistry();
        workerRegistry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));
        RedisWorkerRegistryKeyspace otherKeyspace =
                new RedisWorkerRegistryKeyspace(RedisRuntimeTestSupport.namespace("worker-registry-other"));
        RedisWorkerRegistry otherRegistry = new RedisWorkerRegistry(
                redisClient,
                otherKeyspace,
                (context, workerIds, maxCandidateCount) -> workerIds.stream().limit(maxCandidateCount).toList(),
                meta -> Set.of(RedisWorkerRegistry.DEFAULT_ROUTE_BUCKET_KEY),
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
}
