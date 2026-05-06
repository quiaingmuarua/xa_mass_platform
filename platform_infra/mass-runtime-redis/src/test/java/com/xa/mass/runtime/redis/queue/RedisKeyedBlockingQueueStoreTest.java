package com.xa.mass.runtime.redis.queue;

import com.xa.mass.runtime.queue.KeyedQueueEntry;
import com.xa.mass.runtime.queue.KeyedQueueOfferResult;
import com.xa.mass.runtime.queue.KeyedQueuePollResult;
import com.xa.mass.runtime.queue.KeyedQueuePollStatus;
import com.xa.mass.runtime.queue.KeyedQueueSnapshot;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisKeyedBlockingQueueStoreTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private StatefulRedisConnection<String, String> observerConnection;
    private RedisCommands<String, String> commands;
    private RedisCommands<String, String> observerCommands;
    private RedisKeyedQueueNamespace namespace;
    private RedisKeyedBlockingQueueStore<String, String> store;

    @BeforeEach
    void setUp() {
        String redisUri = System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
        try {
            redisClient = RedisClient.create(redisUri);
            connection = redisClient.connect();
            observerConnection = redisClient.connect();
            commands = connection.sync();
            observerCommands = observerConnection.sync();
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for queue test: " + ex.getMessage());
            throw ex;
        }
        namespace = new RedisKeyedQueueNamespace("xa:mass:test:keyed-queue:" + UUID.randomUUID());
        store = new RedisKeyedBlockingQueueStore<>(
                connection,
                namespace,
                stringCodec(),
                new RedisKeyedQueueOptions(4, Duration.ofMillis(25), Duration.ofMillis(10)),
                new RedisKeyedQueueScripts()
        );
    }

    @AfterEach
    void tearDown() {
        if (observerConnection != null && observerConnection.isOpen()) {
            for (String encodedKeyPart : observerCommands.smembers(namespace.activeQueuesKey())) {
                observerCommands.del(namespace.queueKey(encodedKeyPart), namespace.metaKey(encodedKeyPart));
            }
            observerCommands.del(namespace.activeQueuesKey(), namespace.globalStatsKey());
        }
        if (store != null) {
            store.shutdown();
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
    void mapsUnexpectedOfferScriptResponseToUnavailable() {
        KeyedQueueOfferResult result = RedisKeyedBlockingQueueStore.mapOfferResponse(List.of("UNIMPLEMENTED"));

        assertEquals(
                KeyedQueueOfferResult.unavailable("queue store returned unsupported response: UNIMPLEMENTED"),
                result
        );
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
    void offerRejectsPerKeyAndGlobalBackpressure() {
        RedisKeyedBlockingQueueStore<String, String> smallStore = new RedisKeyedBlockingQueueStore<>(
                connection,
                new RedisKeyedQueueNamespace(namespace.prefix() + ":small"),
                stringCodec(),
                new RedisKeyedQueueOptions(1, Duration.ofMillis(25), Duration.ofMillis(10)),
                new RedisKeyedQueueScripts()
        );
        try {
            assertEquals(KeyedQueueOfferResult.enqueued(), smallStore.offer("worker-1", entry("first", 100L), 1));
            assertEquals(
                    KeyedQueueOfferResult.backpressureRejected("queue is full"),
                    smallStore.offer("worker-1", entry("second", 200L), 1)
            );
            assertEquals(
                    KeyedQueueOfferResult.backpressureRejected("runtime backlog is full"),
                    smallStore.offer("worker-2", entry("third", 300L), 1)
            );
        } finally {
            smallStore.shutdown();
        }
    }

    @Test
    void pollReturnsDeliveredWhenWorkArrivesBeforeTimeout() throws Exception {
        CompletableFuture<KeyedQueuePollResult<String>> result = CompletableFuture.supplyAsync(() -> {
            try {
                return store.poll("worker-1", 10, 1, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return KeyedQueuePollResult.shutdown();
            }
        });

        Thread.sleep(100L);
        store.offer("worker-1", entry("ready", 100L), 10);

        KeyedQueuePollResult<String> pollResult = result.get(2, TimeUnit.SECONDS);
        assertEquals(KeyedQueuePollStatus.DELIVERED, pollResult.status());
        assertEquals(List.of("ready"), values(pollResult.items()));
    }

    @Test
    void snapshotTracksQueueShapeAndOldestAge() throws Exception {
        long now = System.currentTimeMillis();
        store.offer("worker-1", entry("first", now - 250L), 10);
        store.offer("worker-1", entry("second", now - 100L), 10);
        store.offer("worker-2", entry("other", now - 150L), 10);

        KeyedQueueSnapshot<String> snapshot = store.snapshot();

        assertEquals(3, snapshot.queuedItems());
        assertEquals(2, snapshot.queueCount());
        assertEquals(4, snapshot.maxQueuedItems());
        assertEquals(3L, snapshot.enqueuedItems());
        assertEquals(0L, snapshot.drainedItems());
        assertEquals(2, snapshot.queueByKey().size());
        assertEquals(2, snapshot.queueByKey().get("worker-1").queuedItems());
        assertEquals(1, snapshot.queueByKey().get("worker-2").queuedItems());
        assertTrue(snapshot.oldestQueuedAgeMillis() >= 150L);
        assertTrue(snapshot.queueByKey().get("worker-1").oldestQueuedAgeMillis() >= 150L);
    }

    @Test
    void shutdownClearsCurrentNamespaceAndLeavesStoreUnavailable() {
        store.offer("worker-1", entry("queued", 100L), 10);

        store.shutdown();

        KeyedQueueSnapshot<String> snapshot = store.snapshot();
        assertEquals(0, snapshot.queuedItems());
        assertEquals(0, snapshot.queueCount());
        assertEquals(1L, snapshot.shutdownClearedItems());
        assertEquals(
                KeyedQueueOfferResult.unavailable("queue store is stopped"),
                store.offer("worker-1", entry("later", 200L), 10)
        );
        assertEquals(0, observerCommands.exists(namespace.activeQueuesKey(), namespace.globalStatsKey()));
    }

    @Test
    void pollReturnsEmptyAfterTimeout() throws Exception {
        KeyedQueuePollResult<String> pollResult = store.poll("worker-1", 10, 100, TimeUnit.MILLISECONDS);

        assertEquals(KeyedQueuePollStatus.EMPTY, pollResult.status());
        assertTrue(pollResult.items().isEmpty());
    }

    @Test
    void stringCodecRoundTripsSimpleEntry() {
        RedisKeyedQueueCodec<String, String> codec = stringCodec();
        KeyedQueueEntry<String> entry = entry("worker-1", 123L);

        assertEquals("worker-1", codec.encodeKeyPart("worker-1"));
        assertEquals(entry, codec.decodeValue(codec.encodeValue(entry)));
    }

    private RedisKeyedQueueCodec<String, String> stringCodec() {
        return new RedisKeyedQueueCodec<>() {
            @Override
            public String encodeKeyPart(String key) {
                return key;
            }

            @Override
            public String decodeKeyPart(String encodedKeyPart) {
                return encodedKeyPart;
            }

            @Override
            public byte[] encodeValue(KeyedQueueEntry<String> entry) {
                return (entry.createdAtEpochMillis() + ":" + entry.value()).getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public KeyedQueueEntry<String> decodeValue(byte[] bytes) {
                String text = new String(bytes, StandardCharsets.UTF_8);
                int delimiter = text.indexOf(':');
                return new KeyedQueueEntry<>(
                        text.substring(delimiter + 1),
                        Long.parseLong(text.substring(0, delimiter))
                );
            }
        };
    }

    private KeyedQueueEntry<String> entry(String value, long createdAtEpochMillis) {
        return new KeyedQueueEntry<>(value, createdAtEpochMillis);
    }

    private List<String> values(List<KeyedQueueEntry<String>> entries) {
        return entries.stream().map(KeyedQueueEntry::value).toList();
    }
}
