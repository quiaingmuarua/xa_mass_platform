package com.xa.mass.transport.runtime.lease;

import com.xa.mass.transport.runtime.embedded.AdapterSessionIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdapterSessionEvidencePublisherTest {

    @Test
    void connectedPublishesEndpointLeaseOnly() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore(30_000L);
        AdapterSessionEvidencePublisher publisher = new AdapterSessionEvidencePublisher(
                "websocket",
                endpointLeaseStore,
                CurrentSessionDisconnectSink.NOOP
        );

        publisher.connected(new AdapterSessionIdentity("bucket-1", "worker-1"), "session-1", "connected");

        var view = endpointLeaseStore.currentEndpointLease("bucket-1", "worker-1").orElseThrow();
        assertEquals("session-1", view.sessionToken());
    }
}
