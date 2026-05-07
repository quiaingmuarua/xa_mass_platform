package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.packet.TransportPacketViews;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
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
    void enqueueDrainAndPollUseCanonicalAdapterAndRouteKeys() throws Exception {
        DispatchOutcome first = store.enqueue(envelope(" Polling ", item("msg-1", " worker-1 ")));
        store.enqueue(envelope("websocket", item("msg-2", "worker-1")));

        assertEquals(DispatchOutcomeStatus.QUEUED, first.getStatus());
        assertEquals("polling", first.getAdapterId());
        assertEquals("worker-1", first.getRouteKey());
        assertEquals(List.of("msg-1"), messageIds(store.drain("polling", "worker-1", 10)));

        CompletableFuture<List<TransportDispatchEnvelope>> polled = CompletableFuture.supplyAsync(() -> {
            try {
                return store.poll("websocket", "worker-1", 10, 1, TimeUnit.SECONDS).getEnvelopes();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        });

        assertEquals(List.of("msg-2"), messageIds(polled.get(2, TimeUnit.SECONDS)));
    }

    @Test
    void enqueueRejectsInvalidAndBackpressureStates() {
        DispatchOutcome invalid = store.enqueue(invalidEnvelope("polling", item("msg-1", null)));
        DispatchOutcome first = store.enqueue(envelope("polling", item("msg-2", "worker-1")));
        DispatchOutcome second = store.enqueue(envelope("polling", item("msg-3", "worker-1")));
        DispatchOutcome third = store.enqueue(envelope("polling", item("msg-4", "worker-1")));
        DispatchOutcome fourth = store.enqueue(envelope("polling", item("msg-5", "worker-2")));
        DispatchOutcome fifth = store.enqueue(envelope("polling", item("msg-6", "worker-3")));
        DispatchOutcome sixth = store.enqueue(envelope("polling", item("msg-7", "worker-4")));

        assertEquals(DispatchOutcomeStatus.INVALID_ITEM, invalid.getStatus());
        assertEquals(DispatchOutcomeStatus.QUEUED, first.getStatus());
        assertEquals(DispatchOutcomeStatus.QUEUED, second.getStatus());
        assertEquals(DispatchOutcomeStatus.BACKPRESSURE_REJECTED, third.getStatus());
        assertEquals(DispatchOutcomeStatus.QUEUED, fourth.getStatus());
        assertEquals(DispatchOutcomeStatus.QUEUED, fifth.getStatus());
        assertEquals(DispatchOutcomeStatus.BACKPRESSURE_REJECTED, sixth.getStatus());
    }

    @Test
    void statsExposeAdapterBreakdownAndShutdownClearing() {
        store.enqueue(envelope("polling", item("msg-1", "worker-1")));
        store.enqueue(envelope("websocket", item("msg-2", "worker-2")));

        TransportDeliveryStoreStats queued = store.stats();
        assertEquals(2, queued.getQueuedItems());
        assertEquals(2, queued.getQueueCount());
        assertEquals(2, queued.getQueueByAdapter().size());
        assertEquals(1, queued.getQueueByAdapter().get("polling").getQueuedItems());
        assertEquals(1, queued.getQueueByAdapter().get("websocket").getQueuedItems());

        store.shutdown();

        TransportDeliveryStoreStats afterShutdown = store.stats();
        assertEquals(0, afterShutdown.getQueuedItems());
        assertEquals(0, afterShutdown.getQueueCount());
        assertEquals(2L, afterShutdown.getShutdownClearedItems());
        assertEquals(0, observerCommands.exists(namespacePrefix + ":queues", namespacePrefix + ":stats"));
    }

    private TaskDispatchItem item(String messageId, String workerId) {
        return new TaskDispatchItem(
                "task-1",
                messageId,
                "crawler.fetch-page",
                "task-name",
                "demoApp",
                "agent",
                0,
                workerId,
                null,
                "batch-1",
                Map.of("target", "target-1"),
                Map.of()
        );
    }

    private TransportDispatchEnvelope envelope(String adapterId, TaskDispatchItem item) {
        return new TransportDispatchEnvelope(
                "delivery-" + adapterId + "-" + item.getMessageId(),
                new TransportPacket(
                        TransportPacket.CURRENT_VERSION,
                        "delivery-" + adapterId + "-" + item.getMessageId(),
                        item.attemptId(),
                        PacketType.TASK_DISPATCH,
                        adapterId,
                        item.getWorkerId(),
                        item.getTaskId(),
                        item.getMessageId(),
                        item.attemptId(),
                        item.getEventCode(),
                        TransportPacket.JSON_CONTENT_TYPE,
                        TransportPacketViews.dispatchPayload(item)
                ),
                1L
        );
    }

    private TransportDispatchEnvelope invalidEnvelope(String adapterId, TaskDispatchItem item) {
        return new TransportDispatchEnvelope(
                "delivery-" + adapterId + "-" + item.getMessageId(),
                new TransportPacket(
                        TransportPacket.CURRENT_VERSION,
                        "delivery-" + adapterId + "-" + item.getMessageId(),
                        item.attemptId(),
                        PacketType.TASK_DISPATCH,
                        adapterId,
                        " ",
                        item.getTaskId(),
                        item.getMessageId(),
                        item.attemptId(),
                        item.getEventCode(),
                        TransportPacket.JSON_CONTENT_TYPE,
                        TransportPacketViews.dispatchPayload(item)
                ),
                1L
        );
    }

    private List<String> messageIds(List<TransportDispatchEnvelope> envelopes) {
        return envelopes.stream()
                .map(TransportDispatchEnvelope::getPacket)
                .map(TransportPacket::messageId)
                .toList();
    }
}
