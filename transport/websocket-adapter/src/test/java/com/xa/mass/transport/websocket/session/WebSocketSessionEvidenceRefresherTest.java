package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerSessionPresenceEvent;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSocketSessionEvidenceRefresherTest {

    private static final String ADAPTER_ID = "websocket";
    private static final String DELIVERY_BUCKET_ID = "bucket-1";

    @Test
    void startWithNoSessionsIsNoopAndCanStop() {
        WebSocketSessionStore store = new WebSocketSessionStore(ADAPTER_ID);
        RecordingWorkerPresenceIngress presenceIngress = new RecordingWorkerPresenceIngress();
        WebSocketSessionEvidenceRefresher refresher = new WebSocketSessionEvidenceRefresher(
                ADAPTER_ID,
                store,
                publisher(presenceIngress)
        );

        refresher.start();

        assertTrue(refresher.isRunning());
        assertEquals(List.of(), presenceIngress.events);

        refresher.stop();

        assertFalse(refresher.isRunning());
    }

    @Test
    void activeSessionRefreshesEndpointLeaseEvidence() {
        WebSocketSessionStore store = new WebSocketSessionStore(ADAPTER_ID);
        RecordingWorkerPresenceIngress presenceIngress = new RecordingWorkerPresenceIngress();
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore(1_200L);
        AdapterSessionEvidencePublisher publisher = new AdapterSessionEvidencePublisher(
                ADAPTER_ID,
                ADAPTER_ID,
                endpointLeaseStore,
                presenceIngress
        );
        WebSocketSessionController controller = new WebSocketSessionController(store, publisher);
        WebSocketSessionEvidenceRefresher refresher =
                new WebSocketSessionEvidenceRefresher(ADAPTER_ID, store, publisher);

        controller.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", mockActiveChannel("worker-1"));
        presenceIngress.events.clear();

        refresher.start();

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (presenceIngress.events.stream().noneMatch(event -> event.startsWith("heartbeat:worker-1"))) {
                Thread.sleep(25L);
            }
        });
        assertTrue(endpointLeaseStore.currentEndpointLease(DELIVERY_BUCKET_ID, "worker-1").isPresent());

        refresher.stop();
        controller.shutdown();
    }

    @Test
    void stopPreventsFurtherRefreshAndCanRestart() {
        WebSocketSessionStore store = new WebSocketSessionStore(ADAPTER_ID);
        RecordingWorkerPresenceIngress presenceIngress = new RecordingWorkerPresenceIngress();
        AdapterSessionEvidencePublisher publisher = publisher(presenceIngress);
        WebSocketSessionController controller = new WebSocketSessionController(store, publisher);
        WebSocketSessionEvidenceRefresher refresher =
                new WebSocketSessionEvidenceRefresher(ADAPTER_ID, store, publisher);

        controller.addSession(DELIVERY_BUCKET_ID, "route-1", "worker-1", mockActiveChannel("worker-1"));
        presenceIngress.events.clear();

        refresher.start();
        awaitHeartbeatCount(presenceIngress, 1);
        refresher.stop();
        int countAfterStop = heartbeatCount(presenceIngress);

        sleep(1_200L);

        assertEquals(countAfterStop, heartbeatCount(presenceIngress));
        assertFalse(refresher.isRunning());

        refresher.start();
        awaitHeartbeatCount(presenceIngress, countAfterStop + 1);

        refresher.stop();
        controller.shutdown();
    }

    private static AdapterSessionEvidencePublisher publisher(WorkerPresenceIngress presenceIngress) {
        return new AdapterSessionEvidencePublisher(
                ADAPTER_ID,
                ADAPTER_ID,
                new InMemoryTransportEndpointLeaseStore(1_200L),
                presenceIngress
        );
    }

    private static void awaitHeartbeatCount(RecordingWorkerPresenceIngress presenceIngress, int expectedCount) {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            while (heartbeatCount(presenceIngress) < expectedCount) {
                Thread.sleep(25L);
            }
        });
    }

    private static int heartbeatCount(RecordingWorkerPresenceIngress presenceIngress) {
        int count = 0;
        for (String event : presenceIngress.events) {
            if (event.startsWith("heartbeat:worker-1")) {
                count++;
            }
        }
        return count;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static Channel mockActiveChannel(String idText) {
        Channel channel = mock(Channel.class);
        ChannelId channelId = mock(ChannelId.class);
        when(channelId.asShortText()).thenReturn(idText);
        when(channel.id()).thenReturn(channelId);
        when(channel.isActive()).thenReturn(true);
        return channel;
    }

    private static final class RecordingWorkerPresenceIngress implements WorkerPresenceIngress {
        private final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void sessionConnected(WorkerSessionPresenceEvent event) {
            events.add("connected:" + event.workerId());
        }

        @Override
        public void sessionHeartbeat(WorkerSessionPresenceEvent event) {
            events.add("heartbeat:" + event.workerId());
        }

        @Override
        public void sessionDisconnected(WorkerSessionPresenceEvent event) {
            events.add("disconnected:" + event.workerId());
        }
    }
}
