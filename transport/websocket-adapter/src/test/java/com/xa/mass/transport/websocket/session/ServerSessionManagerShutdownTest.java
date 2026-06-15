package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerSessionPresenceEvent;
import com.xa.mass.transport.route.RouteConsumerEndpoint;
import com.xa.mass.transport.runtime.route.InMemoryTransportRouteOwnerStore;
import com.xa.mass.transport.websocket.dispatcher.WebSocketTaskDispatchChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class ServerSessionManagerShutdownTest {

    private static final String DELIVERY_BUCKET_ID = "bucket-1";

    private ServerSessionManager manager;

    @BeforeEach
    void setUp() {
        manager = new ServerSessionManager(WebSocketTaskDispatchChannel.DEFAULT_ADAPTER_ID);
    }

    @Test
    void shutdownClosesAllActiveChannelsAndClearsState() {
        Channel ch1 = mockActiveChannel("ch1");
        Channel ch2 = mockActiveChannel("ch2");
        ChannelHandlerContext ctx1 = mock(ChannelHandlerContext.class);
        ChannelHandlerContext ctx2 = mock(ChannelHandlerContext.class);

        manager.addSession(DELIVERY_BUCKET_ID, "worker-1", "worker-1", ch1, ctx1);
        manager.addSession(DELIVERY_BUCKET_ID, "worker-2", "worker-2", ch2, ctx2);

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

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", channel, ctx);

        assertTrue(manager.isAdapterRouteOnline("ws-public", "route-1"));
        assertFalse(manager.isAdapterRouteOnline("websocket", "route-1"));
        assertEquals("ws-public", manager.getAdapterId());
        assertEquals("ws-public", manager.listWorkerEndpoints().get(0).getAdapterId());
    }

    @Test
    void sessionsProjectRouteOwnerIntoTransportOwnedStore() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore(30_000L, "ws-node-1");
        RecordingWorkerPresenceIngress presenceIngress = new RecordingWorkerPresenceIngress();
        manager.setRouteOwnerStore(routeOwnerStore);
        manager.setWorkerPresenceIngress(presenceIngress);
        Channel channel = mockActiveChannel("worker-1");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", channel, ctx);

        assertTrue(endpoint(routeOwnerStore, "worker-1").isActive(System.currentTimeMillis()));
        assertEquals("route-1", endpoint(routeOwnerStore, "worker-1").routeKey());
        assertEquals("ws-node-1", endpoint(routeOwnerStore, "worker-1").transportNodeId());
        assertTrue(routeOwnerStore.hasActiveRouteOwner(manager.getAdapterId(), "route-1"));
        assertEquals(List.of("connected:worker-1:websocket:route-1:worker-1:websocket connected:worker-1"),
                presenceIngress.events);

        manager.removeSession(channel);

        assertFalse(hasEndpoint(routeOwnerStore, "worker-1"));
        assertFalse(routeOwnerStore.hasActiveRouteOwner(manager.getAdapterId(), "route-1"));
        assertEquals(List.of(
                "connected:worker-1:websocket:route-1:worker-1:websocket connected:worker-1",
                "disconnected:worker-1:websocket:route-1:worker-1:websocket disconnected:worker-1"
        ), presenceIngress.events);
    }

    @Test
    void disconnectingOneGroupRouteConsumerKeepsPeerRouteOwner() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore(30_000L, "ws-node-1");
        manager.setRouteOwnerStore(routeOwnerStore);
        Channel firstChannel = mockActiveChannel("worker-1");
        Channel secondChannel = mockActiveChannel("worker-2");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession(DELIVERY_BUCKET_ID, "group-route", "worker-1", firstChannel, ctx);
        manager.addSession(DELIVERY_BUCKET_ID, "group-route", "worker-2", secondChannel, ctx);

        assertEquals(2, routeOwnerStore.currentOwners("group-route").size());

        manager.removeSession(firstChannel);

        assertFalse(hasEndpoint(routeOwnerStore, "worker-1"));
        assertTrue(endpoint(routeOwnerStore, "worker-2").isActive(System.currentTimeMillis()));
        assertEquals(1, routeOwnerStore.currentOwners("group-route").size());
        assertTrue(routeOwnerStore.hasActiveRouteOwner(manager.getAdapterId(), "group-route"));
    }

    @Test
    void selectedWorkerSendUsesWorkerIndexUnderSharedRouteKey() {
        Channel firstChannel = mockActiveChannel("worker-1-channel");
        Channel secondChannel = mockActiveChannel("worker-2-channel");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession(DELIVERY_BUCKET_ID, "group-route", "worker-1", firstChannel, ctx);
        manager.addSession(DELIVERY_BUCKET_ID, "group-route", "worker-2", secondChannel, ctx);

        assertTrue(manager.sendToSelectedWorker(manager.getAdapterId(), "worker-2", "{\"messageId\":\"msg-2\"}"));

        verify(firstChannel, never()).writeAndFlush(any());
        verify(secondChannel).writeAndFlush(any());
    }

    @Test
    void replacingWorkerChannelKeepsConnectionCountStable() {
        RecordingWorkerPresenceIngress presenceIngress = new RecordingWorkerPresenceIngress();
        manager.setWorkerPresenceIngress(presenceIngress);
        Channel firstChannel = mockActiveChannel("worker-1-old");
        Channel secondChannel = mockActiveChannel("worker-1-new");
        ChannelHandlerContext firstCtx = mock(ChannelHandlerContext.class);
        ChannelHandlerContext secondCtx = mock(ChannelHandlerContext.class);

        manager.addSession(DELIVERY_BUCKET_ID, "worker-1", "worker-1", firstChannel, firstCtx);
        assertEquals(1, manager.getWorkerConnectionCount());

        manager.addSession(DELIVERY_BUCKET_ID, "worker-1", "worker-1", secondChannel, secondCtx);

        assertEquals(1, manager.getWorkerConnectionCount());
        assertEquals(secondChannel, manager.getChannel("worker-1"));
        verify(firstChannel).close();
        assertEquals(List.of(
                "connected:worker-1:websocket:worker-1:worker-1-old:websocket connected:worker-1-old",
                "connected:worker-1:websocket:worker-1:worker-1-new:websocket connected:worker-1-new",
                "disconnected:worker-1:websocket:worker-1:worker-1-old:websocket session replaced:worker-1-old"
        ), presenceIngress.events);

        manager.removeSession(firstChannel);
        assertEquals(1, manager.getWorkerConnectionCount());
        assertEquals(List.of(
                "connected:worker-1:websocket:worker-1:worker-1-old:websocket connected:worker-1-old",
                "connected:worker-1:websocket:worker-1:worker-1-new:websocket connected:worker-1-new",
                "disconnected:worker-1:websocket:worker-1:worker-1-old:websocket session replaced:worker-1-old"
        ), presenceIngress.events);

        manager.removeSession(secondChannel);
        assertEquals(0, manager.getWorkerConnectionCount());
        assertEquals(List.of(
                "connected:worker-1:websocket:worker-1:worker-1-old:websocket connected:worker-1-old",
                "connected:worker-1:websocket:worker-1:worker-1-new:websocket connected:worker-1-new",
                "disconnected:worker-1:websocket:worker-1:worker-1-old:websocket session replaced:worker-1-old",
                "disconnected:worker-1:websocket:worker-1:worker-1-new:websocket disconnected:worker-1-new"
        ), presenceIngress.events);
    }

    @Test
    void replacingSelectedWorkerWithDifferentRouteRetiresOldEndpoint() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore();
        manager.setRouteOwnerStore(routeOwnerStore);
        Channel firstChannel = mockActiveChannel("worker-1-old");
        Channel secondChannel = mockActiveChannel("worker-1-new");
        ChannelHandlerContext firstCtx = mock(ChannelHandlerContext.class);
        ChannelHandlerContext secondCtx = mock(ChannelHandlerContext.class);

        manager.addSession(DELIVERY_BUCKET_ID, "route-old", "worker-1", firstChannel, firstCtx);
        manager.addSession(DELIVERY_BUCKET_ID, "route-new", "worker-1", secondChannel, secondCtx);

        assertEquals(1, manager.getWorkerConnectionCount());
        assertNull(manager.getChannel("route-old"));
        assertEquals(secondChannel, manager.getChannel("route-new"));
        assertFalse(routeOwnerStore.hasActiveRouteOwner(manager.getAdapterId(), "route-old"));
        assertTrue(routeOwnerStore.hasActiveRouteOwner(manager.getAdapterId(), "route-new"));
        assertEquals("route-new", endpoint(routeOwnerStore, "worker-1").routeKey());
        verify(firstChannel).close();

        assertTrue(manager.sendToSelectedWorker(manager.getAdapterId(), "worker-1", "{\"messageId\":\"msg-new\"}"));
        verify(firstChannel, never()).writeAndFlush(any());
        verify(secondChannel).writeAndFlush(any());
    }

    @Test
    void retiredWebSocketChannelCannotReclaimRouteOwner() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore();
        manager.setRouteOwnerStore(routeOwnerStore);
        Channel firstChannel = mockActiveChannel("worker-1-old");
        Channel secondChannel = mockActiveChannel("worker-1-new");
        ChannelHandlerContext firstCtx = mock(ChannelHandlerContext.class);
        ChannelHandlerContext secondCtx = mock(ChannelHandlerContext.class);

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", firstChannel, firstCtx);
        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", secondChannel, secondCtx);

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", firstChannel, firstCtx);

        assertEquals(secondChannel, manager.getChannel("route-1"));
        assertEquals("worker-1-new", endpoint(routeOwnerStore, "worker-1").connectionId());
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

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", firstChannel, firstCtx);
        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", secondChannel, secondCtx);

        manager.removeSession(firstChannel);

        assertTrue(endpoint(routeOwnerStore, "worker-1").isActive(System.currentTimeMillis()));
        assertEquals("worker-1-new", endpoint(routeOwnerStore, "worker-1").connectionId());
        assertTrue(routeOwnerStore.hasActiveRouteOwner(manager.getAdapterId(), "route-1"));

        manager.removeSession(secondChannel);

        assertFalse(hasEndpoint(routeOwnerStore, "worker-1"));
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
        manager.addSession(DELIVERY_BUCKET_ID, "worker-a", "worker-a", active, ctx);
        manager.addSession(DELIVERY_BUCKET_ID, "worker-b", "worker-b", inactive, ctx);

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

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", channel, ctx);

        manager.shutdown();

        assertFalse(hasEndpoint(routeOwnerStore, "worker-1"));
        assertFalse(routeOwnerStore.hasActiveRouteOwner(manager.getAdapterId(), "route-1"));
    }

    @Test
    void activeWebSocketSessionRefreshesRouteOwnerLease() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore(1_200L);
        manager.setRouteOwnerStore(routeOwnerStore);
        Channel channel = mockActiveChannel("worker-1");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", channel, ctx);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Thread.sleep(2_200L);
            assertTrue(endpoint(routeOwnerStore, "worker-1").isActive(System.currentTimeMillis()));
        });
    }

    @Test
    void setRouteOwnerStoreReprojectsActiveWebSocketSessions() {
        InMemoryTransportRouteOwnerStore firstStore = new InMemoryTransportRouteOwnerStore(30_000L, "ws-node-1");
        InMemoryTransportRouteOwnerStore secondStore = new InMemoryTransportRouteOwnerStore(30_000L, "ws-node-2");
        manager.setRouteOwnerStore(firstStore);
        Channel channel = mockActiveChannel("worker-1");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", channel, ctx);
        manager.setRouteOwnerStore(secondStore);

        assertTrue(endpoint(secondStore, "worker-1").isActive(System.currentTimeMillis()));
        assertEquals("route-1", endpoint(secondStore, "worker-1").routeKey());
        assertEquals("worker-1", endpoint(secondStore, "worker-1").connectionId());
        assertEquals("ws-node-2", endpoint(secondStore, "worker-1").transportNodeId());
        assertTrue(secondStore.hasActiveRouteOwner(manager.getAdapterId(), "route-1"));
    }

    private static RouteConsumerEndpoint endpoint(InMemoryTransportRouteOwnerStore store, String workerId) {
        return store.endpointForSelectedWorker(DELIVERY_BUCKET_ID, workerId).orElseThrow();
    }

    private static boolean hasEndpoint(InMemoryTransportRouteOwnerStore store, String workerId) {
        return store.endpointForSelectedWorker(DELIVERY_BUCKET_ID, workerId).isPresent();
    }

    private Channel mockActiveChannel(String idText) {
        Channel ch = mock(Channel.class);
        ChannelId chId = mock(ChannelId.class);
        when(chId.asShortText()).thenReturn(idText);
        when(ch.id()).thenReturn(chId);
        when(ch.isActive()).thenReturn(true);
        return ch;
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
