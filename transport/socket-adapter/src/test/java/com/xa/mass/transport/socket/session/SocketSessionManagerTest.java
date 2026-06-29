package com.xa.mass.transport.socket.session;

import com.xa.mass.transport.lease.TransportEndpointLeaseViewRecord;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import com.xa.mass.transport.runtime.lease.CurrentSessionDisconnectSink;
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
        RecordingDisconnectSink disconnectSink = new RecordingDisconnectSink();
        SocketSessionManager manager = manager("socket", "socket", endpointLeaseStore, disconnectSink);

        manager.addSession(DELIVERY_BUCKET_ID, "worker-1", "endpoint-1",
                activeSocket(), mock(BufferedWriter.class));

        assertEquals("endpoint-1", endpoint(endpointLeaseStore, "worker-1").sessionToken());
        assertEquals(List.of(), disconnectSink.events);

        manager.recordHeartbeat("worker-1", "endpoint-1", "socket heartbeat", "trace-1");

        assertTrue(hasEndpoint(endpointLeaseStore, "worker-1"));
        assertEquals(List.of(), disconnectSink.events);

        manager.removeSession("endpoint-1");

        assertFalse(hasEndpoint(endpointLeaseStore, "worker-1"));
        assertEquals(List.of("bucket-1:worker-1:socket disconnected"), disconnectSink.events);
    }

    @Test
    void targetedWorkerSessionReadUsesWorkerIndex() {
        SocketSessionManager manager = manager("socket-edge", "socket-edge");

        manager.addSession(DELIVERY_BUCKET_ID, "worker-1", "endpoint-1",
                activeSocket(), mock(BufferedWriter.class));

        assertTrue(manager.hasActiveWorkerSession("worker-1"));
        assertFalse(manager.hasActiveWorkerSession("worker-2"));
        assertEquals("socket-edge", manager.getAdapterId());
    }

    @Test
    void selectedWorkerSendUsesWorkerIndex() throws IOException {
        SocketSessionManager manager = manager("socket", "socket");
        BufferedWriter firstWriter = mock(BufferedWriter.class);
        BufferedWriter secondWriter = mock(BufferedWriter.class);

        manager.addSession(DELIVERY_BUCKET_ID, "worker-1", "endpoint-1", activeSocket(), firstWriter);
        manager.addSession(DELIVERY_BUCKET_ID, "worker-2", "endpoint-2", activeSocket(), secondWriter);

        assertTrue(manager.sendToWorker("worker-2", "{\"messageId\":\"msg-2\"}"));

        verify(firstWriter, never()).write(anyString());
        verify(secondWriter).write("{\"messageId\":\"msg-2\"}");
    }

    @Test
    void staleEndpointHeartbeatAndDisconnectDoNotOverrideReplacementEndpointLease() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore();
        RecordingDisconnectSink disconnectSink = new RecordingDisconnectSink();
        SocketSessionManager manager = manager("socket", "socket", endpointLeaseStore, disconnectSink);

        manager.addSession(DELIVERY_BUCKET_ID, "worker-1", "endpoint-old",
                activeSocket(), mock(BufferedWriter.class));
        manager.addSession(DELIVERY_BUCKET_ID, "worker-1", "endpoint-new",
                activeSocket(), mock(BufferedWriter.class));
        assertEquals(List.of(), disconnectSink.events);

        manager.recordHeartbeat("worker-1", "endpoint-old", "stale-heartbeat", "trace-old");

        assertTrue(hasEndpoint(endpointLeaseStore, "worker-1"));
        assertEquals("endpoint-new", endpoint(endpointLeaseStore, "worker-1").sessionToken());

        manager.removeSession("endpoint-old");

        assertTrue(hasEndpoint(endpointLeaseStore, "worker-1"));
        assertEquals(List.of(), disconnectSink.events);

        manager.removeSession("endpoint-new");

        assertFalse(hasEndpoint(endpointLeaseStore, "worker-1"));
        assertEquals(List.of("bucket-1:worker-1:socket disconnected"), disconnectSink.events);
    }

    @Test
    void shutdownReleasesEndpointLeaseBeforeClearingSessions() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore();
        SocketSessionManager manager = manager("socket", "socket", endpointLeaseStore, CurrentSessionDisconnectSink.NOOP);

        manager.addSession(DELIVERY_BUCKET_ID, "worker-1", "endpoint-1",
                activeSocket(), mock(BufferedWriter.class));

        manager.shutdown();

        assertFalse(hasEndpoint(endpointLeaseStore, "worker-1"));
    }

    @Test
    void replacingSelectedWorkerRetiresOldEndpoint() throws IOException {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore();
        SocketSessionManager manager = manager("socket", "socket", endpointLeaseStore, CurrentSessionDisconnectSink.NOOP);
        BufferedWriter oldWriter = mock(BufferedWriter.class);
        BufferedWriter newWriter = mock(BufferedWriter.class);
        Socket oldSocket = activeSocket();
        Socket newSocket = activeSocket();

        manager.addSession(DELIVERY_BUCKET_ID, "worker-1", "endpoint-old", oldSocket, oldWriter);
        manager.addSession(DELIVERY_BUCKET_ID, "worker-1", "endpoint-new", newSocket, newWriter);

        assertEquals(1, manager.getActiveConnectionCount());
        assertEquals("endpoint-new", endpoint(endpointLeaseStore, "worker-1").sessionToken());
        verify(oldSocket).close();

        assertTrue(manager.sendToWorker("worker-1", "{\"messageId\":\"msg-new\"}"));
        verify(oldWriter, never()).write(anyString());
        verify(newWriter).write("{\"messageId\":\"msg-new\"}");
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

    private static SocketSessionManager manager(String adapterId, String adapterMailboxKey) {
        return new SocketSessionManager(
                adapterId,
                adapterMailboxKey,
                AdapterSessionEvidencePublisher.noop(adapterId)
        );
    }

    private static SocketSessionManager manager(String adapterId,
                                                String adapterMailboxKey,
                                                InMemoryTransportEndpointLeaseStore endpointLeaseStore,
                                                CurrentSessionDisconnectSink disconnectSink) {
        return new SocketSessionManager(
                adapterId,
                adapterMailboxKey,
                new AdapterSessionEvidencePublisher(
                        adapterId,
                        endpointLeaseStore,
                        disconnectSink
                )
        );
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
