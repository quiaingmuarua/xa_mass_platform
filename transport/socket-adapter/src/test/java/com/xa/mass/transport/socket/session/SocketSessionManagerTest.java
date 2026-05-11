package com.xa.mass.transport.socket.session;

import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.presence.WorkerPresenceState;
import com.xa.mass.transport.runtime.presence.InMemoryWorkerPresenceStore;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SocketSessionManagerTest {

    @Test
    void connectHeartbeatDisconnectProjectPresenceIntoTransportStore() {
        WorkerSystemEventChannel systemEventChannel = mock(WorkerSystemEventChannel.class);
        InMemoryWorkerPresenceStore presenceStore = new InMemoryWorkerPresenceStore();
        SocketSessionManager manager = new SocketSessionManager("socket", systemEventChannel);
        manager.setWorkerPresenceStore(presenceStore);

        manager.addSession("route-1", "worker-1", "endpoint-1", activeSocket(), mock(BufferedWriter.class));

        assertEquals(WorkerPresenceState.ONLINE, presenceStore.getPresence("worker-1").getPresenceState());
        assertTrue(presenceStore.isRouteOnline("socket", "route-1"));
        verify(systemEventChannel).publishWorkerOnline("worker-1", "socket connected", null);

        manager.recordHeartbeat("route-1", "worker-1", "endpoint-1", "socket heartbeat", "trace-1");

        assertEquals(WorkerPresenceState.ONLINE, presenceStore.getPresence("worker-1").getPresenceState());
        verify(systemEventChannel).publishWorkerHeartbeat("worker-1", "socket heartbeat", "trace-1");

        manager.removeSession("endpoint-1");

        assertEquals(WorkerPresenceState.OFFLINE, presenceStore.getPresence("worker-1").getPresenceState());
        assertFalse(presenceStore.isRouteOnline("socket", "route-1"));
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

    private Socket activeSocket() {
        Socket socket = mock(Socket.class);
        when(socket.isConnected()).thenReturn(true);
        when(socket.isClosed()).thenReturn(false);
        return socket;
    }
}
