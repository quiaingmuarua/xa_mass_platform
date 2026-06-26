package com.xa.mass.transport.runtime.lease;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdapterSessionEvidencePublisherTest {

    @Test
    void connectedPublishesEndpointLeaseAndCurrentSessionConnectSignal() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore(30_000L);
        RecordingConnectSink connectSink = new RecordingConnectSink();
        AdapterSessionEvidencePublisher publisher = new AdapterSessionEvidencePublisher(
                "websocket",
                "websocket-mailbox",
                endpointLeaseStore,
                connectSink,
                CurrentSessionDisconnectSink.NOOP
        );

        publisher.connected("worker-1", "bucket-1", "session-1", "connected", "trace-1");

        assertTrue(endpointLeaseStore.currentEndpointLease("bucket-1", "worker-1").isPresent());
        assertEquals(List.of("bucket-1:worker-1:connected"), connectSink.events);
    }

    private static final class RecordingConnectSink implements CurrentSessionConnectSink {
        private final List<String> events = new ArrayList<>();

        @Override
        public void currentSessionConnected(String deliveryBucketId,
                                            String workerId,
                                            String reason,
                                            long observedAtMillis) {
            events.add(deliveryBucketId + ":" + workerId + ":" + reason);
        }
    }
}
