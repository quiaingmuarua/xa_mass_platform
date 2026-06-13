package com.xa.mass.transport.socket.session;

import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerSessionPresenceEvent;
import com.xa.mass.transport.runtime.route.InMemoryTransportRouteOwnerStore;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

class SocketSessionManagerTest {

    @Test
    void connectHeartbeatDisconnectProjectRouteOwnerIntoTransportStore() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore(30_000L, "socket-node-1");
        RecordingWorkerPresenceIngress presenceIngress = new RecordingWorkerPresenceIngress();
        SocketSessionManager manager = new SocketSessionManager("socket");
        manager.setRouteOwnerStore(routeOwnerStore);
        manager.setWorkerPresenceIngress(presenceIngress);

        manager.addSession("route-1", "worker-1", "endpoint-1", activeSocket(), mock(BufferedWriter.class));

        assertTrue(routeOwnerStore.activeOwnerForSelectedWorker("socket", "worker-1")
                .orElseThrow()
                .isActive(System.currentTimeMillis()));
        assertEquals("socket-node-1", routeOwnerStore.activeOwnerForSelectedWorker("socket", "worker-1")
                .orElseThrow()
                .transportNodeId());
        assertTrue(routeOwnerStore.hasActiveRouteOwner("socket", "route-1"));
        assertEquals(List.of("connected:worker-1:socket:route-1:endpoint-1:socket connected:endpoint-1"),
                presenceIngress.events);

        manager.recordHeartbeat("route-1", "worker-1", "endpoint-1", "socket heartbeat", "trace-1");

        assertTrue(routeOwnerStore.activeOwnerForSelectedWorker("socket", "worker-1")
                .orElseThrow()
                .isActive(System.currentTimeMillis()));
        assertEquals(List.of(
                "connected:worker-1:socket:route-1:endpoint-1:socket connected:endpoint-1",
                "heartbeat:worker-1:socket:route-1:endpoint-1:socket heartbeat:trace-1"
        ), presenceIngress.events);

        manager.removeSession("endpoint-1");

        assertTrue(routeOwnerStore.activeOwnerForSelectedWorker("socket", "worker-1").isEmpty());
        assertFalse(routeOwnerStore.hasActiveRouteOwner("socket", "route-1"));
        assertEquals(List.of(
                "connected:worker-1:socket:route-1:endpoint-1:socket connected:endpoint-1",
                "heartbeat:worker-1:socket:route-1:endpoint-1:socket heartbeat:trace-1",
                "disconnected:worker-1:socket:route-1:endpoint-1:socket disconnected:endpoint-1"
        ), presenceIngress.events);
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
    void selectedWorkerSendUsesWorkerIndexUnderSharedRouteKey() throws IOException {
        SocketSessionManager manager = new SocketSessionManager("socket");
        BufferedWriter firstWriter = mock(BufferedWriter.class);
        BufferedWriter secondWriter = mock(BufferedWriter.class);

        manager.addSession("group-route", "worker-1", "endpoint-1", activeSocket(), firstWriter);
        manager.addSession("group-route", "worker-2", "endpoint-2", activeSocket(), secondWriter);

        assertTrue(manager.sendToSelectedWorker("socket", "worker-2", "{\"messageId\":\"msg-2\"}"));

        verify(firstWriter, never()).write(anyString());
        verify(secondWriter).write("{\"messageId\":\"msg-2\"}");
    }

    @Test
    void staleEndpointHeartbeatAndDisconnectDoNotOverrideReplacementRouteOwner() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore();
        RecordingWorkerPresenceIngress presenceIngress = new RecordingWorkerPresenceIngress();
        SocketSessionManager manager = new SocketSessionManager("socket");
        manager.setRouteOwnerStore(routeOwnerStore);
        manager.setWorkerPresenceIngress(presenceIngress);

        manager.addSession("route-1", "worker-1", "endpoint-old", activeSocket(), mock(BufferedWriter.class));
        manager.addSession("route-1", "worker-1", "endpoint-new", activeSocket(), mock(BufferedWriter.class));
        assertEquals(List.of(
                "connected:worker-1:socket:route-1:endpoint-old:socket connected:endpoint-old",
                "connected:worker-1:socket:route-1:endpoint-new:socket connected:endpoint-new",
                "disconnected:worker-1:socket:route-1:endpoint-old:socket session replaced:endpoint-old"
        ), presenceIngress.events);

        manager.recordHeartbeat("route-1", "worker-1", "endpoint-old", "stale-heartbeat", "trace-old");

        assertTrue(routeOwnerStore.activeOwnerForSelectedWorker("socket", "worker-1")
                .orElseThrow()
                .isActive(System.currentTimeMillis()));
        assertEquals("endpoint-new", routeOwnerStore.activeOwnerForSelectedWorker("socket", "worker-1")
                .orElseThrow()
                .connectionId());

        manager.removeSession("endpoint-old");

