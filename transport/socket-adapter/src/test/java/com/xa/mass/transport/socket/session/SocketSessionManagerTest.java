package com.xa.mass.transport.socket.session;

import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.runtime.presence.InMemoryWorkerPresenceStore;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SocketSessionManagerTest {

    @Test
    void connectHeartbeatDisconnectProjectPresenceIntoTransportStore() {
        WorkerSystemEventChannel systemEventChannel = mock(WorkerSystemEventChannel.class);
        InMemoryWorkerPresenceStore presenceStore = new InMemoryWorkerPresenceStore(30_000L, "socket-node-1");
        SocketSessionManager manager = new SocketSessionManager("socket", systemEventChannel);
        manager.setWorkerPresenceStore(presenceStore);

        manager.addSession("route-1", "worker-1", "endpoint-1", activeSocket(), mock(BufferedWriter.class));

        assertTrue(presenceStore.getPresence("worker-1").isLeaseActive(System.currentTimeMillis()));
        assertEquals("socket-node-1", presenceStore.findOwners("worker-1").getFirst().transportNodeId());
        assertTrue(presenceStore.hasActiveRouteOwner("socket", "route-1"));
        verify(systemEventChannel).publishWorkerOnline("worker-1", "socket connected", null);

        manager.recordHeartbeat("route-1", "worker-1", "endpoint-1", "socket heartbeat", "trace-1");

        assertTrue(presenceStore.getPresence("worker-1").isLeaseActive(System.currentTimeMillis()));
        verify(systemEventChannel).publishWorkerHeartbeat("worker-1", "socket heartbeat", "trace-1");

        manager.removeSession("endpoint-1");

        assertNull(presenceStore.getPresence("worker-1"));
        assertFalse(presenceStore.hasActiveRouteOwner("socket", "route-1"));
        verify(systemEventChannel).publishWorkerOffline("worker-1", "socket disconnected", null);
    }

    @Test
    void adapterScopedRouteLookupUsesConfiguredAdapterId() {
        SocketSessionManager manager = new SocketSessionManager("socket-edge", null);

        manager.addSession("route-1", "worker-1", "endpoint-1", activeSocket(), mock(BufferedWriter.class));

        assertTrue(manager.isAdapterRouteOnline("socket-edge", "route-1"));
        assertFalse(manager.isAdapterRouteOnline("socket", "route-1"));
        assertEquals("socket-edge", manager.getAdapterId());
        assertEquals("socket-edge", manager.listWorkerEndpoints().get(0).getAdapterId());
    }

    @Test
    void staleEndpointHeartbeatAndDisconnectDoNotOverrideReplacementPresence() {
        InMemoryWorkerPresenceStore presenceStore = new InMemoryWorkerPresenceStore();
        SocketSessionManager manager = new SocketSessionManager("socket", mock(WorkerSystemEventChannel.class));
        manager.setWorkerPresenceStore(presenceStore);

        manager.addSession("route-1", "worker-1", "endpoint-old", activeSocket(), mock(BufferedWriter.class));
        manager.addSession("route-1", "worker-1", "endpoint-new", activeSocket(), mock(BufferedWriter.class));

        manager.recordHeartbeat("route-1", "worker-1", "endpoint-old", "stale-heartbeat", "trace-old");

        assertTrue(presenceStore.getPresence("worker-1").isLeaseActive(System.currentTimeMillis()));
        assertEquals("endpoint-new", presenceStore.getPresence("worker-1").getConnectionId());

        manager.removeSession("endpoint-old");

        assertTrue(presenceStore.hasActiveRouteOwner("socket", "route-1"));
        assertTrue(presenceStore.getPresence("worker-1").isLeaseActive(System.currentTimeMillis()));

        manager.removeSession("endpoint-new");

        assertNull(presenceStore.getPresence("worker-1"));
        assertFalse(presenceStore.hasActiveRouteOwner("socket", "route-1"));
    }

    @Test
    void blankConnectionIdOnMarkOnlineGeneratesOwnedConnectionId() {
        InMemoryWorkerPresenceStore presenceStore = new InMemoryWorkerPresenceStore();

        var presence = presenceStore.claimRouteOwner("worker-1", "socket", "route-1", " ", "connected");

        assertNotNull(presence);
        assertNotNull(presence.getConnectionId());
        assertFalse(presence.getConnectionId().isBlank());
    }

    @Test
    void shutdownMarksPresenceOfflineBeforeClearingRoutes() {
        InMemoryWorkerPresenceStore presenceStore = new InMemoryWorkerPresenceStore();
        WorkerSystemEventChannel systemEventChannel = mock(WorkerSystemEventChannel.class);
        SocketSessionManager manager = new SocketSessionManager("socket", systemEventChannel);
        manager.setWorkerPresenceStore(presenceStore);

        manager.addSession("route-1", "worker-1", "endpoint-1", activeSocket(), mock(BufferedWriter.class));

        manager.shutdown();

        assertNull(presenceStore.getPresence("worker-1"));
        assertFalse(presenceStore.hasActiveRouteOwner("socket", "route-1"));
        verify(systemEventChannel).publishWorkerOffline("worker-1", "socket adapter shutdown", null);
    }

    @Test
    void setWorkerPresenceStoreReprojectsActiveSocketSessions() {
        InMemoryWorkerPresenceStore firstStore = new InMemoryWorkerPresenceStore(30_000L, "socket-node-1");
        InMemoryWorkerPresenceStore secondStore = new InMemoryWorkerPresenceStore(30_000L, "socket-node-2");
        SocketSessionManager manager = new SocketSessionManager("socket", mock(WorkerSystemEventChannel.class));
        manager.setWorkerPresenceStore(firstStore);

        manager.addSession("route-1", "worker-1", "endpoint-1", activeSocket(), mock(BufferedWriter.class));
        manager.setWorkerPresenceStore(secondStore);

        assertTrue(secondStore.getPresence("worker-1").isLeaseActive(System.currentTimeMillis()));
        assertEquals("route-1", secondStore.getPresence("worker-1").getRouteKey());
        assertEquals("endpoint-1", secondStore.getPresence("worker-1").getConnectionId());
        assertEquals("socket-node-2", secondStore.findOwners("worker-1").getFirst().transportNodeId());
        assertTrue(secondStore.hasActiveRouteOwner("socket", "route-1"));
    }

    private Socket activeSocket() {
        Socket socket = mock(Socket.class);
        when(socket.isConnected()).thenReturn(true);
        when(socket.isClosed()).thenReturn(false);
        return socket;
    }
}
