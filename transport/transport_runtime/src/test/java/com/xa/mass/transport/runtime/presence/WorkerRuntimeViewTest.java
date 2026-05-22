package com.xa.mass.transport.runtime.presence;

import com.xa.mass.transport.presence.WorkerPresenceState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerRuntimeViewTest {

    @Test
    void unifiedViewReportsWorkerReachableWhenAnyRouteOwnerIsOnline() {
        InMemoryWorkerPresenceStore store = new InMemoryWorkerPresenceStore(30_000L, "node-1");

        store.markOnline("worker-1", "websocket", "route-1", "conn-1", "connected");
        store.markOnline("worker-1", "socket", "route-2", "conn-2", "connected");
        store.markOffline("worker-1", "websocket", "route-1", "conn-1", "disconnect");

        assertTrue(store.hasOnlineOwner("worker-1"));
        assertTrue(store.isWorkerOnline("worker-1"));
        assertEquals(1, store.findOwners("worker-1").size());
        assertEquals("socket", store.findOwners("worker-1").getFirst().adapterId());
    }

    @Test
    void staleRouteDoesNotParticipateInDispatchOwnerView() throws Exception {
        InMemoryWorkerPresenceStore store = new InMemoryWorkerPresenceStore(25L, "node-1");

        store.markOnline("worker-1", "websocket", "route-1", "conn-1", "connected");
        Thread.sleep(40L);

        assertEquals(WorkerPresenceState.STALE, store.getPresence("worker-1").getPresenceState());
        assertFalse(store.hasOnlineOwner("worker-1"));
        assertTrue(store.findOwners("worker-1").isEmpty());
    }
}
