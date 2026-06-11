package com.xa.mass.transport.runtime.presence;

import com.xa.mass.transport.presence.WorkerPresence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryWorkerPresenceStoreTest {

    @Test
    void expiredOwnerEvidenceDropsRouteOnlineView() throws Exception {
        InMemoryWorkerPresenceStore store = new InMemoryWorkerPresenceStore(25L, "runtime-a");

        store.claimRouteOwner("worker-1", "websocket", "route-1", "conn-1", "connected");

        assertTrue(store.isWorkerOnline("worker-1"));
        assertTrue(store.hasActiveRouteOwner("websocket", "route-1"));
        assertEquals(1, store.listActivePresences().size());

        Thread.sleep(40L);

        WorkerPresence presence = store.getPresence("worker-1");
        assertNotNull(presence);
        assertFalse(presence.isLeaseActive(System.currentTimeMillis()));
        assertFalse(store.isWorkerOnline("worker-1"));
        assertFalse(store.hasActiveRouteOwner("websocket", "route-1"));
        assertTrue(store.listActivePresences().isEmpty());
        assertEquals(1, store.pruneExpired());
        assertEquals(0, store.pruneExpired());
        assertEquals(0, store.listActivePresences().size());
        assertNull(store.getPresence("worker-1"));
    }

    @Test
    void routeKeyTakeoverReplacesCurrentOwnerAcrossAdapters() {
        InMemoryWorkerPresenceStore store = new InMemoryWorkerPresenceStore(30_000L, "runtime-a");

        store.claimRouteOwner("worker-1", "websocket", "route-1", "conn-1", "connected");
        store.claimRouteOwner("worker-1", "socket", "route-1", "conn-9", "reconnected");

        assertFalse(store.hasActiveRouteOwner("websocket", "route-1"));
        assertTrue(store.hasActiveRouteOwner("socket", "route-1"));
        assertEquals(1, store.findOwners("worker-1").size());
        assertEquals("socket", store.currentOwner("route-1").orElseThrow().adapterId());

        WorkerPresence onlinePresence = store.getPresence("worker-1");
        assertNotNull(onlinePresence);
        assertEquals("socket", onlinePresence.getAdapterId());
        assertEquals("route-1", onlinePresence.getRouteKey());
        assertTrue(onlinePresence.isLeaseActive(System.currentTimeMillis()));

        store.releaseRouteOwner("worker-1", "websocket", "route-1", "conn-1", "stale-disconnect");
        assertTrue(store.hasActiveRouteOwner("socket", "route-1"));

        store.releaseRouteOwner("worker-1", "socket", "route-1", "conn-9", "disconnect");

        assertNull(store.getPresence("worker-1"));
        assertFalse(store.hasActiveRouteOwner("socket", "route-1"));
        assertTrue(store.findOwners("worker-1").isEmpty());
    }

    @Test
    void workerProjectionFindOwnersReturnsOnlyLatestOnlineRoute() {
        InMemoryWorkerPresenceStore store = new InMemoryWorkerPresenceStore(30_000L, "runtime-a");

        store.claimRouteOwner("worker-1", "websocket", "route-old", "conn-old", "connected");
        store.claimRouteOwner("worker-1", "socket", "route-new", "conn-new", "connected");

        assertEquals(1, store.findOwners("worker-1").size());
        assertEquals("route-new", store.findOwners("worker-1").getFirst().routeKey());
        assertEquals("route-old", store.currentOwner("route-old").orElseThrow().routeKey());
    }

    @Test
    void reconnectOnSameRouteRejectsStaleHeartbeatAndDisconnect() {
        InMemoryWorkerPresenceStore store = new InMemoryWorkerPresenceStore(30_000L, "runtime-a");

        store.claimRouteOwner("worker-1", "websocket", "route-1", "conn-old", "connected");
        store.claimRouteOwner("worker-1", "websocket", "route-1", "conn-new", "reconnected");

        WorkerPresence ignoredHeartbeat = store.refreshHeartbeat("worker-1", "websocket", "route-1", "conn-old", "stale-heartbeat");
        assertNotNull(ignoredHeartbeat);
        assertEquals("conn-new", ignoredHeartbeat.getConnectionId());
        assertEquals("route-1", ignoredHeartbeat.getRouteKey());
        assertTrue(store.hasActiveRouteOwner("websocket", "route-1"));

        WorkerPresence ignoredOffline = store.releaseRouteOwner("worker-1", "websocket", "route-1", "conn-old", "stale-disconnect");
        assertNotNull(ignoredOffline);
        assertEquals("conn-new", ignoredOffline.getConnectionId());
        assertTrue(store.hasActiveRouteOwner("websocket", "route-1"));

        WorkerPresence finalOffline = store.releaseRouteOwner("worker-1", "websocket", "route-1", "conn-new", "disconnect");
        assertNotNull(finalOffline);
        assertEquals("conn-new", finalOffline.getConnectionId());
        assertFalse(store.hasActiveRouteOwner("websocket", "route-1"));
    }
}
