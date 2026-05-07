package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.channel.WorkerSystemEventChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ServerSessionManagerShutdownTest {

    private ServerSessionManager manager;

    @BeforeEach
    void setUp() {
        manager = new ServerSessionManager();
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

        manager.removeSession(firstChannel);
        assertEquals(1, manager.getWorkerConnectionCount());

        manager.removeSession(secondChannel);
        assertEquals(0, manager.getWorkerConnectionCount());
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

    private Channel mockActiveChannel(String idText) {
        Channel ch = mock(Channel.class);
        ChannelId chId = mock(ChannelId.class);
        when(chId.asShortText()).thenReturn(idText);
        when(ch.id()).thenReturn(chId);
        when(ch.isActive()).thenReturn(true);
        return ch;
    }
}
