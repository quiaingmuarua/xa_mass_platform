package com.xa.mass.sdk.worker;

import com.xa.mass.transport.channel.DeliveryPullChannel;
import com.xa.mass.transport.channel.DeliveryPullResult;
import com.xa.mass.transport.channel.PulledDeliveryMessage;
import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerSessionPresenceEvent;
import com.xa.mass.transport.model.CanonicalWorkerGroupRouteKeyCodec;
import com.xa.mass.transport.model.TransportResultIngressEnvelope;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PullWorkerSessionTest {

    @Test
    void pollResultDelegatesToDeliveryPullChannelWithRegisteredWorkerId() {
        DeliveryPullChannel deliveryPullChannel = mock(DeliveryPullChannel.class);
        DeliveryPullResult expected = DeliveryPullResult.delivered(List.of(message("msg-1")));
        when(deliveryPullChannel.pollDeliveryMessagesResult("group-1", "worker-1", 5, 250L)).thenReturn(expected);

        PullWorkerSession session = session(deliveryPullChannel, mock(TransportResultIngressChannel.class),
                new InMemoryTransportEndpointLeaseStore());

        TaskPullResult result = session.pollResult(5, 250L);

        assertEquals(TaskPullStatus.DELIVERED, result.getStatus());
        assertEquals("group-1", session.workerGroupId());
        assertEquals(List.of(correlation("msg-1", "attempt-msg-1")),
                result.getItems().stream().map(PulledTaskDispatch::getResultCorrelationRef).toList());
    }

    @Test
    void pollReturnsPulledTaskItemsFromExplicitPullResult() {
        DeliveryPullChannel deliveryPullChannel = mock(DeliveryPullChannel.class);
        when(deliveryPullChannel.pollDeliveryMessagesResult("group-1", "worker-1", 3, 100L))
                .thenReturn(DeliveryPullResult.delivered(List.of(message("msg-1"), message("msg-2"))));

        PullWorkerSession session = session(deliveryPullChannel, mock(TransportResultIngressChannel.class),
                new InMemoryTransportEndpointLeaseStore());

        List<PulledTaskDispatch> items = session.poll(3, 100L);

        assertEquals(List.of(correlation("msg-1", "attempt-msg-1"), correlation("msg-2", "attempt-msg-2")),
                items.stream().map(PulledTaskDispatch::getResultCorrelationRef).toList());
    }

    @Test
    void submitResultUsesSessionRouteKeyAndPulledAttemptContext() {
        TransportResultIngressChannel resultIngestChannel = mock(TransportResultIngressChannel.class);
        when(resultIngestChannel.ingest(any(TransportResultIngressEnvelope.class))).thenReturn(true);

        PullWorkerSession session = session(mock(DeliveryPullChannel.class), resultIngestChannel,
                new InMemoryTransportEndpointLeaseStore());

        PulledTaskDispatch item = new PulledTaskDispatch(
                correlation("msg-1", "attempt-1"),
                "crawler.fetch-page",
                Map.of("target", "target-1"),
                Map.of()
        );

        session.submitResult(item, true, "ok");

        var captured = org.mockito.ArgumentCaptor.forClass(TransportResultIngressEnvelope.class);
        verify(resultIngestChannel).ingest(captured.capture());
        assertEquals(routeKey(), captured.getValue().diagnostic("routeKey"));
        assertEquals(item.getResultCorrelationRef(), captured.getValue().getPartitionKey());
        assertTrue(captured.getValue().getPayload().contains("\"resultCorrelationRef\":\"" + item.getResultCorrelationRef() + "\""));
    }

    @Test
    void submitResultWithoutDispatchRouteKeyUsesCanonicalRouteKey() {
        TransportResultIngressChannel resultIngestChannel = mock(TransportResultIngressChannel.class);
        when(resultIngestChannel.ingest(any(TransportResultIngressEnvelope.class))).thenReturn(true);

        PullWorkerSession session = session(mock(DeliveryPullChannel.class), resultIngestChannel,
                new InMemoryTransportEndpointLeaseStore());

        String resultCorrelationRef = correlation("msg-1", "attempt-1");
        session.submitResult(resultCorrelationRef, true, "ok", null, Map.of());

        var captured = org.mockito.ArgumentCaptor.forClass(TransportResultIngressEnvelope.class);
        verify(resultIngestChannel).ingest(captured.capture());
        assertEquals(routeKey(), captured.getValue().diagnostic("routeKey"));
        assertEquals(resultCorrelationRef, captured.getValue().getPartitionKey());
        assertTrue(captured.getValue().getPayload().contains("\"resultCorrelationRef\":\"" + resultCorrelationRef + "\""));
    }

    @Test
    void connectHeartbeatDisconnectWritePresenceWithCanonicalRouteAndSessionToken() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore();
        RecordingWorkerPresenceIngress presenceIngress = new RecordingWorkerPresenceIngress();
        PullWorkerSession session = session(mock(DeliveryPullChannel.class), mock(TransportResultIngressChannel.class),
                endpointLeaseStore, presenceIngress);

        assertTrue(session.connectAndClaim("connected"));
        assertTrue(endpointLeaseStore.currentEndpointLease("group-1", "worker-1").isPresent());
        assertEquals("conn-1", endpointLeaseStore.currentEndpointLease("group-1", "worker-1")
                .orElseThrow()
                .endpointLeaseId());
        assertEquals(List.of("CONNECTED:worker-1:polling:" + routeKey() + ":conn-1:connected:conn-1"),
                presenceIngress.events);

        assertFalse(staleSession("stale-conn", endpointLeaseStore, new RecordingWorkerPresenceIngress())
                .disconnectIfCurrent("stale-disconnect"));
        assertTrue(endpointLeaseStore.currentEndpointLease("group-1", "worker-1").isPresent());

        assertFalse(staleSession("stale-conn", endpointLeaseStore, new RecordingWorkerPresenceIngress())
                .refreshHeartbeatIfCurrent("stale-heartbeat"));
        assertTrue(session.refreshHeartbeatIfCurrent("heartbeat"));
        assertTrue(endpointLeaseStore.currentEndpointLease("group-1", "worker-1").isPresent());
        assertEquals(List.of(
                        "CONNECTED:worker-1:polling:" + routeKey() + ":conn-1:connected:conn-1",
                        "HEARTBEAT:worker-1:polling:" + routeKey() + ":conn-1:heartbeat:conn-1"
                ),
                presenceIngress.events);

        assertTrue(session.disconnectIfCurrent("disconnect"));
        assertTrue(endpointLeaseStore.currentEndpointLease("group-1", "worker-1").isEmpty());
        assertEquals(List.of(
                        "CONNECTED:worker-1:polling:" + routeKey() + ":conn-1:connected:conn-1",
                        "HEARTBEAT:worker-1:polling:" + routeKey() + ":conn-1:heartbeat:conn-1",
                        "DISCONNECTED:worker-1:polling:" + routeKey() + ":conn-1:disconnect:conn-1"
                ),
                presenceIngress.events);
    }

    private static PullWorkerSession session(DeliveryPullChannel deliveryPullChannel,
                                             TransportResultIngressChannel resultIngestChannel,
                                             InMemoryTransportEndpointLeaseStore endpointLeaseStore) {
        return session("conn-1", deliveryPullChannel, resultIngestChannel, endpointLeaseStore,
                new RecordingWorkerPresenceIngress());
    }

    private static PullWorkerSession staleSession(String connectionId,
                                                  InMemoryTransportEndpointLeaseStore endpointLeaseStore,
                                                  WorkerPresenceIngress presenceIngress) {
        return session(connectionId, mock(DeliveryPullChannel.class), mock(TransportResultIngressChannel.class), endpointLeaseStore,
                presenceIngress);
    }

    private static PullWorkerSession session(DeliveryPullChannel deliveryPullChannel,
                                             TransportResultIngressChannel resultIngestChannel,
                                             InMemoryTransportEndpointLeaseStore endpointLeaseStore,
                                             WorkerPresenceIngress presenceIngress) {
        return session("conn-1", deliveryPullChannel, resultIngestChannel, endpointLeaseStore, presenceIngress);
    }

    private static PullWorkerSession session(String connectionId,
                                             DeliveryPullChannel deliveryPullChannel,
                                             TransportResultIngressChannel resultIngestChannel,
                                             InMemoryTransportEndpointLeaseStore endpointLeaseStore,
                                             WorkerPresenceIngress presenceIngress) {
        return new PullWorkerSession(
                "worker-1",
                "group-1",
                "polling",
                connectionId,
                deliveryPullChannel,
                resultIngestChannel,
                endpointLeaseStore,
                presenceIngress,
                "polling"
        );
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
                    + event.routeKey() + ":"
                    + event.sessionToken() + ":"
                    + event.reason() + ":"
                    + event.traceId();
        }
    }

    private static String routeKey() {
        return CanonicalWorkerGroupRouteKeyCodec.encode("group-1");
    }

    private static PulledTaskDispatch item(String messageId) {
        return new PulledTaskDispatch(
                correlation(messageId, "attempt-" + messageId),
                "crawler.fetch-page",
                Map.of("target", "target-1"),
                Map.of()
        );
    }

    private static PulledDeliveryMessage message(String messageId) {
        return new PulledDeliveryMessage(
                "delivery-" + messageId,
                "worker-1",
                """
                {
                  "resultCorrelationRef": "%s",
                  "eventCode": "crawler.fetch-page",
                  "input": {"target": "target-1"},
                  "sharedConfig": {}
                }
                """.formatted(correlation(messageId, "attempt-" + messageId)),
                correlation(messageId, "attempt-" + messageId),
                1L
        );
    }

    private static String correlation(String messageId, String attemptId) {
        return "corr-" + messageId + "-" + attemptId;
    }
}
