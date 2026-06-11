package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import com.xa.mass.transport.presence.WorkerPresenceState;
import com.xa.mass.transport.runtime.presence.InMemoryWorkerPresenceStore;
import com.xa.mass.transport.websocket.worker.WebSocketRealtimeWorkerAdapter;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ServerSessionManagerShutdownTest {

    private ServerSessionManager manager;

    @BeforeEach
    void setUp() {
        manager = new ServerSessionManager(WebSocketRealtimeWorkerAdapter.DEFAULT_ADAPTER_ID);
    }

    @Test
    void shutdownClosesAllActiveChannelsAndClearsState() {
        Channel ch1 = mockActiveChannel("ch1");
        Channel ch2 = mockActiveChannel("ch2");
        ChannelHandlerContext ctx1 = mock(ChannelHandlerContext.class);
        ChannelHandlerContext ctx2 = mock(ChannelHandlerContext.class);

        manager.addSession("worker-1", "worker-1", ch1, ctx1);
        manager.addSession("worker-2", "worker-2", ch2, ctx2);

        assertEquals(2, manager.getWorkerConnectionCount());

        manager.shutdown();

        verify(ch1).close();
        verify(ch2).close();
        assertEquals(0, manager.getWorkerConnectionCount());
        assertFalse(manager.isAdapterRouteOnline(manager.getAdapterId(), "worker-1"));
        assertFalse(manager.isAdapterRouteOnline(manager.getAdapterId(), "worker-2"));
    }

    @Test
    void shutdownOnEmptyManagerIsIdempotent() {
        assertDoesNotThrow(() -> manager.shutdown());
    }

    @Test
    void adapterScopedLookupsUseConfiguredAdapterId() {
        manager = new ServerSessionManager("ws-public");
        Channel channel = mockActiveChannel("worker-1");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession("route-1", "worker-1", channel, ctx);

        assertTrue(manager.isAdapterRouteOnline("ws-public", "route-1"));
        assertFalse(manager.isAdapterRouteOnline("websocket", "route-1"));
        assertEquals("ws-public", manager.getAdapterId());
        assertEquals("ws-public", manager.listWorkerEndpoints().get(0).getAdapterId());
    }

    @Test
    void workerOnlineOfflineSignalsTrackWorkerLevelReachability() {
        WorkerSystemEventChannel systemEventChannel = mock(WorkerSystemEventChannel.class);
        manager.setSystemEventChannel(systemEventChannel);
        Channel firstChannel = mockActiveChannel("worker-1-old");
        Channel secondChannel = mockActiveChannel("worker-1-new");
        ChannelHandlerContext firstCtx = mock(ChannelHandlerContext.class);
        ChannelHandlerContext secondCtx = mock(ChannelHandlerContext.class);

        manager.addSession("worker-1", "worker-1", firstChannel, firstCtx);
        manager.addSession("worker-1", "worker-1", secondChannel, secondCtx);

        verify(systemEventChannel, times(1)).publishWorkerOnline("worker-1", "websocket connected", null);
        assertTrue(manager.isAdapterRouteOnline(manager.getAdapterId(), "worker-1"));
        assertEquals(secondChannel, manager.getChannel("worker-1"));

        manager.removeSession(firstChannel);

        verify(systemEventChannel, never()).publishWorkerOffline("worker-1", "websocket disconnected", null);
        assertTrue(manager.isAdapterRouteOnline(manager.getAdapterId(), "worker-1"));

        manager.removeSession(secondChannel);

        verify(systemEventChannel, times(1)).publishWorkerOffline("worker-1", "websocket disconnected", null);
        assertFalse(manager.isAdapterRouteOnline(manager.getAdapterId(), "worker-1"));
    }

    @Test
    void sessionsProjectPresenceIntoTransportOwnedStore() {
        InMemoryWorkerPresenceStore presenceStore = new InMemoryWorkerPresenceStore(30_000L, "ws-node-1");
        manager.setWorkerPresenceStore(presenceStore);
        Channel channel = mockActiveChannel("worker-1");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession("route-1", "worker-1", channel, ctx);

        assertEquals(WorkerPresenceState.ONLINE, presenceStore.getPresence("worker-1").getPresenceState());
        assertEquals("route-1", presenceStore.getPresence("worker-1").getRouteKey());
        assertEquals("ws-node-1", presenceStore.findOwners("worker-1").getFirst().transportNodeId());
        assertTrue(presenceStore.isRouteOnline(manager.getAdapterId(), "route-1"));

        manager.removeSession(channel);

        assertEquals(WorkerPresenceState.OFFLINE, presenceStore.getPresence("worker-1").getPresenceState());
        assertFalse(presenceStore.isRouteOnline(manager.getAdapterId(), "route-1"));
    }

    @Test
    void replacingWorkerChannelKeepsConnectionCountStable() {
        Channel firstChannel = mockActiveChannel("worker-1-old");
        Channel secondChannel = mockActiveChannel("worker-1-new");
        ChannelHandlerContext firstCtx = mock(ChannelHandlerContext.class);
        ChannelHandlerContext secondCtx = mock(ChannelHandlerContext.class);

        manager.addSession("worker-1", "worker-1", firstChannel, firstCtx);
        assertEquals(1, manager.getWorkerConnectionCount());

        manager.addSession("worker-1", "worker-1", secondChannel, secondCtx);

        assertEquals(1, manager.getWorkerConnectionCount());
        assertEquals(secondChannel, manager.getChannel("worker-1"));
        verify(firstChannel).close();

        manager.removeSession(firstChannel);
        assertEquals(1, manager.getWorkerConnectionCount());

        manager.removeSession(secondChannel);
        assertEquals(0, manager.getWorkerConnectionCount());
    }

    @Test
    void retiredWebSocketChannelCannotReclaimRouteOwner() {
        InMemoryWorkerPresenceStore presenceStore = new InMemoryWorkerPresenceStore();
        manager.setWorkerPresenceStore(presenceStore);
        Channel firstChannel = mockActiveChannel("worker-1-old");
        Channel secondChannel = mockActiveChannel("worker-1-new");
        ChannelHandlerContext firstCtx = mock(ChannelHandlerContext.class);
        ChannelHandlerContext secondCtx = mock(ChannelHandlerContext.class);

        manager.addSession("route-1", "worker-1", firstChannel, firstCtx);
        manager.addSession("route-1", "worker-1", secondChannel, secondCtx);

        manager.addSession("route-1", "worker-1", firstChannel, firstCtx);

        assertEquals(secondChannel, manager.getChannel("route-1"));
        assertEquals("worker-1-new", presenceStore.getPresence("worker-1").getConnectionId());
        verify(firstChannel).close();
    }

    @Test
    void removingStaleChannelDoesNotOfflineReplacementPresence() {
        InMemoryWorkerPresenceStore presenceStore = new InMemoryWorkerPresenceStore();
        manager.setWorkerPresenceStore(presenceStore);
        Channel firstChannel = mockActiveChannel("worker-1-old");
        Channel secondChannel = mockActiveChannel("worker-1-new");
        ChannelHandlerContext firstCtx = mock(ChannelHandlerContext.class);
        ChannelHandlerContext secondCtx = mock(ChannelHandlerContext.class);

        manager.addSession("route-1", "worker-1", firstChannel, firstCtx);
        manager.addSession("route-1", "worker-1", secondChannel, secondCtx);

        manager.removeSession(firstChannel);

        assertEquals(WorkerPresenceState.ONLINE, presenceStore.getPresence("worker-1").getPresenceState());
        assertEquals("worker-1-new", presenceStore.getPresence("worker-1").getConnectionId());
        assertTrue(presenceStore.isRouteOnline(manager.getAdapterId(), "route-1"));

        manager.removeSession(secondChannel);

        assertEquals(WorkerPresenceState.OFFLINE, presenceStore.getPresence("worker-1").getPresenceState());
        assertFalse(presenceStore.isRouteOnline(manager.getAdapterId(), "route-1"));
    }

    @Test
    void shutdownSkipsInactiveChannels() {
        Channel active = mockActiveChannel("active");
        Channel inactive = mock(Channel.class);
        ChannelId inactiveId = mock(ChannelId.class);
        when(inactive.isActive()).thenReturn(false);
        when(inactive.id()).thenReturn(inactiveId);
        when(inactiveId.asShortText()).thenReturn("inactive");

        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        manager.addSession("worker-a", "worker-a", active, ctx);
        manager.addSession("worker-b", "worker-b", inactive, ctx);

        manager.shutdown();

        verify(active).close();
        verify(inactive, never()).close();
    }

    @Test
    void shutdownMarksPresenceOfflineBeforeClearingRoutes() {
        InMemoryWorkerPresenceStore presenceStore = new InMemoryWorkerPresenceStore();
        manager.setWorkerPresenceStore(presenceStore);
        Channel channel = mockActiveChannel("worker-1");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession("route-1", "worker-1", channel, ctx);

        manager.shutdown();

        assertEquals(WorkerPresenceState.OFFLINE, presenceStore.getPresence("worker-1").getPresenceState());
        assertFalse(presenceStore.isRouteOnline(manager.getAdapterId(), "route-1"));
    }

    @Test
    void activeWebSocketSessionRefreshesPresenceLease() {
        InMemoryWorkerPresenceStore presenceStore = new InMemoryWorkerPresenceStore(1_200L);
        manager.setWorkerPresenceStore(presenceStore);
        Channel channel = mockActiveChannel("worker-1");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession("route-1", "worker-1", channel, ctx);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Thread.sleep(2_200L);
            assertEquals(WorkerPresenceState.ONLINE, presenceStore.getPresence("worker-1").getPresenceState());
        });
    }

    @Test
    void setWorkerPresenceStoreReprojectsActiveWebSocketSessions() {
        InMemoryWorkerPresenceStore firstStore = new InMemoryWorkerPresenceStore(30_000L, "ws-node-1");
        InMemoryWorkerPresenceStore secondStore = new InMemoryWorkerPresenceStore(30_000L, "ws-node-2");
        manager.setWorkerPresenceStore(firstStore);
        Channel channel = mockActiveChannel("worker-1");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession("route-1", "worker-1", channel, ctx);
        manager.setWorkerPresenceStore(secondStore);

        assertEquals(WorkerPresenceState.ONLINE, secondStore.getPresence("worker-1").getPresenceState());
        assertEquals("route-1", secondStore.getPresence("worker-1").getRouteKey());
        assertEquals("worker-1", secondStore.getPresence("worker-1").getConnectionId());
        assertEquals("ws-node-2", secondStore.findOwners("worker-1").getFirst().transportNodeId());
        assertTrue(secondStore.isRouteOnline(manager.getAdapterId(), "route-1"));
    }

    private Channel mockActiveChannel(String idText) {
        Channel ch = mock(Channel.class);
        ChannelId chId = mock(ChannelId.class);
        when(chId.asShortText()).thenReturn(idText);
        when(ch.id()).thenReturn(chId);
        when(ch.isActive()).thenReturn(true);
        return ch;
    }
}
