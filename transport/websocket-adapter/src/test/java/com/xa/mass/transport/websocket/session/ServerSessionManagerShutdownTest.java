package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.runtime.route.InMemoryTransportRouteOwnerStore;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void sessionsProjectRouteOwnerIntoTransportOwnedStore() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore(30_000L, "ws-node-1");
        manager.setRouteOwnerStore(routeOwnerStore);
        Channel channel = mockActiveChannel("worker-1");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession("route-1", "worker-1", channel, ctx);

        assertTrue(routeOwnerStore.getLatestOwnerByWorker("worker-1").isLeaseActive(System.currentTimeMillis()));
        assertEquals("route-1", routeOwnerStore.getLatestOwnerByWorker("worker-1").getRouteKey());
        assertEquals("ws-node-1", routeOwnerStore.findRouteOwners("worker-1").getFirst().transportNodeId());
        assertTrue(routeOwnerStore.hasActiveRouteOwner(manager.getAdapterId(), "route-1"));

        manager.removeSession(channel);

        assertNull(routeOwnerStore.getLatestOwnerByWorker("worker-1"));
        assertFalse(routeOwnerStore.hasActiveRouteOwner(manager.getAdapterId(), "route-1"));
    }

    @Test
    void disconnectingOneGroupRouteConsumerKeepsPeerRouteOwner() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore(30_000L, "ws-node-1");
        manager.setRouteOwnerStore(routeOwnerStore);
        Channel firstChannel = mockActiveChannel("worker-1");
        Channel secondChannel = mockActiveChannel("worker-2");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession("group-route", "worker-1", firstChannel, ctx);
        manager.addSession("group-route", "worker-2", secondChannel, ctx);

        assertEquals(2, routeOwnerStore.currentOwners("group-route").size());

        manager.removeSession(firstChannel);

        assertNull(routeOwnerStore.getLatestOwnerByWorker("worker-1"));
        assertTrue(routeOwnerStore.getLatestOwnerByWorker("worker-2").isLeaseActive(System.currentTimeMillis()));
        assertEquals(1, routeOwnerStore.currentOwners("group-route").size());
        assertTrue(routeOwnerStore.hasActiveRouteOwner(manager.getAdapterId(), "group-route"));
    }

    @Test
    void selectedWorkerSendUsesWorkerIndexUnderSharedRouteKey() {
        Channel firstChannel = mockActiveChannel("worker-1-channel");
        Channel secondChannel = mockActiveChannel("worker-2-channel");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession("group-route", "worker-1", firstChannel, ctx);
        manager.addSession("group-route", "worker-2", secondChannel, ctx);

        assertTrue(manager.sendToSelectedWorker(manager.getAdapterId(), "worker-2", "{\"messageId\":\"msg-2\"}"));

        verify(firstChannel, never()).writeAndFlush(any());
        verify(secondChannel).writeAndFlush(any());
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
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore();
        manager.setRouteOwnerStore(routeOwnerStore);
        Channel firstChannel = mockActiveChannel("worker-1-old");
        Channel secondChannel = mockActiveChannel("worker-1-new");
        ChannelHandlerContext firstCtx = mock(ChannelHandlerContext.class);
        ChannelHandlerContext secondCtx = mock(ChannelHandlerContext.class);

        manager.addSession("route-1", "worker-1", firstChannel, firstCtx);
        manager.addSession("route-1", "worker-1", secondChannel, secondCtx);

        manager.addSession("route-1", "worker-1", firstChannel, firstCtx);

        assertEquals(secondChannel, manager.getChannel("route-1"));
        assertEquals("worker-1-new", routeOwnerStore.getLatestOwnerByWorker("worker-1").getConnectionId());
        verify(firstChannel).close();
    }

    @Test
    void removingStaleChannelDoesNotReleaseReplacementRouteOwner() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore();
        manager.setRouteOwnerStore(routeOwnerStore);
        Channel firstChannel = mockActiveChannel("worker-1-old");
        Channel secondChannel = mockActiveChannel("worker-1-new");
        ChannelHandlerContext firstCtx = mock(ChannelHandlerContext.class);
        ChannelHandlerContext secondCtx = mock(ChannelHandlerContext.class);

        manager.addSession("route-1", "worker-1", firstChannel, firstCtx);
        manager.addSession("route-1", "worker-1", secondChannel, secondCtx);

        manager.removeSession(firstChannel);

        assertTrue(routeOwnerStore.getLatestOwnerByWorker("worker-1").isLeaseActive(System.currentTimeMillis()));
        assertEquals("worker-1-new", routeOwnerStore.getLatestOwnerByWorker("worker-1").getConnectionId());
        assertTrue(routeOwnerStore.hasActiveRouteOwner(manager.getAdapterId(), "route-1"));

        manager.removeSession(secondChannel);

        assertNull(routeOwnerStore.getLatestOwnerByWorker("worker-1"));
        assertFalse(routeOwnerStore.hasActiveRouteOwner(manager.getAdapterId(), "route-1"));
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
    void shutdownReleasesRouteOwnerBeforeClearingRoutes() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore();
        manager.setRouteOwnerStore(routeOwnerStore);
        Channel channel = mockActiveChannel("worker-1");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession("route-1", "worker-1", channel, ctx);

        manager.shutdown();

        assertNull(routeOwnerStore.getLatestOwnerByWorker("worker-1"));
        assertFalse(routeOwnerStore.hasActiveRouteOwner(manager.getAdapterId(), "route-1"));
    }

    @Test
    void activeWebSocketSessionRefreshesRouteOwnerLease() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore(1_200L);
        manager.setRouteOwnerStore(routeOwnerStore);
        Channel channel = mockActiveChannel("worker-1");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession("route-1", "worker-1", channel, ctx);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Thread.sleep(2_200L);
            assertTrue(routeOwnerStore.getLatestOwnerByWorker("worker-1").isLeaseActive(System.currentTimeMillis()));
        });
    }

    @Test
    void setRouteOwnerStoreReprojectsActiveWebSocketSessions() {
        InMemoryTransportRouteOwnerStore firstStore = new InMemoryTransportRouteOwnerStore(30_000L, "ws-node-1");
        InMemoryTransportRouteOwnerStore secondStore = new InMemoryTransportRouteOwnerStore(30_000L, "ws-node-2");
        manager.setRouteOwnerStore(firstStore);
        Channel channel = mockActiveChannel("worker-1");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession("route-1", "worker-1", channel, ctx);
        manager.setRouteOwnerStore(secondStore);

        assertTrue(secondStore.getLatestOwnerByWorker("worker-1").isLeaseActive(System.currentTimeMillis()));
        assertEquals("route-1", secondStore.getLatestOwnerByWorker("worker-1").getRouteKey());
        assertEquals("worker-1", secondStore.getLatestOwnerByWorker("worker-1").getConnectionId());
        assertEquals("ws-node-2", secondStore.findRouteOwners("worker-1").getFirst().transportNodeId());
        assertTrue(secondStore.hasActiveRouteOwner(manager.getAdapterId(), "route-1"));
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
