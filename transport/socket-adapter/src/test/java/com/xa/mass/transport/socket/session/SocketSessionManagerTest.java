package com.xa.mass.transport.socket.session;

import com.xa.mass.transport.runtime.route.InMemoryTransportRouteOwnerStore;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SocketSessionManagerTest {

    @Test
    void connectHeartbeatDisconnectProjectRouteOwnerIntoTransportStore() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore(30_000L, "socket-node-1");
        SocketSessionManager manager = new SocketSessionManager("socket");
        manager.setRouteOwnerStore(routeOwnerStore);

        manager.addSession("route-1", "worker-1", "endpoint-1", activeSocket(), mock(BufferedWriter.class));

        assertTrue(routeOwnerStore.getLatestOwnerByWorker("worker-1").isLeaseActive(System.currentTimeMillis()));
        assertEquals("socket-node-1", routeOwnerStore.findRouteOwners("worker-1").getFirst().transportNodeId());
        assertTrue(routeOwnerStore.hasActiveRouteOwner("socket", "route-1"));

        manager.recordHeartbeat("route-1", "worker-1", "endpoint-1", "socket heartbeat", "trace-1");

        assertTrue(routeOwnerStore.getLatestOwnerByWorker("worker-1").isLeaseActive(System.currentTimeMillis()));

        manager.removeSession("endpoint-1");

        assertNull(routeOwnerStore.getLatestOwnerByWorker("worker-1"));
        assertFalse(routeOwnerStore.hasActiveRouteOwner("socket", "route-1"));
    }

    @Test
    void adapterScopedRouteLookupUsesConfiguredAdapterId() {
        SocketSessionManager manager = new SocketSessionManager("socket-edge");

        manager.addSession("route-1", "worker-1", "endpoint-1", activeSocket(), mock(BufferedWriter.class));

        assertTrue(manager.isAdapterRouteOnline("socket-edge", "route-1"));
        assertFalse(manager.isAdapterRouteOnline("socket", "route-1"));
        assertEquals("socket-edge", manager.getAdapterId());
        assertEquals("socket-edge", manager.listWorkerEndpoints().get(0).getAdapterId());
    }

    @Test
    void staleEndpointHeartbeatAndDisconnectDoNotOverrideReplacementRouteOwner() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore();
        SocketSessionManager manager = new SocketSessionManager("socket");
        manager.setRouteOwnerStore(routeOwnerStore);

        manager.addSession("route-1", "worker-1", "endpoint-old", activeSocket(), mock(BufferedWriter.class));
        manager.addSession("route-1", "worker-1", "endpoint-new", activeSocket(), mock(BufferedWriter.class));

        manager.recordHeartbeat("route-1", "worker-1", "endpoint-old", "stale-heartbeat", "trace-old");

        assertTrue(routeOwnerStore.getLatestOwnerByWorker("worker-1").isLeaseActive(System.currentTimeMillis()));
        assertEquals("endpoint-new", routeOwnerStore.getLatestOwnerByWorker("worker-1").getConnectionId());

        manager.removeSession("endpoint-old");

        assertTrue(routeOwnerStore.hasActiveRouteOwner("socket", "route-1"));
        assertTrue(routeOwnerStore.getLatestOwnerByWorker("worker-1").isLeaseActive(System.currentTimeMillis()));

        manager.removeSession("endpoint-new");

        assertNull(routeOwnerStore.getLatestOwnerByWorker("worker-1"));
        assertFalse(routeOwnerStore.hasActiveRouteOwner("socket", "route-1"));
    }

    @Test
    void blankConnectionIdOnRouteOwnerClaimGeneratesOwnedConnectionId() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore();

        var owner = routeOwnerStore.claimRouteOwner("worker-1", "socket", "route-1", " ", "connected");

        assertNotNull(owner);
        assertNotNull(owner.getConnectionId());
        assertFalse(owner.getConnectionId().isBlank());
    }

    @Test
    void shutdownReleasesRouteOwnerBeforeClearingRoutes() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore();
        SocketSessionManager manager = new SocketSessionManager("socket");
        manager.setRouteOwnerStore(routeOwnerStore);

        manager.addSession("route-1", "worker-1", "endpoint-1", activeSocket(), mock(BufferedWriter.class));

        manager.shutdown();

        assertNull(routeOwnerStore.getLatestOwnerByWorker("worker-1"));
        assertFalse(routeOwnerStore.hasActiveRouteOwner("socket", "route-1"));
    }

    @Test
    void setRouteOwnerStoreReprojectsActiveSocketSessions() {
        InMemoryTransportRouteOwnerStore firstStore = new InMemoryTransportRouteOwnerStore(30_000L, "socket-node-1");
        InMemoryTransportRouteOwnerStore secondStore = new InMemoryTransportRouteOwnerStore(30_000L, "socket-node-2");
        SocketSessionManager manager = new SocketSessionManager("socket");
        manager.setRouteOwnerStore(firstStore);

        manager.addSession("route-1", "worker-1", "endpoint-1", activeSocket(), mock(BufferedWriter.class));
        manager.setRouteOwnerStore(secondStore);

        assertTrue(secondStore.getLatestOwnerByWorker("worker-1").isLeaseActive(System.currentTimeMillis()));
        assertEquals("route-1", secondStore.getLatestOwnerByWorker("worker-1").getRouteKey());
        assertEquals("endpoint-1", secondStore.getLatestOwnerByWorker("worker-1").getConnectionId());
        assertEquals("socket-node-2", secondStore.findRouteOwners("worker-1").getFirst().transportNodeId());
        assertTrue(secondStore.hasActiveRouteOwner("socket", "route-1"));
    }

    private Socket activeSocket() {
        Socket socket = mock(Socket.class);
        when(socket.isConnected()).thenReturn(true);
        when(socket.isClosed()).thenReturn(false);
        return socket;
    }
}
