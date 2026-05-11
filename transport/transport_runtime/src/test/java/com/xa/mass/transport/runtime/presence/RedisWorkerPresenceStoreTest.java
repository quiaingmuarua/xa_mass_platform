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
            for (String workerId : observerCommands.smembers(namespacePrefix + ":workers")) {
                observerCommands.del(namespacePrefix + ":worker:" + workerId);
            }
            for (String key : observerCommands.keys(namespacePrefix + ":route:*")) {
                observerCommands.del(key);
            }
            observerCommands.del(namespacePrefix + ":workers");
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
                     new RedisWorkerPresenceStore(redisClient, namespacePrefix, 25L, "runtime-a", false)) {
            shortLeaseStore.markOnline("worker-2", "socket", "route-2", "conn-2", "connected");

            assertTrue(shortLeaseStore.isRouteOnline("socket", "route-2"));
            Thread.sleep(40L);

            WorkerPresence stale = shortLeaseStore.getPresence("worker-2");
            assertNotNull(stale);
            assertEquals(WorkerPresenceState.STALE, stale.getPresenceState());
            assertFalse(shortLeaseStore.isRouteOnline("socket", "route-2"));
            assertEquals(1, shortLeaseStore.pruneExpired());
            assertTrue(shortLeaseStore.listActivePresences().isEmpty());
        }
    }

    @Test
    void newestRouteOwnershipReplacesOldRouteMapping() {
        store.markOnline("worker-3", "websocket", "route-old", "conn-1", "connected");
        store.markOnline("worker-3", "socket", "route-new", "conn-2", "reconnected");

        assertFalse(store.isRouteOnline("websocket", "route-old"));
        assertTrue(store.isRouteOnline("socket", "route-new"));
        assertEquals("worker-3", observerCommands.get(namespacePrefix + ":route:socket" + '\u0000' + "route-new"));
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

            WorkerPresence offline = store.getPresence("worker-4");
            assertNotNull(offline);
            assertEquals(WorkerPresenceState.OFFLINE, offline.getPresenceState());
            assertFalse(store.isRouteOnline("websocket", "route-4"));
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
