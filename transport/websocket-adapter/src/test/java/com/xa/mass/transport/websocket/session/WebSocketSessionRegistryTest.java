package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.lease.TransportEndpointLeaseViewRecord;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import com.xa.mass.transport.runtime.lease.CurrentSessionDisconnectSink;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import com.xa.mass.transport.websocket.runtime.WebSocketAdapterConfig;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class WebSocketSessionRegistryTest {

    private static final String WORKER_GROUP_ID = "group-1";

    private WebSocketSessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = newRegistry(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID);
    }

    @Test
    void shutdownClosesAllActiveChannelsAndClearsState() {
        Channel ch1 = mockActiveChannel("ch1");
        Channel ch2 = mockActiveChannel("ch2");

        registry.addSession(WORKER_GROUP_ID, "worker-1", ch1);
        registry.addSession(WORKER_GROUP_ID, "worker-2", ch2);

        assertEquals(2, registry.activeConnectionCount());

        registry.shutdown();

        verify(ch1).close();
        verify(ch2).close();
        assertEquals(0, registry.activeConnectionCount());
        assertFalse(registry.sendTextToWorker("worker-1", "{}"));
        assertFalse(registry.sendTextToWorker("worker-2", "{}"));
    }

    @Test
    void shutdownOnEmptyRegistryIsIdempotent() {
        assertDoesNotThrow(() -> registry.shutdown());
    }

    @Test
    void sessionsProjectEndpointLeaseWithoutRouteKey() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore =
                new InMemoryTransportEndpointLeaseStore(30_000L);
        RecordingDisconnectSink disconnectSink = new RecordingDisconnectSink();
        registry = newRegistry(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID, endpointLeaseStore, disconnectSink);
        Channel channel = mockActiveChannel("worker-1");

        registry.addSession(WORKER_GROUP_ID, "worker-1", channel);

        assertTrue(hasEndpoint(endpointLeaseStore, "worker-1"));
        TransportEndpointLeaseViewRecord endpoint = endpoint(endpointLeaseStore, "worker-1");
        assertEquals(WORKER_GROUP_ID, endpoint.deliveryBucketId());
        assertEquals("worker-1", endpoint.workerId());
        assertEquals("worker-1", endpoint.sessionToken());
        assertEquals(List.of(), disconnectSink.events);

        registry.removeSession(channel);

        assertFalse(hasEndpoint(endpointLeaseStore, "worker-1"));
        assertEquals(List.of("group-1:worker-1:websocket disconnected"), disconnectSink.events);
    }

    @Test
    void selectedWorkerLookupDoesNotNeedRouteOrGroupIndex() {
        Channel firstChannel = mockActiveChannel("worker-1-channel");
        Channel secondChannel = mockActiveChannel("worker-2-channel");

        registry.addSession(WORKER_GROUP_ID, "worker-1", firstChannel);
        registry.addSession(WORKER_GROUP_ID, "worker-2", secondChannel);

        assertTrue(registry.sendTextToWorker("worker-1", "{\"a\":1}"));
        assertTrue(registry.sendTextToWorker("worker-2", "{\"b\":2}"));
        assertFalse(registry.sendTextToWorker("worker-3", "{}"));
        verify(firstChannel).writeAndFlush(any(TextWebSocketFrame.class));
        verify(secondChannel).writeAndFlush(any(TextWebSocketFrame.class));
    }

    @Test
    void replacingWorkerChannelKeepsConnectionCountStable() {
        RecordingDisconnectSink disconnectSink = new RecordingDisconnectSink();
        registry = newRegistry(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID,
                new InMemoryTransportEndpointLeaseStore(30_000L),
                disconnectSink);
        Channel firstChannel = mockActiveChannel("worker-1-old");
        Channel secondChannel = mockActiveChannel("worker-1-new");

        registry.addSession(WORKER_GROUP_ID, "worker-1", firstChannel);
        assertEquals(1, registry.activeConnectionCount());

        registry.addSession(WORKER_GROUP_ID, "worker-1", secondChannel);

        assertEquals(1, registry.activeConnectionCount());
        assertTrue(registry.sendTextToWorker("worker-1", "{\"hello\":\"world\"}"));
        verify(firstChannel).close();
        verify(secondChannel).writeAndFlush(any(TextWebSocketFrame.class));
        assertEquals(List.of(), disconnectSink.events);

        registry.removeSession(firstChannel);
        assertEquals(1, registry.activeConnectionCount());

        registry.removeSession(secondChannel);
        assertEquals(0, registry.activeConnectionCount());
        assertEquals(List.of("group-1:worker-1:websocket disconnected"), disconnectSink.events);
    }

    @Test
    void retiredWebSocketChannelCannotReclaimEndpointLease() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore();
        registry = newRegistry(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID, endpointLeaseStore,
                CurrentSessionDisconnectSink.NOOP);
        Channel firstChannel = mockActiveChannel("worker-1-old");
        Channel secondChannel = mockActiveChannel("worker-1-new");

        registry.addSession(WORKER_GROUP_ID, "worker-1", firstChannel);
        registry.addSession(WORKER_GROUP_ID, "worker-1", secondChannel);

        registry.addSession(WORKER_GROUP_ID, "worker-1", firstChannel);

        assertEquals("worker-1-new", endpoint(endpointLeaseStore, "worker-1").sessionToken());
        verify(firstChannel).close();
    }

    @Test
    void removingStaleChannelDoesNotReleaseReplacementEndpointLease() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore();
        registry = newRegistry(WebSocketAdapterConfig.DEFAULT_ADAPTER_ID, endpointLeaseStore,
                CurrentSessionDisconnectSink.NOOP);
        Channel firstChannel = mockActiveChannel("worker-1-old");
        Channel secondChannel = mockActiveChannel("worker-1-new");

        registry.addSession(WORKER_GROUP_ID, "worker-1", firstChannel);
        registry.addSession(WORKER_GROUP_ID, "worker-1", secondChannel);

        registry.removeSession(firstChannel);

        assertTrue(hasEndpoint(endpointLeaseStore, "worker-1"));
        assertEquals("worker-1-new", endpoint(endpointLeaseStore, "worker-1").sessionToken());

        registry.removeSession(secondChannel);

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
        registry.addSession(WORKER_GROUP_ID, "worker-a", active);
        registry.addSession(WORKER_GROUP_ID, "worker-b", inactive);

        registry.shutdown();

        verify(active).close();
        verify(inactive, never()).close();
    }

    @Test
    void sentFramePreservesRawMessage() {
        Channel channel = mockActiveChannel("worker-1");
        registry.addSession(WORKER_GROUP_ID, "worker-1", channel);

        assertTrue(registry.sendTextToWorker("worker-1", " raw "));

        ArgumentCaptor<TextWebSocketFrame> captor = ArgumentCaptor.forClass(TextWebSocketFrame.class);
        verify(channel).writeAndFlush(captor.capture());
        assertEquals(" raw ", captor.getValue().text());
    }

    @Test
    void currentWorkerIdReturnsOnlyChannelBinding() {
        Channel channel = mockActiveChannel("worker-1");

        assertNull(registry.currentWorkerId(channel));

        registry.addSession(WORKER_GROUP_ID, "worker-1", channel);

        assertEquals("worker-1", registry.currentWorkerId(channel));
    }

    private static TransportEndpointLeaseViewRecord endpoint(InMemoryTransportEndpointLeaseStore store, String workerId) {
        return store.currentEndpointLease(WORKER_GROUP_ID, workerId).orElseThrow();
    }

    private static boolean hasEndpoint(InMemoryTransportEndpointLeaseStore store, String workerId) {
        return store.currentEndpointLease(WORKER_GROUP_ID, workerId).isPresent();
    }

    private Channel mockActiveChannel(String idText) {
        Channel ch = mock(Channel.class);
        ChannelId chId = mock(ChannelId.class);
        when(chId.asShortText()).thenReturn(idText);
        when(ch.id()).thenReturn(chId);
        when(ch.isActive()).thenReturn(true);
        return ch;
    }

    private WebSocketSessionRegistry newRegistry(String adapterId) {
        return newRegistry(adapterId, new InMemoryTransportEndpointLeaseStore(30_000L),
                CurrentSessionDisconnectSink.NOOP);
    }

    private WebSocketSessionRegistry newRegistry(String adapterId,
                                                 InMemoryTransportEndpointLeaseStore endpointLeaseStore,
                                                 CurrentSessionDisconnectSink disconnectSink) {
        AdapterSessionEvidencePublisher sessionEvidencePublisher = new AdapterSessionEvidencePublisher(
                adapterId,
                endpointLeaseStore,
                disconnectSink
        );
        return new WebSocketSessionRegistry(sessionEvidencePublisher);
    }

    private static final class RecordingDisconnectSink implements CurrentSessionDisconnectSink {
        private final List<String> events = new ArrayList<>();

        @Override
        public void currentSessionDisconnected(String deliveryBucketId,
                                               String workerId,
                                               String reason,
                                               long observedAtMillis) {
            events.add(deliveryBucketId + ":" + workerId + ":" + reason);
        }
    }
}
