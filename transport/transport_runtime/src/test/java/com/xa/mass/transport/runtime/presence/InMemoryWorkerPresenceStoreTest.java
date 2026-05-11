package com.xa.mass.transport.runtime.presence;

import com.xa.mass.transport.presence.WorkerPresence;
import com.xa.mass.transport.presence.WorkerPresenceState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    }

    @Test
    void routeOwnershipMovesWithNewestOnlinePresenceAndOfflineClearsIt() {
        InMemoryWorkerPresenceStore store = new InMemoryWorkerPresenceStore(30_000L, "runtime-a");

        store.markOnline("worker-1", "websocket", "route-1", "conn-1", "connected");
        store.markOnline("worker-1", "socket", "route-9", "conn-9", "reconnected");

        assertFalse(store.isRouteOnline("websocket", "route-1"));
        assertTrue(store.isRouteOnline("socket", "route-9"));

        WorkerPresence onlinePresence = store.getPresence("worker-1");
        assertNotNull(onlinePresence);
        assertEquals("socket", onlinePresence.getAdapterId());
        assertEquals("route-9", onlinePresence.getRouteKey());
        assertEquals(WorkerPresenceState.ONLINE, onlinePresence.getPresenceState());

        store.markOffline("worker-1", "socket", "route-9", "conn-9", "disconnect");

        WorkerPresence offlinePresence = store.getPresence("worker-1");
        assertNotNull(offlinePresence);
        assertEquals(WorkerPresenceState.OFFLINE, offlinePresence.getPresenceState());
        assertFalse(store.isRouteOnline("socket", "route-9"));
        assertTrue(store.listActivePresences().isEmpty());
    }
}
