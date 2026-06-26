package com.xa.mass.transport.polling.runtime;

import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import com.xa.mass.transport.runtime.lease.CurrentSessionConnectSink;
import com.xa.mass.transport.runtime.lease.CurrentSessionDisconnectSink;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollingSessionEvidenceDriverTest {

    @Test
    void connectHeartbeatDisconnectPublishEndpointLeaseAndCurrentDisconnectSignal() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore(30_000L);
        RecordingDisconnectSink disconnectSink = new RecordingDisconnectSink();
        PollingSessionEvidenceDriver driver = new PollingSessionEvidenceDriver(new AdapterSessionEvidencePublisher(
                "polling-default",
                "polling-mailbox",
                endpointLeaseStore,
                CurrentSessionConnectSink.NOOP,
                disconnectSink
        ));

        assertTrue(driver.connect("worker-1", "bucket-1", "conn-1", "connected"));
        var connected = endpointLeaseStore.currentEndpointLease("bucket-1", "worker-1").orElseThrow();
        assertEquals("conn-1", connected.endpointLeaseId());
        assertEquals(List.of(), disconnectSink.events);

        PollingSessionEvidenceDriver staleDriver = new PollingSessionEvidenceDriver(new AdapterSessionEvidencePublisher(
                "polling-default",
                "polling-mailbox",
                endpointLeaseStore,
                CurrentSessionConnectSink.NOOP,
                disconnectSink
        ));
        assertFalse(staleDriver.heartbeat("worker-1", "bucket-1", "stale-conn", "stale-heartbeat"));
        assertTrue(driver.heartbeat("worker-1", "bucket-1", "conn-1", "heartbeat"));
        assertEquals(List.of(), disconnectSink.events);

        assertFalse(staleDriver.disconnect("worker-1", "bucket-1", "stale-conn", "stale-disconnect"));
        assertTrue(endpointLeaseStore.currentEndpointLease("bucket-1", "worker-1").isPresent());
        assertEquals(List.of(), disconnectSink.events);

        assertTrue(driver.disconnect("worker-1", "bucket-1", "conn-1", "disconnect"));
        assertTrue(endpointLeaseStore.currentEndpointLease("bucket-1", "worker-1").isEmpty());
        assertEquals(List.of("bucket-1:worker-1:disconnect"), disconnectSink.events);
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
