package com.xa.mass.transport.runtime.route;

import com.xa.mass.transport.route.TransportRouteOwnerRecord;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisTransportRouteOwnerStoreTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private StatefulRedisConnection<String, String> observerConnection;
    private RedisCommands<String, String> observerCommands;
    private String namespacePrefix;
    private RedisTransportRouteOwnerStore store;

    @BeforeEach
    void setUp() {
        String redisUri = System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
        try {
            redisClient = RedisClient.create(redisUri);
            connection = redisClient.connect();
            observerConnection = redisClient.connect();
            observerCommands = observerConnection.sync();
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for route-owner test: " + ex.getMessage());
            throw ex;
        }
        namespacePrefix = "xa:mass:test:transport-route-owner:" + UUID.randomUUID();
        store = new RedisTransportRouteOwnerStore(connection, namespacePrefix, 1_000L, "runtime-a");
    }

    @AfterEach
    void tearDown() {
        if (observerCommands != null) {
            for (String key : observerCommands.keys(namespacePrefix + ":*")) {
                observerCommands.del(key);
            }
        }
        if (store != null) {
            store.shutdown();
        }
        if (observerConnection != null && observerConnection.isOpen()) {
            observerConnection.close();
        }
        if (connection != null && connection.isOpen()) {
            connection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    void heartbeatRoundTripUsesRouteConsumerHash() {
        store.claimRouteOwner(" worker-1 ", " websocket ", " route-1 ", " conn-1 ", "connected");

        TransportRouteOwnerRecord online = store.getLatestOwnerByWorker("worker-1");
        assertNotNull(online);
        assertTrue(online.isLeaseActive(System.currentTimeMillis()));
        assertEquals("websocket", online.getAdapterId());
        assertEquals("route-1", online.getRouteKey());
        assertTrue(store.hasActiveRouteOwner("websocket", "route-1"));
        assertEquals("worker-1", store.currentOwner("route-1").orElseThrow().workerId());
        assertEquals("route-1", observerCommands.get(workerRouteKey("worker-1")));
        assertEquals(1L, observerCommands.scard(namespacePrefix + ":routes"));
        assertEquals(1, store.currentOwners("route-1").size());

        store.refreshHeartbeat("worker-1", "websocket", "route-1", "conn-1", "heartbeat");
        assertTrue(store.getLatestOwnerByWorker("worker-1").isLeaseActive(System.currentTimeMillis()));

        store.releaseRouteOwner("worker-1", "websocket", "route-1", "conn-1", "disconnect");

        assertNull(store.getLatestOwnerByWorker("worker-1"));
        assertTrue(store.currentOwners("route-1").isEmpty());
        assertNull(observerCommands.get(workerRouteKey("worker-1")));
        assertFalse(store.hasActiveRouteOwner("websocket", "route-1"));
    }

    @Test
    void sameRouteKeyCanHaveMultipleActiveConsumers() {
        store.claimRouteOwner("worker-1", "websocket", "route-shared", "conn-1", "connected");
        store.claimRouteOwner("worker-2", "socket", "route-shared", "conn-2", "connected");

        assertTrue(store.hasActiveRouteOwner("websocket", "route-shared"));
        assertTrue(store.hasActiveRouteOwner("socket", "route-shared"));
        assertEquals(2, store.currentOwners("route-shared").size());

        store.releaseRouteOwner("worker-1", "websocket", "route-shared", "conn-1", "disconnect");

        assertFalse(store.hasActiveRouteOwner("websocket", "route-shared"));
        assertTrue(store.hasActiveRouteOwner("socket", "route-shared"));
        assertEquals(1, store.currentOwners("route-shared").size());
        assertNull(observerCommands.get(workerRouteKey("worker-1")));
        assertEquals("route-shared", observerCommands.get(workerRouteKey("worker-2")));
    }

    @Test
    void expiredConsumerEvidencePrunesRouteIndex() throws Exception {
        try (RedisTransportRouteOwnerStore shortLeaseStore =
                     new RedisTransportRouteOwnerStore(redisClient, namespacePrefix, 250L, "runtime-a", false)) {
            shortLeaseStore.claimRouteOwner("worker-2", "socket", "route-2", "conn-2", "connected");

            assertTrue(shortLeaseStore.hasActiveRouteOwner("socket", "route-2"));
            Thread.sleep(300L);

            TransportRouteOwnerRecord stale = shortLeaseStore.getLatestOwnerByWorker("worker-2");
            assertNotNull(stale);
            assertFalse(stale.isLeaseActive(System.currentTimeMillis()));
            assertFalse(shortLeaseStore.hasActiveRouteOwner("socket", "route-2"));
            assertEquals(1, shortLeaseStore.pruneExpired());
            assertTrue(shortLeaseStore.listActiveRouteOwners().isEmpty());
            assertNull(shortLeaseStore.getLatestOwnerByWorker("worker-2"));
            assertTrue(shortLeaseStore.currentOwners("route-2").isEmpty());
            assertNull(observerCommands.get(workerRouteKey("worker-2")));
        }
    }

    @Test
    void sharedNamespaceAllowsAnotherTransportInstanceToReadRouteConsumerTruth() {
        try (StatefulRedisConnection<String, String> secondaryConnection = redisClient.connect();
             RedisTransportRouteOwnerStore secondary =
                     new RedisTransportRouteOwnerStore(secondaryConnection, namespacePrefix, 1_000L, "runtime-b")) {
            store.claimRouteOwner("worker-4", "websocket", "route-4", "conn-4", "connected");

            TransportRouteOwnerRecord mirrored = secondary.getLatestOwnerByWorker("worker-4");
            assertNotNull(mirrored);
            assertTrue(mirrored.isLeaseActive(System.currentTimeMillis()));
            assertEquals("websocket", mirrored.getAdapterId());
            assertEquals("route-4", mirrored.getRouteKey());
            assertTrue(secondary.hasActiveRouteOwner("websocket", "route-4"));
            assertEquals(1, secondary.currentOwners("route-4").size());

            secondary.releaseRouteOwner("worker-4", "websocket", "route-4", "conn-4", "disconnect");

            assertNull(store.getLatestOwnerByWorker("worker-4"));
            assertFalse(store.hasActiveRouteOwner("websocket", "route-4"));
        }
    }

    private String workerRouteKey(String workerId) {
        return namespacePrefix + ":worker-route:" + workerId;
    }
}
