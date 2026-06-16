package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisTransportDeliveryStoreTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private StatefulRedisConnection<String, String> observerConnection;
    private RedisCommands<String, String> observerCommands;
    private String namespacePrefix;
    private RedisTransportDeliveryStore store;

    @BeforeEach
    void setUp() {
        String redisUri = System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
        try {
            redisClient = RedisClient.create(redisUri);
            connection = redisClient.connect();
            observerConnection = redisClient.connect();
            observerCommands = observerConnection.sync();
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for transport queue test: " + ex.getMessage());
            throw ex;
        }
        namespacePrefix = "xa:mass:test:transport-delivery:" + UUID.randomUUID();
        store = new RedisTransportDeliveryStore(
                connection,
                namespacePrefix,
                4,
                2
        );
    }

    @AfterEach
    void tearDown() {
        if (observerConnection != null && observerConnection.isOpen()) {
            for (String encodedKeyPart : observerCommands.smembers(namespacePrefix + ":queues")) {
                observerCommands.del(namespacePrefix + ":q:" + encodedKeyPart, namespacePrefix + ":meta:" + encodedKeyPart);
            }
            observerCommands.del(namespacePrefix + ":queues", namespacePrefix + ":stats");
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
    void enqueueDrainAndPollUseSelectedWorkerSelectorUnderSharedDeliveryQueue() throws Exception {
        DispatchOutcome first = store.enqueue(" Polling ", " Queue-A ", queued(" Queue-A ", item("msg-1", " worker-1 ")));
        store.enqueue("polling", "Queue-A", queued("Queue-A", item("msg-2", "worker-2")));

        assertEquals(DispatchOutcomeStatus.QUEUED, first.getStatus());
        assertEquals("worker-1", first.getSelectedWorkerId());
        assertEquals(List.of("msg-1"), messageIds(store.drain("Queue-A", "worker-1", 10)));
        assertTrue(store.drain("Queue-A", "worker-1", 10).isEmpty());

        CompletableFuture<List<QueuedPulledDispatch>> polled = CompletableFuture.supplyAsync(() -> {
            try {
                return store.poll("Queue-A", "worker-2", 10, 1, TimeUnit.SECONDS).getItems();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        });

        assertEquals(List.of("msg-2"), messageIds(polled.get(2, TimeUnit.SECONDS)));
    }

    @Test
    void enqueueRejectsInvalidAndBackpressureStates() {
        DispatchOutcome invalid = store.enqueue("polling", "polling", invalidQueued("polling", item("msg-1", null)));
        DispatchOutcome first = store.enqueue("polling", "polling", queued("polling", item("msg-2", "worker-1")));
        DispatchOutcome second = store.enqueue("polling", "polling", queued("polling", item("msg-3", "worker-1")));
        DispatchOutcome third = store.enqueue("polling", "polling", queued("polling", item("msg-4", "worker-1")));
        DispatchOutcome fourth = store.enqueue("polling", "polling", queued("polling", item("msg-5", "worker-2")));
        DispatchOutcome fifth = store.enqueue("polling", "polling", queued("polling", item("msg-6", "worker-3")));
        DispatchOutcome sixth = store.enqueue("polling", "polling", queued("polling", item("msg-7", "worker-4")));

        assertEquals(DispatchOutcomeStatus.INVALID, invalid.getStatus());
        assertEquals(DispatchOutcomeStatus.QUEUED, first.getStatus());
        assertEquals(DispatchOutcomeStatus.QUEUED, second.getStatus());
        assertEquals(DispatchOutcomeStatus.BACKPRESSURE, third.getStatus());
        assertEquals(DispatchOutcomeStatus.QUEUED, fourth.getStatus());
        assertEquals(DispatchOutcomeStatus.QUEUED, fifth.getStatus());
        assertEquals(DispatchOutcomeStatus.BACKPRESSURE, sixth.getStatus());
        assertTrue(observerCommands.keys(namespacePrefix + ":*").stream().allMatch(this::isDeliveryKeyFamily));
    }

    @Test
    void statsExposeDeliveryQueueBreakdownAndShutdownClearing() {
        store.enqueue("polling", "polling", queued("polling", item("msg-1", "worker-1")));
        store.enqueue("polling", "polling", queued("polling", item("msg-2", "worker-2")));

        TransportDeliveryStoreStats queued = store.stats();
        assertEquals(2, queued.getQueuedItems());
        assertEquals(2, queued.getQueueCount());
        assertEquals(1, queued.getQueueByAdapter().size());
        assertEquals(2, queued.getQueueByAdapter().get("polling").getQueuedItems());
        assertEquals(2, queued.getQueueByAdapter().get("polling").getQueueCount());

        store.shutdown();

        TransportDeliveryStoreStats afterShutdown = store.stats();
        assertEquals(0, afterShutdown.getQueuedItems());
        assertEquals(0, afterShutdown.getQueueCount());
        assertEquals(2L, afterShutdown.getShutdownClearedItems());
        assertEquals(0, observerCommands.exists(namespacePrefix + ":queues", namespacePrefix + ":stats"));
    }

    private DispatchFixture item(String messageId, String workerId) {
        return new DispatchFixture(messageId, workerId);
    }

    private QueuedPulledDispatch queued(String adapterId, DispatchFixture item) {
        String deliveryId = "delivery-" + adapterId + "-" + item.messageId();
        return new QueuedPulledDispatch(
                deliveryId,
                item.workerId(),
                payload(item),
                correlation(item),
                1L
        );
    }

    private QueuedPulledDispatch invalidQueued(String adapterId, DispatchFixture item) {
        return null;
    }

    private String payload(DispatchFixture item) {
        return "{\"messageId\":\"" + item.messageId() + "\"}";
    }

    private String correlation(DispatchFixture item) {
        return "corr-" + item.messageId();
    }

    private List<String> messageIds(List<QueuedPulledDispatch> items) {
        return items.stream()
                .map(item -> messageId(item.payload()))
                .toList();
    }

    private String messageId(String payload) {
        return payload.replace("{\"messageId\":\"", "").replace("\"}", "");
    }

    private boolean isDeliveryKeyFamily(String key) {
        return key.startsWith(namespacePrefix + ":q:")
                || key.startsWith(namespacePrefix + ":meta:")
                || Set.of(namespacePrefix + ":queues", namespacePrefix + ":stats").contains(key);
    }

    private record DispatchFixture(String messageId, String workerId) {
    }
}

