package com.xa.mass.transport.runtime.presence;

import com.xa.mass.transport.presence.WorkerPresence;
import com.xa.mass.transport.presence.WorkerPresenceState;
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

class RedisWorkerPresenceStoreTest {

    private RedisClient redisClient;
    private StatefulRedisConnection<String, String> connection;
    private StatefulRedisConnection<String, String> observerConnection;
    private RedisCommands<String, String> observerCommands;
    private String namespacePrefix;
    private RedisWorkerPresenceStore store;

    @BeforeEach
    void setUp() {
        String redisUri = System.getProperty("mass.redis.test.uri", "redis://127.0.0.1:6379/0");
        try {
            redisClient = RedisClient.create(redisUri);
            connection = redisClient.connect();
            observerConnection = redisClient.connect();
            observerCommands = observerConnection.sync();
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for presence test: " + ex.getMessage());
            throw ex;
        }
        namespacePrefix = "xa:mass:test:transport-presence:" + UUID.randomUUID();
        store = new RedisWorkerPresenceStore(connection, namespacePrefix, 1_000L, "runtime-a");
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
        store.markOnline(" worker-1 ", " websocket ", " route-1 ", " conn-1 ", "connected");

        WorkerPresence online = store.getPresence("worker-1");
        assertNotNull(online);
        assertEquals(WorkerPresenceState.ONLINE, online.getPresenceState());
        assertEquals("websocket", online.getAdapterId());
        assertEquals("route-1", online.getRouteKey());
        assertTrue(store.isRouteOnline("websocket", "route-1"));

        store.refreshHeartbeat("worker-1", "websocket", "route-1", "conn-1", "heartbeat");
        assertEquals(WorkerPresenceState.ONLINE, store.getPresence("worker-1").getPresenceState());

        store.markOffline("worker-1", "websocket", "route-1", "conn-1", "disconnect");

        WorkerPresence offline = store.getPresence("worker-1");
        assertNotNull(offline);
        assertEquals(WorkerPresenceState.OFFLINE, offline.getPresenceState());
        assertFalse(store.isRouteOnline("websocket", "route-1"));
    }

    @Test
    void expiredOnlinePresenceMaterializesAsStaleAndClearsRouteIndex() throws Exception {
        try (RedisWorkerPresenceStore shortLeaseStore =
                     new RedisWorkerPresenceStore(redisClient, namespacePrefix, 250L, "runtime-a", false)) {
            shortLeaseStore.markOnline("worker-2", "socket", "route-2", "conn-2", "connected");

            assertTrue(shortLeaseStore.isRouteOnline("socket", "route-2"));
            Thread.sleep(300L);

            WorkerPresence stale = shortLeaseStore.getPresence("worker-2");
            assertNotNull(stale);
            assertEquals(WorkerPresenceState.STALE, stale.getPresenceState());
            assertFalse(shortLeaseStore.isRouteOnline("socket", "route-2"));
            assertEquals(1, shortLeaseStore.pruneExpired());
            assertTrue(shortLeaseStore.listActivePresences().isEmpty());
            assertNull(shortLeaseStore.getPresence("worker-2"));
            assertNull(observerCommands.get(namespacePrefix + ":route:socket" + '\u0000' + "route-2"));
        }
    }

    @Test
    void workerCanExposeMultipleOnlineRouteOwners() {
        store.markOnline("worker-3", "websocket", "route-old", "conn-1", "connected");
        store.markOnline("worker-3", "socket", "route-new", "conn-2", "reconnected");

        assertTrue(store.isRouteOnline("websocket", "route-old"));
        assertTrue(store.isRouteOnline("socket", "route-new"));
        assertEquals(2, store.findOwners("worker-3").size());
        assertEquals("socket", store.getPresence("worker-3").getAdapterId());
        assertEquals("worker-3", observerCommands.get(namespacePrefix + ":route:socket" + '\u0000' + "route-new"));
        store.markOffline("worker-3", "socket", "route-new", "conn-2", "disconnect");
        assertTrue(store.isRouteOnline("websocket", "route-old"));
        assertFalse(store.isRouteOnline("socket", "route-new"));
        assertEquals(1, store.findOwners("worker-3").size());
    }

    @Test
    void reconnectOnSameRouteRejectsStaleHeartbeatAndDisconnect() {
        store.markOnline("worker-3", "websocket", "route-1", "conn-old", "connected");
        store.markOnline("worker-3", "websocket", "route-1", "conn-new", "reconnected");

        WorkerPresence ignoredHeartbeat = store.refreshHeartbeat("worker-3", "websocket", "route-1", "conn-old", "stale-heartbeat");
        assertNotNull(ignoredHeartbeat);
        assertEquals(WorkerPresenceState.ONLINE, ignoredHeartbeat.getPresenceState());
        assertEquals("conn-new", ignoredHeartbeat.getConnectionId());
        assertEquals("route-1", ignoredHeartbeat.getRouteKey());
        assertTrue(store.isRouteOnline("websocket", "route-1"));

        WorkerPresence ignoredOffline = store.markOffline("worker-3", "websocket", "route-1", "conn-old", "stale-disconnect");
        assertNotNull(ignoredOffline);
        assertEquals(WorkerPresenceState.ONLINE, ignoredOffline.getPresenceState());
        assertTrue(store.isRouteOnline("websocket", "route-1"));

        WorkerPresence finalOffline = store.markOffline("worker-3", "websocket", "route-1", "conn-new", "disconnect");
        assertNotNull(finalOffline);
        assertEquals(WorkerPresenceState.OFFLINE, finalOffline.getPresenceState());
        assertFalse(store.isRouteOnline("websocket", "route-1"));
    }

