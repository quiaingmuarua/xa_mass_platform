package com.xa.mass.transport.socket.session;

import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerSessionPresenceEvent;
import com.xa.mass.transport.lease.TransportEndpointLeaseViewRecord;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

class SocketSessionManagerTest {

    private static final String DELIVERY_BUCKET_ID = "bucket-1";

    @Test
    void connectHeartbeatDisconnectProjectEndpointLeaseIntoTransportStore() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore =
                new InMemoryTransportEndpointLeaseStore(30_000L);
        RecordingWorkerPresenceIngress presenceIngress = new RecordingWorkerPresenceIngress();
        SocketSessionManager manager = new SocketSessionManager("socket");
        manager.setEndpointLeaseStore(endpointLeaseStore);
        manager.setWorkerPresenceIngress(presenceIngress);

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", "endpoint-1",
                activeSocket(), mock(BufferedWriter.class));

        assertEquals("route-1", endpoint(endpointLeaseStore, "worker-1").endpointAddress());
        assertEquals(List.of("connected:worker-1:socket:route-1:endpoint-1:socket connected:endpoint-1"),
                presenceIngress.events);

        manager.recordHeartbeat("route-1", "worker-1", "endpoint-1", "socket heartbeat", "trace-1");

        assertTrue(hasEndpoint(endpointLeaseStore, "worker-1"));
        assertEquals(List.of(
                "connected:worker-1:socket:route-1:endpoint-1:socket connected:endpoint-1",
                "heartbeat:worker-1:socket:route-1:endpoint-1:socket heartbeat:trace-1"
        ), presenceIngress.events);

        manager.removeSession("endpoint-1");

        assertFalse(hasEndpoint(endpointLeaseStore, "worker-1"));
        assertEquals(List.of(
                "connected:worker-1:socket:route-1:endpoint-1:socket connected:endpoint-1",
                "heartbeat:worker-1:socket:route-1:endpoint-1:socket heartbeat:trace-1",
                "disconnected:worker-1:socket:route-1:endpoint-1:socket disconnected:endpoint-1"
        ), presenceIngress.events);
    }

    @Test
    void adapterScopedRouteLookupUsesConfiguredAdapterId() {
        SocketSessionManager manager = new SocketSessionManager("socket-edge");

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", "endpoint-1",
                activeSocket(), mock(BufferedWriter.class));

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

        manager.addSession(DELIVERY_BUCKET_ID, "group-route", "worker-1", "endpoint-1", activeSocket(), firstWriter);
        manager.addSession(DELIVERY_BUCKET_ID, "group-route", "worker-2", "endpoint-2", activeSocket(), secondWriter);

        assertTrue(manager.sendToSelectedWorker("socket", "worker-2", "{\"messageId\":\"msg-2\"}"));

        verify(firstWriter, never()).write(anyString());
        verify(secondWriter).write("{\"messageId\":\"msg-2\"}");
    }

    @Test
    void staleEndpointHeartbeatAndDisconnectDoNotOverrideReplacementEndpointLease() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore();
        RecordingWorkerPresenceIngress presenceIngress = new RecordingWorkerPresenceIngress();
        SocketSessionManager manager = new SocketSessionManager("socket");
        manager.setEndpointLeaseStore(endpointLeaseStore);
        manager.setWorkerPresenceIngress(presenceIngress);

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", "endpoint-old",
                activeSocket(), mock(BufferedWriter.class));
        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", "endpoint-new",
                activeSocket(), mock(BufferedWriter.class));
        assertEquals(List.of(
                "connected:worker-1:socket:route-1:endpoint-old:socket connected:endpoint-old",
                "connected:worker-1:socket:route-1:endpoint-new:socket connected:endpoint-new",
                "disconnected:worker-1:socket:route-1:endpoint-old:socket session replaced:endpoint-old"
        ), presenceIngress.events);

        manager.recordHeartbeat("route-1", "worker-1", "endpoint-old", "stale-heartbeat", "trace-old");

        assertTrue(hasEndpoint(endpointLeaseStore, "worker-1"));
        assertEquals("endpoint-new", endpoint(endpointLeaseStore, "worker-1").endpointLeaseId());

        manager.removeSession("endpoint-old");

        assertTrue(hasEndpoint(endpointLeaseStore, "worker-1"));
        assertEquals(List.of(
                "connected:worker-1:socket:route-1:endpoint-old:socket connected:endpoint-old",
                "connected:worker-1:socket:route-1:endpoint-new:socket connected:endpoint-new",
                "disconnected:worker-1:socket:route-1:endpoint-old:socket session replaced:endpoint-old"
        ), presenceIngress.events);

        manager.removeSession("endpoint-new");

        assertFalse(hasEndpoint(endpointLeaseStore, "worker-1"));
        assertEquals(List.of(
                "connected:worker-1:socket:route-1:endpoint-old:socket connected:endpoint-old",
                "connected:worker-1:socket:route-1:endpoint-new:socket connected:endpoint-new",
                "disconnected:worker-1:socket:route-1:endpoint-old:socket session replaced:endpoint-old",
                "disconnected:worker-1:socket:route-1:endpoint-new:socket disconnected:endpoint-new"
        ), presenceIngress.events);
    }

    @Test
    void shutdownReleasesEndpointLeaseBeforeClearingRoutes() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore();
        SocketSessionManager manager = new SocketSessionManager("socket");
        manager.setEndpointLeaseStore(endpointLeaseStore);

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", "endpoint-1",
                activeSocket(), mock(BufferedWriter.class));

        manager.shutdown();

        assertFalse(hasEndpoint(endpointLeaseStore, "worker-1"));
    }

    @Test
    void replacingSelectedWorkerWithDifferentRouteRetiresOldEndpoint() throws IOException {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore();
        SocketSessionManager manager = new SocketSessionManager("socket");
        manager.setEndpointLeaseStore(endpointLeaseStore);
        BufferedWriter oldWriter = mock(BufferedWriter.class);
        BufferedWriter newWriter = mock(BufferedWriter.class);
        Socket oldSocket = activeSocket();
        Socket newSocket = activeSocket();

        manager.addSession(DELIVERY_BUCKET_ID, "route-old", "worker-1", "endpoint-old", oldSocket, oldWriter);
        manager.addSession(DELIVERY_BUCKET_ID, "route-new", "worker-1", "endpoint-new", newSocket, newWriter);

        assertEquals(1, manager.getActiveConnectionCount());
        assertEquals("route-new", endpoint(endpointLeaseStore, "worker-1").endpointAddress());
        verify(oldSocket).close();

        assertTrue(manager.sendToSelectedWorker("socket", "worker-1", "{\"messageId\":\"msg-new\"}"));
        verify(oldWriter, never()).write(anyString());
        verify(newWriter).write("{\"messageId\":\"msg-new\"}");
    }

    @Test
    void setEndpointLeaseStoreReprojectsActiveSocketSessions() {
        InMemoryTransportEndpointLeaseStore firstStore =
                new InMemoryTransportEndpointLeaseStore(30_000L);
        InMemoryTransportEndpointLeaseStore secondStore =
                new InMemoryTransportEndpointLeaseStore(30_000L);
        SocketSessionManager manager = new SocketSessionManager("socket");
        manager.setEndpointLeaseStore(firstStore);

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", "endpoint-1",
                activeSocket(), mock(BufferedWriter.class));
        manager.setEndpointLeaseStore(secondStore);

        assertEquals("route-1", endpoint(secondStore, "worker-1").endpointAddress());
        assertEquals("endpoint-1", endpoint(secondStore, "worker-1").endpointLeaseId());
    }

    private static TransportEndpointLeaseViewRecord endpoint(InMemoryTransportEndpointLeaseStore store,
                                                             String workerId) {
        return store.currentEndpointLease(DELIVERY_BUCKET_ID, workerId).orElseThrow();
    }

    private static boolean hasEndpoint(InMemoryTransportEndpointLeaseStore store, String workerId) {
        return store.currentEndpointLease(DELIVERY_BUCKET_ID, workerId).isPresent();
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
