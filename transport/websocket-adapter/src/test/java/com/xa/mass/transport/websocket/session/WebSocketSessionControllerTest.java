package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.channel.NoopWorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerSessionPresenceEvent;
import com.xa.mass.transport.lease.TransportEndpointLeaseViewRecord;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;
import io.netty.channel.Channel;
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

        manager.addSession(DELIVERY_BUCKET_ID, "worker-1", "worker-1", ch1);
        manager.addSession(DELIVERY_BUCKET_ID, "worker-2", "worker-2", ch2);

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

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", channel);

        assertTrue(sessionStore.hasActiveEndpointAddress("route-1"));
        assertEquals("ws-public", new WebSocketEndpointInspector(sessionStore).listWorkerEndpoints().get(0).getAdapterId());
    }

    @Test
    void sessionsProjectEndpointLeaseIntoTransportOwnedStore() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore =
                new InMemoryTransportEndpointLeaseStore(30_000L);
        RecordingWorkerPresenceIngress presenceIngress = new RecordingWorkerPresenceIngress();
        manager = newController(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID, endpointLeaseStore, presenceIngress);
        Channel channel = mockActiveChannel("worker-1");

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", channel);

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
        manager = newController(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID, endpointLeaseStore,
                NoopWorkerPresenceIngress.INSTANCE);
        Channel firstChannel = mockActiveChannel("worker-1");
        Channel secondChannel = mockActiveChannel("worker-2");

        manager.addSession(DELIVERY_BUCKET_ID, "group-route", "worker-1", firstChannel);
        manager.addSession(DELIVERY_BUCKET_ID, "group-route", "worker-2", secondChannel);

        assertTrue(hasEndpoint(endpointLeaseStore, "worker-1"));
        assertTrue(hasEndpoint(endpointLeaseStore, "worker-2"));

        manager.removeSession(firstChannel);

        assertFalse(hasEndpoint(endpointLeaseStore, "worker-1"));
        assertTrue(hasEndpoint(endpointLeaseStore, "worker-2"));
        assertTrue(sessionStore.hasActiveEndpointAddress("group-route"));
    }

    @Test
    void sessionStoreIndexesSelectedWorkersUnderSharedEndpointAddress() {
        Channel firstChannel = mockActiveChannel("worker-1-channel");
        Channel secondChannel = mockActiveChannel("worker-2-channel");

        manager.addSession(DELIVERY_BUCKET_ID, "group-route", "worker-1", firstChannel);
        manager.addSession(DELIVERY_BUCKET_ID, "group-route", "worker-2", secondChannel);

        assertEquals(firstChannel, sessionStore.activeChannelForWorker("worker-1"));
        assertEquals(secondChannel, sessionStore.activeChannelForWorker("worker-2"));
        assertEquals(2, sessionStore.activeChannelsForEndpointAddress("group-route").size());
    }

    @Test
    void replacingWorkerChannelKeepsConnectionCountStable() {
        RecordingWorkerPresenceIngress presenceIngress = new RecordingWorkerPresenceIngress();
        manager = newController(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID,
                new InMemoryTransportEndpointLeaseStore(30_000L),
                presenceIngress);
        Channel firstChannel = mockActiveChannel("worker-1-old");
        Channel secondChannel = mockActiveChannel("worker-1-new");

        manager.addSession(DELIVERY_BUCKET_ID, "worker-1", "worker-1", firstChannel);
        assertEquals(1, sessionStore.activeConnectionCount());

        manager.addSession(DELIVERY_BUCKET_ID, "worker-1", "worker-1", secondChannel);

        assertEquals(1, sessionStore.activeConnectionCount());
        assertEquals(secondChannel, sessionStore.activeChannelForEndpointAddress("worker-1"));
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
        manager = newController(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID, endpointLeaseStore,
                NoopWorkerPresenceIngress.INSTANCE);
        Channel firstChannel = mockActiveChannel("worker-1-old");
        Channel secondChannel = mockActiveChannel("worker-1-new");

        manager.addSession(DELIVERY_BUCKET_ID, "route-old", "worker-1", firstChannel);
        manager.addSession(DELIVERY_BUCKET_ID, "route-new", "worker-1", secondChannel);

        assertEquals(1, sessionStore.activeConnectionCount());
        assertNull(sessionStore.activeChannelForEndpointAddress("route-old"));
        assertEquals(secondChannel, sessionStore.activeChannelForEndpointAddress("route-new"));
        assertEquals("route-new", endpoint(endpointLeaseStore, "worker-1").endpointAddress());
        verify(firstChannel).close();

        assertEquals(secondChannel, sessionStore.activeChannelForWorker("worker-1"));
    }

    @Test
    void retiredWebSocketChannelCannotReclaimEndpointLease() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore();
        manager = newController(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID, endpointLeaseStore,
                NoopWorkerPresenceIngress.INSTANCE);
        Channel firstChannel = mockActiveChannel("worker-1-old");
        Channel secondChannel = mockActiveChannel("worker-1-new");

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", firstChannel);
        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", secondChannel);

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", firstChannel);

        assertEquals(secondChannel, sessionStore.activeChannelForEndpointAddress("route-1"));
        assertEquals("worker-1-new", endpoint(endpointLeaseStore, "worker-1").endpointLeaseId());
        verify(firstChannel).close();
    }

    @Test
    void removingStaleChannelDoesNotReleaseReplacementEndpointLease() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore();
        manager = newController(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID, endpointLeaseStore,
                NoopWorkerPresenceIngress.INSTANCE);
        Channel firstChannel = mockActiveChannel("worker-1-old");
        Channel secondChannel = mockActiveChannel("worker-1-new");

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", firstChannel);
        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", secondChannel);

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
        manager.addSession(DELIVERY_BUCKET_ID, "worker-a", "worker-a", active);
        manager.addSession(DELIVERY_BUCKET_ID, "worker-b", "worker-b", inactive);

        manager.shutdown();

        verify(active).close();
        verify(inactive, never()).close();
    }

    @Test
    void shutdownReleasesEndpointLeaseBeforeClearingRoutes() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore();
        manager = newController(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID, endpointLeaseStore,
                NoopWorkerPresenceIngress.INSTANCE);
        Channel channel = mockActiveChannel("worker-1");

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", channel);

        manager.shutdown();

        assertFalse(hasEndpoint(endpointLeaseStore, "worker-1"));
    }

    @Test
    void activeWebSocketSessionRefreshesEndpointLease() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore(1_200L);
        manager = newController(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID, endpointLeaseStore,
                NoopWorkerPresenceIngress.INSTANCE);
        Channel channel = mockActiveChannel("worker-1");

        manager.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", channel);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            Thread.sleep(2_200L);
            assertTrue(hasEndpoint(endpointLeaseStore, "worker-1"));
        });
    }

    @Test
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
        return newController(adapterId, new InMemoryTransportEndpointLeaseStore(30_000L),
                NoopWorkerPresenceIngress.INSTANCE);
    }

    private WebSocketSessionController newController(String adapterId,
                                                     InMemoryTransportEndpointLeaseStore endpointLeaseStore,
                                                     WorkerPresenceIngress presenceIngress) {
        sessionStore = new WebSocketSessionStore(adapterId);
        evidenceDriver = new WebSocketSessionEvidenceDriver(new AdapterSessionEvidencePublisher(
                adapterId,
                adapterId,
                endpointLeaseStore,
                presenceIngress
        ));
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
