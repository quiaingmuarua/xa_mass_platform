package com.xa.mass.runtime.redis.queue;

import com.xa.mass.runtime.queue.KeyedQueueEntry;
import com.xa.mass.runtime.queue.KeyedQueueOfferResult;
import com.xa.mass.runtime.queue.KeyedQueuePollResult;
import com.xa.mass.runtime.queue.KeyedQueuePollStatus;
import com.xa.mass.runtime.redis.RedisRuntimeTestSupport;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisKeyedBlockingQueueStoreTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private StatefulRedisConnection<String, String> observerConnection;
    private RedisCommands<String, String> observerCommands;
    private RedisKeyedQueueNamespace namespace;
    private RedisKeyedBlockingQueueStore store;

    @BeforeEach
    void setUp() {
        redisClient = RedisRuntimeTestSupport.createClientOrSkip("queue test");
        connection = redisClient.connect();
        observerConnection = redisClient.connect();
        observerCommands = observerConnection.sync();
        namespace = new RedisKeyedQueueNamespace(RedisRuntimeTestSupport.namespace("keyed-queue"));
        store = new RedisKeyedBlockingQueueStore(
                connection,
                namespace,
                new RedisKeyedQueueOptions(4, Duration.ofMillis(25))
        );
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.shutdown();
        }
        RedisRuntimeTestSupport.cleanupNamespace(observerCommands, namespace == null ? null : namespace.prefix());
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
    void offerAndDrainPreserveFifoPerKey() {
        assertEquals(KeyedQueueOfferResult.enqueued(), store.offer("worker-1", entry("first", 100L), 10));
        assertEquals(KeyedQueueOfferResult.enqueued(), store.offer("worker-1", entry("second", 200L), 10));
        assertEquals(KeyedQueueOfferResult.enqueued(), store.offer("worker-2", entry("other", 300L), 10));

        assertEquals(List.of("first", "second"), values(store.drain("worker-1", 10)));
        assertEquals(List.of("other"), values(store.drain("worker-2", 10)));
        assertTrue(store.drain("worker-1", 10).isEmpty());
    }

    @Test
    void offerRejectsPerKeyBackpressure() {
        assertEquals(KeyedQueueOfferResult.enqueued(), store.offer("worker-1", entry("first", 100L), 1));
        assertEquals(
                KeyedQueueOfferResult.backpressureRejected("queue is full"),
                store.offer("worker-1", entry("second", 200L), 1)
        );
        assertEquals(KeyedQueueOfferResult.enqueued(), store.offer("worker-2", entry("other", 300L), 1));
    }

    @Test
    void pollReturnsDeliveredWhenWorkArrivesBeforeTimeout() throws Exception {
        CompletableFuture<KeyedQueuePollResult> result = CompletableFuture.supplyAsync(() -> {
            try {
                return store.poll("worker-1", 10, 1, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return KeyedQueuePollResult.shutdown();
            }
        });

        Thread.sleep(100L);
        store.offer("worker-1", entry("ready", 100L), 10);

        KeyedQueuePollResult pollResult = result.get(2, TimeUnit.SECONDS);
        assertEquals(KeyedQueuePollStatus.DELIVERED, pollResult.status());
        assertEquals(List.of("ready"), values(pollResult.items()));
    }

    @Test
    void sizeIsTargetedPointRead() {
        store.offer("worker-1", entry("first", 100L), 10);
        store.offer("worker-1", entry("second", 200L), 10);
        store.offer("worker-2", entry("other", 300L), 10);

        assertEquals(2, store.size("worker-1"));
        assertEquals(1, store.size("worker-2"));
        assertEquals(0, store.size("missing"));
    }

    @Test
    void shutdownLeavesStoreUnavailable() {
        store.offer("worker-1", entry("queued", 100L), 10);

        store.shutdown();

        assertEquals(
                KeyedQueueOfferResult.unavailable("queue store is stopped"),
                store.offer("worker-1", entry("later", 200L), 10)
        );
    }

    @Test
    void pollReturnsEmptyAfterTimeout() throws Exception {
        KeyedQueuePollResult pollResult = store.poll("worker-1", 10, 100, TimeUnit.MILLISECONDS);

        assertEquals(KeyedQueuePollStatus.EMPTY, pollResult.status());
        assertTrue(pollResult.items().isEmpty());
    }

    private KeyedQueueEntry entry(String value, long createdAtEpochMillis) {
        return new KeyedQueueEntry(value, createdAtEpochMillis);
    }

    private List<String> values(List<KeyedQueueEntry> entries) {
        return entries.stream().map(KeyedQueueEntry::value).toList();
    }
}
