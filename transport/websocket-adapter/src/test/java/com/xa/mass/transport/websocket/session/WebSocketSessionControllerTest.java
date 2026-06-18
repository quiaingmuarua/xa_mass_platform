package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerSessionPresenceEvent;
import com.xa.mass.transport.lease.TransportEndpointLeaseViewRecord;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;
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

class WebSocketSessionControllerTest {

    private static final String DELIVERY_BUCKET_ID = "bucket-1";

    private WebSocketSessionStore sessionStore;
    private WebSocketSessionEvidenceDriver evidenceDriver;
    private WebSocketSessionController manager;

    @BeforeEach
    void setUp() {
        manager = newController(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID);
    }

    @Test
    void shutdownClosesAllActiveChannelsAndClearsState() {
        Channel ch1 = mockActiveChannel("ch1");
        Channel ch2 = mockActiveChannel("ch2");
        ChannelHandlerContext ctx1 = mock(ChannelHandlerContext.class);
        ChannelHandlerContext ctx2 = mock(ChannelHandlerContext.class);

        manager.addSession(DELIVERY_BUCKET_ID, "worker-1", "worker-1", ch1, ctx1);
        manager.addSession(DELIVERY_BUCKET_ID, "worker-2", "worker-2", ch2, ctx2);

        assertEquals(2, sessionStore.activeConnectionCount());

        manager.shutdown();

        verify(ch1).close();
        verify(ch2).close();
        assertEquals(0, sessionStore.activeConnectionCount());
        assertFalse(sessionStore.hasActiveEndpointAddress("worker-1"));
        assertFalse(sessionStore.hasActiveEndpointAddress("worker-2"));
    }

    @Test
    void shutdownOnEmptyManagerIsIdempotent() {
        assertDoesNotThrow(() -> manager.shutdown());
    }

    @Test
    void adapterScopedLookupsUseConfiguredAdapterId() {
        manager = newController("ws-public");
        Channel channel = mockActiveChannel("worker-1");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", channel, ctx);

        assertTrue(sessionStore.hasActiveEndpointAddress("route-1"));
        assertEquals("ws-public", new WebSocketEndpointInspector(sessionStore).listWorkerEndpoints().get(0).getAdapterId());
    }

    @Test
    void sessionsProjectEndpointLeaseIntoTransportOwnedStore() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore =
                new InMemoryTransportEndpointLeaseStore(30_000L);
        RecordingWorkerPresenceIngress presenceIngress = new RecordingWorkerPresenceIngress();
        evidenceDriver.setEndpointLeaseStore(endpointLeaseStore);
        evidenceDriver.setWorkerPresenceIngress(presenceIngress);
        Channel channel = mockActiveChannel("worker-1");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", channel, ctx);

        assertTrue(hasEndpoint(endpointLeaseStore, "worker-1"));
        assertEquals("route-1", endpoint(endpointLeaseStore, "worker-1").endpointAddress());
        assertEquals(List.of("connected:worker-1:websocket:route-1:worker-1:websocket connected:worker-1"),
                presenceIngress.events);

        manager.removeSession(channel);

