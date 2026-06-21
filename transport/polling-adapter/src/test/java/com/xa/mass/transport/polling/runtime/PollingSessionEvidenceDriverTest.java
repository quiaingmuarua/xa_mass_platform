package com.xa.mass.transport.polling.runtime;

import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerSessionPresenceEvent;
import com.xa.mass.transport.model.CanonicalWorkerGroupRouteKeyCodec;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollingSessionEvidenceDriverTest {

    @Test
    void connectHeartbeatDisconnectPublishPresenceAndEndpointLease() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore(30_000L);
        RecordingWorkerPresenceIngress presenceIngress = new RecordingWorkerPresenceIngress();
        PollingSessionEvidenceDriver driver = new PollingSessionEvidenceDriver(
                "polling-default",
                "polling-mailbox",
                endpointLeaseStore,
                presenceIngress
        );

        assertTrue(driver.connect("worker-1", "bucket-1", routeKey(), "conn-1", "connected"));
        var connected = endpointLeaseStore.currentEndpointLease("bucket-1", "worker-1").orElseThrow();
        assertEquals(routeKey(), connected.endpointAddress());
        assertEquals("conn-1", connected.endpointLeaseId());
        assertEquals(List.of("CONNECTED:worker-1:polling-default:polling-mailbox:" + routeKey() + ":conn-1:connected:conn-1"),
                presenceIngress.events);

        PollingSessionEvidenceDriver staleDriver = new PollingSessionEvidenceDriver(
                "polling-default",
                "polling-mailbox",
                endpointLeaseStore,
                new RecordingWorkerPresenceIngress()
        );
        assertFalse(staleDriver.heartbeat("worker-1", "bucket-1", routeKey(), "stale-conn", "stale-heartbeat"));
        assertTrue(driver.heartbeat("worker-1", "bucket-1", routeKey(), "conn-1", "heartbeat"));
        assertEquals(List.of(
                        "CONNECTED:worker-1:polling-default:polling-mailbox:" + routeKey() + ":conn-1:connected:conn-1",
                        "HEARTBEAT:worker-1:polling-default:polling-mailbox:" + routeKey() + ":conn-1:heartbeat:conn-1"
                ),
                presenceIngress.events);

        assertFalse(staleDriver.disconnect("worker-1", "bucket-1", routeKey(), "stale-conn", "stale-disconnect"));
        assertTrue(endpointLeaseStore.currentEndpointLease("bucket-1", "worker-1").isPresent());

        assertTrue(driver.disconnect("worker-1", "bucket-1", routeKey(), "conn-1", "disconnect"));
        assertTrue(endpointLeaseStore.currentEndpointLease("bucket-1", "worker-1").isEmpty());
        assertEquals(List.of(
                        "CONNECTED:worker-1:polling-default:polling-mailbox:" + routeKey() + ":conn-1:connected:conn-1",
                        "HEARTBEAT:worker-1:polling-default:polling-mailbox:" + routeKey() + ":conn-1:heartbeat:conn-1",
                        "DISCONNECTED:worker-1:polling-default:polling-mailbox:" + routeKey() + ":conn-1:disconnect:conn-1"
                ),
                presenceIngress.events);
    }

    private static String routeKey() {
        return CanonicalWorkerGroupRouteKeyCodec.encode("bucket-1");
    }

    private static final class RecordingWorkerPresenceIngress implements WorkerPresenceIngress {
        private final List<String> events = new ArrayList<>();

        @Override
        public void sessionConnected(WorkerSessionPresenceEvent event) {
            events.add(describe(event));
        }

        @Override
        public void sessionHeartbeat(WorkerSessionPresenceEvent event) {
            events.add(describe(event));
        }

        @Override
        public void sessionDisconnected(WorkerSessionPresenceEvent event) {
            events.add(describe(event));
        }

        private String describe(WorkerSessionPresenceEvent event) {
            return event.eventType().name() + ":"
                    + event.workerId() + ":"
                    + event.adapterId() + ":"
                    + event.adapterMailboxKey() + ":"
                    + event.routeKey() + ":"
                    + event.sessionToken() + ":"
                    + event.reason() + ":"
                    + event.traceId();
        }
    }
}