        assertTrue(routeOwnerStore.hasActiveRouteOwner("socket", "route-1"));
        assertTrue(routeOwnerStore.activeOwnerForSelectedWorker("socket", "worker-1")
                .orElseThrow()
                .isActive(System.currentTimeMillis()));
        assertEquals(List.of(
                "connected:worker-1:socket:route-1:endpoint-old:socket connected:endpoint-old",
                "connected:worker-1:socket:route-1:endpoint-new:socket connected:endpoint-new",
                "disconnected:worker-1:socket:route-1:endpoint-old:socket session replaced:endpoint-old"
        ), presenceIngress.events);

        manager.removeSession("endpoint-new");

        assertTrue(routeOwnerStore.activeOwnerForSelectedWorker("socket", "worker-1").isEmpty());
        assertFalse(routeOwnerStore.hasActiveRouteOwner("socket", "route-1"));
        assertEquals(List.of(
                "connected:worker-1:socket:route-1:endpoint-old:socket connected:endpoint-old",
                "connected:worker-1:socket:route-1:endpoint-new:socket connected:endpoint-new",
                "disconnected:worker-1:socket:route-1:endpoint-old:socket session replaced:endpoint-old",
                "disconnected:worker-1:socket:route-1:endpoint-new:socket disconnected:endpoint-new"
        ), presenceIngress.events);
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

        assertTrue(routeOwnerStore.activeOwnerForSelectedWorker("socket", "worker-1").isEmpty());
        assertFalse(routeOwnerStore.hasActiveRouteOwner("socket", "route-1"));
    }

    @Test
    void replacingSelectedWorkerWithDifferentRouteRetiresOldEndpoint() throws IOException {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore();
        SocketSessionManager manager = new SocketSessionManager("socket");
        manager.setRouteOwnerStore(routeOwnerStore);
        BufferedWriter oldWriter = mock(BufferedWriter.class);
        BufferedWriter newWriter = mock(BufferedWriter.class);
        Socket oldSocket = activeSocket();
        Socket newSocket = activeSocket();

        manager.addSession("route-old", "worker-1", "endpoint-old", oldSocket, oldWriter);
        manager.addSession("route-new", "worker-1", "endpoint-new", newSocket, newWriter);

        assertEquals(1, manager.getActiveConnectionCount());
        assertFalse(routeOwnerStore.hasActiveRouteOwner("socket", "route-old"));
        assertTrue(routeOwnerStore.hasActiveRouteOwner("socket", "route-new"));
        assertEquals("route-new", routeOwnerStore.activeOwnerForSelectedWorker("socket", "worker-1")
                .orElseThrow()
                .routeKey());
        verify(oldSocket).close();

        assertTrue(manager.sendToSelectedWorker("socket", "worker-1", "{\"messageId\":\"msg-new\"}"));
        verify(oldWriter, never()).write(anyString());
        verify(newWriter).write("{\"messageId\":\"msg-new\"}");
    }

    @Test
    void setRouteOwnerStoreReprojectsActiveSocketSessions() {
        InMemoryTransportRouteOwnerStore firstStore = new InMemoryTransportRouteOwnerStore(30_000L, "socket-node-1");
        InMemoryTransportRouteOwnerStore secondStore = new InMemoryTransportRouteOwnerStore(30_000L, "socket-node-2");
        SocketSessionManager manager = new SocketSessionManager("socket");
        manager.setRouteOwnerStore(firstStore);

        manager.addSession("route-1", "worker-1", "endpoint-1", activeSocket(), mock(BufferedWriter.class));
        manager.setRouteOwnerStore(secondStore);

        assertTrue(secondStore.activeOwnerForSelectedWorker("socket", "worker-1")
                .orElseThrow()
                .isActive(System.currentTimeMillis()));
        assertEquals("route-1", secondStore.activeOwnerForSelectedWorker("socket", "worker-1")
                .orElseThrow()
                .routeKey());
        assertEquals("endpoint-1", secondStore.activeOwnerForSelectedWorker("socket", "worker-1")
                .orElseThrow()
                .connectionId());
        assertEquals("socket-node-2", secondStore.activeOwnerForSelectedWorker("socket", "worker-1")
                .orElseThrow()
                .transportNodeId());
        assertTrue(secondStore.hasActiveRouteOwner("socket", "route-1"));
    }

    private Socket activeSocket() {
        Socket socket = mock(Socket.class);
        when(socket.isConnected()).thenReturn(true);
        when(socket.isClosed()).thenReturn(false);
        return socket;
    }

    private static final class RecordingWorkerPresenceIngress implements WorkerPresenceIngress {
        private final List<String> events = new ArrayList<>();

        @Override
        public void sessionConnected(WorkerSessionPresenceEvent event) {
            events.add("connected:" + describe(event));
        }

        @Override
        public void sessionHeartbeat(WorkerSessionPresenceEvent event) {
            events.add("heartbeat:" + describe(event));
        }

        @Override
        public void sessionDisconnected(WorkerSessionPresenceEvent event) {
            events.add("disconnected:" + describe(event));
        }

        private static String describe(WorkerSessionPresenceEvent event) {
            return event.workerId()
                    + ":" + event.adapterId()
                    + ":" + event.routeKey()
                    + ":" + event.sessionToken()
                    + ":" + event.reason()
                    + ":" + event.traceId();
        }
    }
}