    @Test
    void sharedNamespaceAllowsAnotherTransportInstanceToReadPresenceTruth() {
        try (StatefulRedisConnection<String, String> secondaryConnection = redisClient.connect();
             RedisWorkerPresenceStore secondary =
                     new RedisWorkerPresenceStore(secondaryConnection, namespacePrefix, 1_000L, "runtime-b")) {
            store.markOnline("worker-4", "websocket", "route-4", "conn-4", "connected");

            WorkerPresence mirrored = secondary.getPresence("worker-4");
            assertNotNull(mirrored);
            assertEquals(WorkerPresenceState.ONLINE, mirrored.getPresenceState());
            assertEquals("websocket", mirrored.getAdapterId());
            assertEquals("route-4", mirrored.getRouteKey());
            assertTrue(secondary.isRouteOnline("websocket", "route-4"));

            secondary.markOffline("worker-4", "websocket", "route-4", "conn-4b", "disconnect");

            WorkerPresence stillOnline = store.getPresence("worker-4");
            assertNotNull(stillOnline);
            assertEquals(WorkerPresenceState.ONLINE, stillOnline.getPresenceState());
            assertTrue(store.isRouteOnline("websocket", "route-4"));

            secondary.markOffline("worker-4", "websocket", "route-4", "conn-4", "disconnect");

            WorkerPresence offline = store.getPresence("worker-4");
            assertNotNull(offline);
            assertEquals(WorkerPresenceState.OFFLINE, offline.getPresenceState());
            assertFalse(store.isRouteOnline("websocket", "route-4"));
        }
    }

    @Test
    void crossInstanceSameRouteReconnectKeepsNewestOwnerAliveWhenOldInstanceSignalsLate() {
        try (StatefulRedisConnection<String, String> secondaryConnection = redisClient.connect();
             RedisWorkerPresenceStore secondary =
                     new RedisWorkerPresenceStore(secondaryConnection, namespacePrefix, 1_000L, "runtime-b")) {
            store.markOnline("worker-6", "websocket", "route-1", "conn-old", "connected");
            secondary.markOnline("worker-6", "websocket", "route-1", "conn-new", "reconnected");

            WorkerPresence staleHeartbeat = store.refreshHeartbeat(
                    "worker-6",
                    "websocket",
                    "route-1",
                    "conn-old",
                    "late-heartbeat"
            );
            assertNotNull(staleHeartbeat);
            assertEquals(WorkerPresenceState.ONLINE, staleHeartbeat.getPresenceState());
            assertEquals("conn-new", staleHeartbeat.getConnectionId());
            assertEquals("websocket", staleHeartbeat.getAdapterId());
            assertEquals("route-1", staleHeartbeat.getRouteKey());
            assertTrue(store.isRouteOnline("websocket", "route-1"));

            WorkerPresence staleOffline = store.markOffline(
                    "worker-6",
                    "websocket",
                    "route-1",
                    "conn-old",
                    "late-disconnect"
            );
            assertNotNull(staleOffline);
            assertEquals(WorkerPresenceState.ONLINE, staleOffline.getPresenceState());
            assertEquals("conn-new", staleOffline.getConnectionId());
            assertTrue(secondary.isRouteOnline("websocket", "route-1"));

            WorkerPresence finalOffline = secondary.markOffline(
                    "worker-6",
                    "websocket",
                    "route-1",
                    "conn-new",
                    "disconnect"
            );
            assertNotNull(finalOffline);
            assertEquals(WorkerPresenceState.OFFLINE, finalOffline.getPresenceState());
            assertFalse(store.isRouteOnline("websocket", "route-1"));
        }
    }

    @Test
    void staleLeaseObservedByAnotherInstanceConvergesToSharedUnreachableState() throws Exception {
        try (RedisWorkerPresenceStore shortLeaseWriter =
                     new RedisWorkerPresenceStore(redisClient, namespacePrefix, 25L, "runtime-a", false);
             StatefulRedisConnection<String, String> readerConnection = redisClient.connect();
             RedisWorkerPresenceStore reader =
                     new RedisWorkerPresenceStore(readerConnection, namespacePrefix, 25L, "runtime-b")) {
            shortLeaseWriter.markOnline("worker-5", "socket", "route-5", "conn-5", "connected");

            Thread.sleep(40L);

            WorkerPresence stale = reader.getPresence("worker-5");
            assertNotNull(stale);
            assertEquals(WorkerPresenceState.STALE, stale.getPresenceState());
            assertFalse(reader.isRouteOnline("socket", "route-5"));
            assertFalse(shortLeaseWriter.isRouteOnline("socket", "route-5"));
            assertTrue(shortLeaseWriter.listActivePresences().isEmpty());
        }
    }
}
