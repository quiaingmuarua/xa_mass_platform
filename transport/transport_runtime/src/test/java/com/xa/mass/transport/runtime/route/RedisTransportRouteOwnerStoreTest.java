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
    void onlineHeartbeatOfflineRoundTripUsesSharedRedisState() {
        store.claimRouteOwner(" worker-1 ", " websocket ", " route-1 ", " conn-1 ", "connected");

        TransportRouteOwnerRecord online = store.getLatestOwnerByWorker("worker-1");
        assertNotNull(online);
        assertTrue(online.isLeaseActive(System.currentTimeMillis()));
        assertEquals("websocket", online.getAdapterId());
        assertEquals("route-1", online.getRouteKey());
        assertTrue(store.hasActiveRouteOwner("websocket", "route-1"));
        assertEquals("worker-1", store.currentOwner("route-1").orElseThrow().workerId());
        assertEquals("route-1", observerCommands.get(workerRouteKey("worker-1")));
        assertNotNull(observerCommands.hget(ownerHash("route-1"), "route-1"));
        assertNotNull(observerCommands.zscore(deadlineKey("route-1"), "route-1"));
        assertEquals(0L, observerCommands.exists(namespacePrefix + ":owner-shards"));
        assertEquals(0L, observerCommands.exists(namespacePrefix + ":workers"));
        assertTrue(observerCommands.keys(namespacePrefix + ":route:*").isEmpty());
        assertTrue(observerCommands.keys(namespacePrefix + ":route-presence:*").isEmpty());
        assertTrue(observerCommands.keys(namespacePrefix + ":worker-routes:*").isEmpty());

        store.refreshHeartbeat("worker-1", "websocket", "route-1", "conn-1", "heartbeat");
        assertTrue(store.getLatestOwnerByWorker("worker-1").isLeaseActive(System.currentTimeMillis()));

        store.releaseRouteOwner("worker-1", "websocket", "route-1", "conn-1", "disconnect");

        assertNull(store.getLatestOwnerByWorker("worker-1"));
        assertTrue(store.currentOwner("route-1").isEmpty());
        assertNull(observerCommands.hget(ownerHash("route-1"), "route-1"));
        assertNull(observerCommands.zscore(deadlineKey("route-1"), "route-1"));
        assertNull(observerCommands.get(workerRouteKey("worker-1")));
        assertFalse(store.hasActiveRouteOwner("websocket", "route-1"));
    }

    @Test
    void expiredOwnerEvidencePrunesRouteIndex() throws Exception {
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
            assertNull(observerCommands.hget(ownerHash("route-2"), "route-2"));
            assertNull(observerCommands.get(workerRouteKey("worker-2")));
        }
    }

    @Test
    void routeKeyTakeoverReplacesCurrentOwnerAcrossAdapters() {
        store.claimRouteOwner("worker-3", "websocket", "route-old", "conn-1", "connected");
        store.claimRouteOwner("worker-3", "socket", "route-old", "conn-2", "reconnected");

        assertFalse(store.hasActiveRouteOwner("websocket", "route-old"));
        assertTrue(store.hasActiveRouteOwner("socket", "route-old"));
        assertEquals(1, store.findRouteOwners("worker-3").size());
        assertEquals("socket", store.getLatestOwnerByWorker("worker-3").getAdapterId());
        assertEquals("socket", store.currentOwner("route-old").orElseThrow().adapterId());
        assertEquals("route-old", observerCommands.get(workerRouteKey("worker-3")));

        store.releaseRouteOwner("worker-3", "websocket", "route-old", "conn-1", "stale-disconnect");
        assertTrue(store.hasActiveRouteOwner("socket", "route-old"));

        store.releaseRouteOwner("worker-3", "socket", "route-old", "conn-2", "disconnect");
        assertFalse(store.hasActiveRouteOwner("socket", "route-old"));
        assertTrue(store.findRouteOwners("worker-3").isEmpty());
        assertNull(observerCommands.hget(ownerHash("route-old"), "route-old"));
        assertNull(observerCommands.get(workerRouteKey("worker-3")));
    }

    @Test
    void routeKeyTakeoverByAnotherWorkerDoesNotKeepOldWorkerProjectionOnline() {
        store.claimRouteOwner("worker-old", "websocket", "route-shared", "conn-old", "connected");
        store.claimRouteOwner("worker-new", "socket", "route-shared", "conn-new", "takeover");

        assertNull(store.getLatestOwnerByWorker("worker-old"));
        assertFalse(store.isWorkerReachable("worker-old"));
        assertEquals("worker-new", store.getLatestOwnerByWorker("worker-new").getWorkerId());
        assertEquals("worker-new", store.currentOwner("route-shared").orElseThrow().workerId());
        assertNull(observerCommands.get(workerRouteKey("worker-old")));
    }

    @Test
    void workerRouteProjectionFindOwnersReturnsOnlyLatestOnlineRoute() {
        store.claimRouteOwner("worker-1", "websocket", "route-old", "conn-old", "connected");
        store.claimRouteOwner("worker-1", "socket", "route-new", "conn-new", "connected");

        assertEquals(1, store.findRouteOwners("worker-1").size());
        assertEquals("route-new", store.findRouteOwners("worker-1").getFirst().routeKey());
        assertEquals("route-new", observerCommands.get(workerRouteKey("worker-1")));
        assertEquals("route-old", store.currentOwner("route-old").orElseThrow().routeKey());
    }

    @Test
    void reconnectOnSameRouteRejectsStaleHeartbeatAndDisconnect() {
        store.claimRouteOwner("worker-3", "websocket", "route-1", "conn-old", "connected");
        store.claimRouteOwner("worker-3", "websocket", "route-1", "conn-new", "reconnected");

        TransportRouteOwnerRecord ignoredHeartbeat = store.refreshHeartbeat("worker-3", "websocket", "route-1", "conn-old", "stale-heartbeat");
        assertNotNull(ignoredHeartbeat);
        assertTrue(ignoredHeartbeat.isLeaseActive(System.currentTimeMillis()));
        assertEquals("conn-new", ignoredHeartbeat.getConnectionId());
        assertEquals("route-1", ignoredHeartbeat.getRouteKey());
        assertTrue(store.hasActiveRouteOwner("websocket", "route-1"));

        TransportRouteOwnerRecord ignoredRelease = store.releaseRouteOwner("worker-3", "websocket", "route-1", "conn-old", "stale-disconnect");
        assertNotNull(ignoredRelease);
        assertTrue(ignoredRelease.isLeaseActive(System.currentTimeMillis()));
        assertTrue(store.hasActiveRouteOwner("websocket", "route-1"));

        TransportRouteOwnerRecord finalRelease = store.releaseRouteOwner("worker-3", "websocket", "route-1", "conn-new", "disconnect");
        assertNotNull(finalRelease);
        assertEquals("conn-new", finalRelease.getConnectionId());
        assertFalse(store.hasActiveRouteOwner("websocket", "route-1"));
    }

    @Test
    void sharedNamespaceAllowsAnotherTransportInstanceToReadRouteOwnerTruth() {
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

            secondary.releaseRouteOwner("worker-4", "websocket", "route-4", "conn-4b", "disconnect");

            TransportRouteOwnerRecord stillOnline = store.getLatestOwnerByWorker("worker-4");
            assertNotNull(stillOnline);
            assertTrue(stillOnline.isLeaseActive(System.currentTimeMillis()));
            assertTrue(store.hasActiveRouteOwner("websocket", "route-4"));

            secondary.releaseRouteOwner("worker-4", "websocket", "route-4", "conn-4", "disconnect");

            assertNull(store.getLatestOwnerByWorker("worker-4"));
            assertFalse(store.hasActiveRouteOwner("websocket", "route-4"));
        }
    }

    @Test
    void crossInstanceSameRouteReconnectKeepsNewestOwnerAliveWhenOldInstanceSignalsLate() {
        try (StatefulRedisConnection<String, String> secondaryConnection = redisClient.connect();
             RedisTransportRouteOwnerStore secondary =
                     new RedisTransportRouteOwnerStore(secondaryConnection, namespacePrefix, 1_000L, "runtime-b")) {
            store.claimRouteOwner("worker-6", "websocket", "route-1", "conn-old", "connected");
            secondary.claimRouteOwner("worker-6", "websocket", "route-1", "conn-new", "reconnected");

            TransportRouteOwnerRecord staleHeartbeat = store.refreshHeartbeat(
                    "worker-6",
                    "websocket",
                    "route-1",
                    "conn-old",
                    "late-heartbeat"
            );
            assertNotNull(staleHeartbeat);
            assertTrue(staleHeartbeat.isLeaseActive(System.currentTimeMillis()));
            assertEquals("conn-new", staleHeartbeat.getConnectionId());
            assertEquals("websocket", staleHeartbeat.getAdapterId());
            assertEquals("route-1", staleHeartbeat.getRouteKey());
            assertTrue(store.hasActiveRouteOwner("websocket", "route-1"));

            TransportRouteOwnerRecord staleRelease = store.releaseRouteOwner(
                    "worker-6",
                    "websocket",
                    "route-1",
                    "conn-old",
                    "late-disconnect"
            );
            assertNotNull(staleRelease);
            assertTrue(staleRelease.isLeaseActive(System.currentTimeMillis()));
            assertEquals("conn-new", staleRelease.getConnectionId());
            assertTrue(secondary.hasActiveRouteOwner("websocket", "route-1"));

            TransportRouteOwnerRecord finalRelease = secondary.releaseRouteOwner(
                    "worker-6",
                    "websocket",
                    "route-1",
                    "conn-new",
                    "disconnect"
            );
            assertNotNull(finalRelease);
            assertEquals("conn-new", finalRelease.getConnectionId());
            assertFalse(store.hasActiveRouteOwner("websocket", "route-1"));
        }
    }

    @Test
    void staleLeaseObservedByAnotherInstanceConvergesToSharedUnreachableState() throws Exception {
        try (RedisTransportRouteOwnerStore shortLeaseWriter =
                     new RedisTransportRouteOwnerStore(redisClient, namespacePrefix, 25L, "runtime-a", false);
             StatefulRedisConnection<String, String> readerConnection = redisClient.connect();
             RedisTransportRouteOwnerStore reader =
                     new RedisTransportRouteOwnerStore(readerConnection, namespacePrefix, 25L, "runtime-b")) {
            shortLeaseWriter.claimRouteOwner("worker-5", "socket", "route-5", "conn-5", "connected");

            Thread.sleep(40L);

            TransportRouteOwnerRecord stale = reader.getLatestOwnerByWorker("worker-5");
            assertNotNull(stale);
            assertFalse(stale.isLeaseActive(System.currentTimeMillis()));
            assertFalse(reader.hasActiveRouteOwner("socket", "route-5"));
            assertFalse(shortLeaseWriter.hasActiveRouteOwner("socket", "route-5"));
            assertTrue(shortLeaseWriter.listActiveRouteOwners().isEmpty());
        }
    }

    private String ownerHash(String routeKey) {
        return namespacePrefix + ":owner:" + Math.floorMod(routeKey.hashCode(), 64);
    }

    private String deadlineKey(String routeKey) {
        return namespacePrefix + ":deadline:" + Math.floorMod(routeKey.hashCode(), 64);
    }

    private String workerRouteKey(String workerId) {
        return namespacePrefix + ":worker-route:" + workerId;
    }
}
