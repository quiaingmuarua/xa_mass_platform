package com.xa.mass.transport.runtime.presence;

import com.xa.mass.transport.presence.WorkerPresence;
import com.xa.mass.transport.presence.WorkerPresenceState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryWorkerPresenceStoreTest {

    @Test
    void expiredOnlinePresenceMaterializesAsStaleAndDropsRouteOnlineView() throws Exception {
        InMemoryWorkerPresenceStore store = new InMemoryWorkerPresenceStore(25L, "runtime-a");

        store.markOnline("worker-1", "websocket", "route-1", "conn-1", "connected");

        assertTrue(store.isWorkerOnline("worker-1"));
        assertTrue(store.isRouteOnline("websocket", "route-1"));
        assertEquals(1, store.listActivePresences().size());

        Thread.sleep(40L);

        WorkerPresence presence = store.getPresence("worker-1");
        assertNotNull(presence);
        assertEquals(WorkerPresenceState.STALE, presence.getPresenceState());
        assertFalse(store.isWorkerOnline("worker-1"));
        assertFalse(store.isRouteOnline("websocket", "route-1"));
        assertTrue(store.listActivePresences().isEmpty());
        assertEquals(1, store.pruneExpired());
        assertEquals(0, store.pruneExpired());
        assertEquals(0, store.listActivePresences().size());
        assertNull(store.getPresence("worker-1"));
    }

    @Test
    void workerCanExposeMultipleOnlineRouteOwnersAndOfflineOneRouteOnly() {
        InMemoryWorkerPresenceStore store = new InMemoryWorkerPresenceStore(30_000L, "runtime-a");

        store.markOnline("worker-1", "websocket", "route-1", "conn-1", "connected");
        store.markOnline("worker-1", "socket", "route-9", "conn-9", "reconnected");

        assertTrue(store.isRouteOnline("websocket", "route-1"));
        assertTrue(store.isRouteOnline("socket", "route-9"));
        assertEquals(2, store.findOwners("worker-1").size());

        WorkerPresence onlinePresence = store.getPresence("worker-1");
        assertNotNull(onlinePresence);
        assertEquals("socket", onlinePresence.getAdapterId());
        assertEquals("route-9", onlinePresence.getRouteKey());
        assertEquals(WorkerPresenceState.ONLINE, onlinePresence.getPresenceState());

        store.markOffline("worker-1", "socket", "route-9", "conn-9", "disconnect");

        WorkerPresence remainingPresence = store.getPresence("worker-1");
        assertNotNull(remainingPresence);
        assertEquals(WorkerPresenceState.ONLINE, remainingPresence.getPresenceState());
        assertEquals("websocket", remainingPresence.getAdapterId());
        assertFalse(store.isRouteOnline("socket", "route-9"));
        assertTrue(store.isRouteOnline("websocket", "route-1"));
        assertEquals(1, store.findOwners("worker-1").size());
    }

    @Test
    void reconnectOnSameRouteRejectsStaleHeartbeatAndDisconnect() {
        InMemoryWorkerPresenceStore store = new InMemoryWorkerPresenceStore(30_000L, "runtime-a");

        store.markOnline("worker-1", "websocket", "route-1", "conn-old", "connected");
        store.markOnline("worker-1", "websocket", "route-1", "conn-new", "reconnected");

        WorkerPresence ignoredHeartbeat = store.refreshHeartbeat("worker-1", "websocket", "route-1", "conn-old", "stale-heartbeat");
        assertNotNull(ignoredHeartbeat);
        assertEquals("conn-new", ignoredHeartbeat.getConnectionId());
        assertEquals("route-1", ignoredHeartbeat.getRouteKey());
        assertTrue(store.isRouteOnline("websocket", "route-1"));

        WorkerPresence ignoredOffline = store.markOffline("worker-1", "websocket", "route-1", "conn-old", "stale-disconnect");
        assertNotNull(ignoredOffline);
        assertEquals(WorkerPresenceState.ONLINE, ignoredOffline.getPresenceState());
        assertTrue(store.isRouteOnline("websocket", "route-1"));

        WorkerPresence finalOffline = store.markOffline("worker-1", "websocket", "route-1", "conn-new", "disconnect");
        assertNotNull(finalOffline);
        assertEquals(WorkerPresenceState.OFFLINE, finalOffline.getPresenceState());
        assertFalse(store.isRouteOnline("websocket", "route-1"));
    }
}
