package com.xa.mass.transport.websocket.session;

import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import com.xa.mass.transport.runtime.lease.CurrentSessionDisconnectSink;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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
        AdapterSessionEvidencePublisher publisher = publisher(new InMemoryTransportEndpointLeaseStore(1_200L));
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry(publisher);
        WebSocketSessionEvidenceRefresher refresher = new WebSocketSessionEvidenceRefresher(
                ADAPTER_ID,
                registry,
                publisher
        );

        refresher.start();

        assertTrue(refresher.isRunning());

        refresher.stop();

        assertFalse(refresher.isRunning());
    }

    @Test
    void activeSessionRefreshesEndpointLeaseEvidence() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore(1_200L);
        AdapterSessionEvidencePublisher publisher = publisher(endpointLeaseStore);
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry(publisher);
        WebSocketSessionEvidenceRefresher refresher =
                new WebSocketSessionEvidenceRefresher(ADAPTER_ID, registry, publisher);

        registry.addSession(DELIVERY_BUCKET_ID, "worker-1", mockActiveChannel("worker-1"));

        refresher.start();

        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            Thread.sleep(1_600L);
            assertTrue(endpointLeaseStore.currentEndpointLease(DELIVERY_BUCKET_ID, "worker-1").isPresent());
        });

        refresher.stop();
        registry.shutdown();
    }

    @Test
    void stopPreventsFurtherRefreshAndAllowsLeaseToExpire() throws InterruptedException {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore(1_200L);
        AdapterSessionEvidencePublisher publisher = publisher(endpointLeaseStore);
        WebSocketSessionRegistry registry = new WebSocketSessionRegistry(publisher);
        WebSocketSessionEvidenceRefresher refresher =
                new WebSocketSessionEvidenceRefresher(ADAPTER_ID, registry, publisher);

        registry.addSession(DELIVERY_BUCKET_ID, "worker-1", mockActiveChannel("worker-1"));

        refresher.start();
        Thread.sleep(1_600L);
        assertTrue(endpointLeaseStore.currentEndpointLease(DELIVERY_BUCKET_ID, "worker-1").isPresent());

        refresher.stop();
        Thread.sleep(1_400L);
        assertFalse(endpointLeaseStore.currentEndpointLease(DELIVERY_BUCKET_ID, "worker-1").isPresent());
        assertFalse(refresher.isRunning());

        registry.shutdown();
    }

    private static AdapterSessionEvidencePublisher publisher(InMemoryTransportEndpointLeaseStore endpointLeaseStore) {
        return new AdapterSessionEvidencePublisher(
                ADAPTER_ID,
                endpointLeaseStore,
                CurrentSessionDisconnectSink.NOOP
        );
    }

    private static Channel mockActiveChannel(String idText) {
        Channel channel = mock(Channel.class);
        ChannelId channelId = mock(ChannelId.class);
        when(channelId.asShortText()).thenReturn(idText);
        when(channel.id()).thenReturn(channelId);
        when(channel.isActive()).thenReturn(true);
        return channel;
    }
}