        assertFalse(hasEndpoint(endpointLeaseStore, "worker-1"));
        assertEquals(List.of(
                "connected:worker-1:websocket:route-1:worker-1:websocket connected:worker-1",
                "disconnected:worker-1:websocket:route-1:worker-1:websocket disconnected:worker-1"
        ), presenceIngress.events);
    }

    @Test
    void disconnectingOneGroupRouteConsumerKeepsPeerEndpointLease() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore =
                new InMemoryTransportEndpointLeaseStore(30_000L);
        evidenceDriver.setEndpointLeaseStore(endpointLeaseStore);
        Channel firstChannel = mockActiveChannel("worker-1");
        Channel secondChannel = mockActiveChannel("worker-2");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession(DELIVERY_BUCKET_ID, "group-route", "worker-1", firstChannel, ctx);
        manager.addSession(DELIVERY_BUCKET_ID, "group-route", "worker-2", secondChannel, ctx);

        assertTrue(hasEndpoint(endpointLeaseStore, "worker-1"));
        assertTrue(hasEndpoint(endpointLeaseStore, "worker-2"));

        manager.removeSession(firstChannel);

        assertFalse(hasEndpoint(endpointLeaseStore, "worker-1"));
        assertTrue(hasEndpoint(endpointLeaseStore, "worker-2"));
        assertTrue(sessionStore.hasActiveEndpointAddress("group-route"));
    }

    @Test
    void selectedWorkerSendUsesWorkerIndexUnderSharedRouteKey() {
        Channel firstChannel = mockActiveChannel("worker-1-channel");
        Channel secondChannel = mockActiveChannel("worker-2-channel");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession(DELIVERY_BUCKET_ID, "group-route", "worker-1", firstChannel, ctx);
        manager.addSession(DELIVERY_BUCKET_ID, "group-route", "worker-2", secondChannel, ctx);

        assertTrue(new WebSocketSelectedWorkerSender(sessionStore)
                .sendToSelectedWorker("worker-2", "{\"messageId\":\"msg-2\"}"));

        verify(firstChannel, never()).writeAndFlush(any());
        verify(secondChannel).writeAndFlush(any());
    }

    @Test
    void replacingWorkerChannelKeepsConnectionCountStable() {
        RecordingWorkerPresenceIngress presenceIngress = new RecordingWorkerPresenceIngress();
        evidenceDriver.setWorkerPresenceIngress(presenceIngress);
        Channel firstChannel = mockActiveChannel("worker-1-old");
        Channel secondChannel = mockActiveChannel("worker-1-new");
        ChannelHandlerContext firstCtx = mock(ChannelHandlerContext.class);
        ChannelHandlerContext secondCtx = mock(ChannelHandlerContext.class);

        manager.addSession(DELIVERY_BUCKET_ID, "worker-1", "worker-1", firstChannel, firstCtx);
        assertEquals(1, sessionStore.activeConnectionCount());

        manager.addSession(DELIVERY_BUCKET_ID, "worker-1", "worker-1", secondChannel, secondCtx);

        assertEquals(1, sessionStore.activeConnectionCount());
        assertEquals(secondChannel, sessionStore.activeRecordForEndpointAddress("worker-1").channel());
        verify(firstChannel).close();
        assertEquals(List.of(
                "connected:worker-1:websocket:worker-1:worker-1-old:websocket connected:worker-1-old",
                "connected:worker-1:websocket:worker-1:worker-1-new:websocket connected:worker-1-new",
                "disconnected:worker-1:websocket:worker-1:worker-1-old:websocket session replaced:worker-1-old"
        ), presenceIngress.events);

        manager.removeSession(firstChannel);
        assertEquals(1, sessionStore.activeConnectionCount());
        assertEquals(List.of(
                "connected:worker-1:websocket:worker-1:worker-1-old:websocket connected:worker-1-old",
                "connected:worker-1:websocket:worker-1:worker-1-new:websocket connected:worker-1-new",
                "disconnected:worker-1:websocket:worker-1:worker-1-old:websocket session replaced:worker-1-old"
        ), presenceIngress.events);

        manager.removeSession(secondChannel);
        assertEquals(0, sessionStore.activeConnectionCount());
        assertEquals(List.of(
                "connected:worker-1:websocket:worker-1:worker-1-old:websocket connected:worker-1-old",
                "connected:worker-1:websocket:worker-1:worker-1-new:websocket connected:worker-1-new",
                "disconnected:worker-1:websocket:worker-1:worker-1-old:websocket session replaced:worker-1-old",
                "disconnected:worker-1:websocket:worker-1:worker-1-new:websocket disconnected:worker-1-new"
        ), presenceIngress.events);
    }

    @Test
    void replacingSelectedWorkerWithDifferentRouteRetiresOldEndpoint() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore();
        evidenceDriver.setEndpointLeaseStore(endpointLeaseStore);
        Channel firstChannel = mockActiveChannel("worker-1-old");
        Channel secondChannel = mockActiveChannel("worker-1-new");
        ChannelHandlerContext firstCtx = mock(ChannelHandlerContext.class);
        ChannelHandlerContext secondCtx = mock(ChannelHandlerContext.class);

        manager.addSession(DELIVERY_BUCKET_ID, "route-old", "worker-1", firstChannel, firstCtx);
        manager.addSession(DELIVERY_BUCKET_ID, "route-new", "worker-1", secondChannel, secondCtx);

        assertEquals(1, sessionStore.activeConnectionCount());
        assertNull(sessionStore.activeRecordForEndpointAddress("route-old"));
        assertEquals(secondChannel, sessionStore.activeRecordForEndpointAddress("route-new").channel());
        assertEquals("route-new", endpoint(endpointLeaseStore, "worker-1").endpointAddress());
        verify(firstChannel).close();

        assertTrue(new WebSocketSelectedWorkerSender(sessionStore)
                .sendToSelectedWorker("worker-1", "{\"messageId\":\"msg-new\"}"));
        verify(firstChannel, never()).writeAndFlush(any());
        verify(secondChannel).writeAndFlush(any());
    }

    @Test
    void retiredWebSocketChannelCannotReclaimEndpointLease() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore();
        evidenceDriver.setEndpointLeaseStore(endpointLeaseStore);
        Channel firstChannel = mockActiveChannel("worker-1-old");
        Channel secondChannel = mockActiveChannel("worker-1-new");
        ChannelHandlerContext firstCtx = mock(ChannelHandlerContext.class);
        ChannelHandlerContext secondCtx = mock(ChannelHandlerContext.class);

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", firstChannel, firstCtx);
        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", secondChannel, secondCtx);

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", firstChannel, firstCtx);

        assertEquals(secondChannel, sessionStore.activeRecordForEndpointAddress("route-1").channel());
        assertEquals("worker-1-new", endpoint(endpointLeaseStore, "worker-1").endpointLeaseId());
        verify(firstChannel).close();
    }

    @Test
    void removingStaleChannelDoesNotReleaseReplacementEndpointLease() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore();
        evidenceDriver.setEndpointLeaseStore(endpointLeaseStore);
        Channel firstChannel = mockActiveChannel("worker-1-old");
        Channel secondChannel = mockActiveChannel("worker-1-new");
        ChannelHandlerContext firstCtx = mock(ChannelHandlerContext.class);
        ChannelHandlerContext secondCtx = mock(ChannelHandlerContext.class);

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", firstChannel, firstCtx);
        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", secondChannel, secondCtx);

        manager.removeSession(firstChannel);

        assertTrue(hasEndpoint(endpointLeaseStore, "worker-1"));
        assertEquals("worker-1-new", endpoint(endpointLeaseStore, "worker-1").endpointLeaseId());

        manager.removeSession(secondChannel);

        assertFalse(hasEndpoint(endpointLeaseStore, "worker-1"));
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
    void shutdownReleasesEndpointLeaseBeforeClearingRoutes() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore();
        evidenceDriver.setEndpointLeaseStore(endpointLeaseStore);
        Channel channel = mockActiveChannel("worker-1");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", channel, ctx);

        manager.shutdown();

        assertFalse(hasEndpoint(endpointLeaseStore, "worker-1"));
    }

    @Test
    void activeWebSocketSessionRefreshesEndpointLease() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore(1_200L);
        evidenceDriver.setEndpointLeaseStore(endpointLeaseStore);
        Channel channel = mockActiveChannel("worker-1");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", channel, ctx);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Thread.sleep(2_200L);
            assertTrue(hasEndpoint(endpointLeaseStore, "worker-1"));
        });
    }

    @Test
    void evidenceDriverReprojectsActiveWebSocketSessions() {
        InMemoryTransportEndpointLeaseStore firstStore = new InMemoryTransportEndpointLeaseStore(30_000L);
        InMemoryTransportEndpointLeaseStore secondStore = new InMemoryTransportEndpointLeaseStore(30_000L);
        evidenceDriver.setEndpointLeaseStore(firstStore);
        Channel channel = mockActiveChannel("worker-1");
        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", channel, ctx);
        evidenceDriver.setEndpointLeaseStore(secondStore);
        evidenceDriver.projectActiveSessions(sessionStore.activeRecords(), "websocket endpoint lease store replaced");

        assertTrue(hasEndpoint(secondStore, "worker-1"));
        assertEquals("route-1", endpoint(secondStore, "worker-1").endpointAddress());
        assertEquals("worker-1", endpoint(secondStore, "worker-1").endpointLeaseId());
    }

    private static TransportEndpointLeaseViewRecord endpoint(InMemoryTransportEndpointLeaseStore store, String workerId) {
        return store.currentEndpointLease(DELIVERY_BUCKET_ID, workerId).orElseThrow();
    }

    private static boolean hasEndpoint(InMemoryTransportEndpointLeaseStore store, String workerId) {
        return store.currentEndpointLease(DELIVERY_BUCKET_ID, workerId).isPresent();
    }

    private Channel mockActiveChannel(String idText) {
        Channel ch = mock(Channel.class);
        ChannelId chId = mock(ChannelId.class);
        when(chId.asShortText()).thenReturn(idText);
        when(ch.id()).thenReturn(chId);
        when(ch.isActive()).thenReturn(true);
        return ch;
    }

    private WebSocketSessionController newController(String adapterId) {
        sessionStore = new WebSocketSessionStore(adapterId);
        evidenceDriver = new WebSocketSessionEvidenceDriver(adapterId);
        WebSocketSessionRefreshLoop refreshLoop =
                new WebSocketSessionRefreshLoop(adapterId, sessionStore, evidenceDriver);
        return new WebSocketSessionController(sessionStore, evidenceDriver, refreshLoop);
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
