package com.xa.mass.sdk.worker;

import com.xa.mass.transport.channel.DeliveryPullChannel;
import com.xa.mass.transport.channel.DeliveryPullResult;
import com.xa.mass.transport.channel.PulledDeliveryMessage;
import com.xa.mass.transport.channel.ResultIngressEntry;
import com.xa.mass.transport.channel.TransportResultIngressChannel;
import com.xa.mass.transport.channel.WorkerPresenceIngress;
import com.xa.mass.transport.channel.WorkerSessionPresenceEvent;
import com.xa.mass.transport.polling.runtime.PollingSessionEvidenceDriver;
import com.xa.mass.transport.runtime.embedded.PullSessionEvidenceDriver;
import com.xa.mass.transport.runtime.lease.AdapterSessionEvidencePublisher;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import com.xa.mass.worker.runtime.resource.WorkerHeartbeatRuntime;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddedPullWorkerSessionTest {

    @Test
    void pollResultDelegatesToDeliveryPullChannelWithRegisteredWorkerId() {
        DeliveryPullChannel deliveryPullChannel = mock(DeliveryPullChannel.class);
        DeliveryPullResult expected = DeliveryPullResult.delivered(List.of(message("msg-1")));
        when(deliveryPullChannel.pollDeliveryMessagesResult("group-1", "worker-1", 5, 250L)).thenReturn(expected);

        EmbeddedPullWorkerSession session = session(deliveryPullChannel, mock(TransportResultIngressChannel.class),
                new InMemoryTransportEndpointLeaseStore());

        WorkerPollResult result = session.pollResult(5, 250L);

        assertEquals(WorkerPollStatus.DELIVERED, result.getStatus());
        assertEquals("group-1", session.workerGroupId());
        assertEquals(List.of(correlation("msg-1", "attempt-msg-1")),
                result.getItems().stream().map(WorkerAction::getReplyRef).toList());
    }

    @Test
    void pollReturnsPulledTaskItemsFromExplicitPullResult() {
        DeliveryPullChannel deliveryPullChannel = mock(DeliveryPullChannel.class);
        when(deliveryPullChannel.pollDeliveryMessagesResult("group-1", "worker-1", 3, 100L))
                .thenReturn(DeliveryPullResult.delivered(List.of(message("msg-1"), message("msg-2"))));

        EmbeddedPullWorkerSession session = session(deliveryPullChannel, mock(TransportResultIngressChannel.class),
                new InMemoryTransportEndpointLeaseStore());

        List<WorkerAction> items = session.poll(3, 100L);

        assertEquals(List.of(correlation("msg-1", "attempt-msg-1"), correlation("msg-2", "attempt-msg-2")),
                items.stream().map(WorkerAction::getReplyRef).toList());
    }

    @Test
    void submitResultUsesOpaqueCorrelationWithoutTransportDiagnostics() {
        TransportResultIngressChannel resultIngestChannel = mock(TransportResultIngressChannel.class);
        when(resultIngestChannel.ingest(any(ResultIngressEntry.class))).thenReturn(true);

        EmbeddedPullWorkerSession session = session(mock(DeliveryPullChannel.class), resultIngestChannel,
                new InMemoryTransportEndpointLeaseStore());

        WorkerAction item = new WorkerAction(
                "action-1",
                correlation("msg-1", "attempt-1"),
                "crawler.fetch-page",
                "{\"target\":\"target-1\"}",
                Map.of()
        );

        session.submitActionReply(item, true, "ok");

        var captured = org.mockito.ArgumentCaptor.forClass(ResultIngressEntry.class);
        verify(resultIngestChannel).ingest(captured.capture());
        assertNull(captured.getValue().diagnostics().get("routeKey"));
        assertNull(captured.getValue().diagnostics().get("adapterId"));
        assertEquals(item.getReplyRef(), captured.getValue().partitionKey());
        assertEquals(item.getReplyRef(), captured.getValue().message().resultCorrelationRef());
        assertTrue(captured.getValue().message().payload().contains("\"replyRef\":\"" + item.getReplyRef() + "\""));
    }

    @Test
    void submitResultWithCorrelationRefDoesNotExposeTransportDiagnostics() {
        TransportResultIngressChannel resultIngestChannel = mock(TransportResultIngressChannel.class);
        when(resultIngestChannel.ingest(any(ResultIngressEntry.class))).thenReturn(true);

        EmbeddedPullWorkerSession session = session(mock(DeliveryPullChannel.class), resultIngestChannel,
                new InMemoryTransportEndpointLeaseStore());

        String replyRef = correlation("msg-1", "attempt-1");
        session.submitActionReply(replyRef, true, null, "ok");

        var captured = org.mockito.ArgumentCaptor.forClass(ResultIngressEntry.class);
        verify(resultIngestChannel).ingest(captured.capture());
        assertNull(captured.getValue().diagnostics().get("routeKey"));
        assertNull(captured.getValue().diagnostics().get("adapterId"));
        assertEquals(replyRef, captured.getValue().partitionKey());
        assertEquals(replyRef, captured.getValue().message().resultCorrelationRef());
        assertTrue(captured.getValue().message().payload().contains("\"replyRef\":\"" + replyRef + "\""));
        assertTrue(captured.getValue().message().payload().contains("\"body\":\"ok\""));
    }

    @Test
    void connectHeartbeatDisconnectWritePresenceWithoutRouteKeyAndWithSessionToken() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore = new InMemoryTransportEndpointLeaseStore();
        RecordingWorkerPresenceIngress presenceIngress = new RecordingWorkerPresenceIngress();
        WorkerHeartbeatRuntime workerHeartbeatRuntime = mock(WorkerHeartbeatRuntime.class);
        EmbeddedPullWorkerSession session = session(mock(DeliveryPullChannel.class), mock(TransportResultIngressChannel.class),
                endpointLeaseStore, presenceIngress, workerHeartbeatRuntime);

        assertTrue(session.connectAndClaim("connected"));
        verify(workerHeartbeatRuntime).refreshWorkerHeartbeat(eq("worker-1"), anyLong());
        assertTrue(endpointLeaseStore.currentEndpointLease("group-1", "worker-1").isPresent());
        assertEquals("conn-1", endpointLeaseStore.currentEndpointLease("group-1", "worker-1")
                .orElseThrow()
                .endpointLeaseId());
        assertEquals(List.of("CONNECTED:worker-1:polling:null:conn-1:connected:conn-1"),
                presenceIngress.events);

        assertFalse(staleSession("stale-conn", endpointLeaseStore, new RecordingWorkerPresenceIngress())
                .disconnectIfCurrent("stale-disconnect"));
        assertTrue(endpointLeaseStore.currentEndpointLease("group-1", "worker-1").isPresent());

        assertFalse(staleSession("stale-conn", endpointLeaseStore, new RecordingWorkerPresenceIngress())
                .refreshHeartbeatIfCurrent("stale-heartbeat"));
        assertTrue(session.refreshHeartbeatIfCurrent("heartbeat"));
        verify(workerHeartbeatRuntime, org.mockito.Mockito.times(2)).refreshWorkerHeartbeat(eq("worker-1"), anyLong());
        assertTrue(endpointLeaseStore.currentEndpointLease("group-1", "worker-1").isPresent());
        assertEquals(List.of(
                        "CONNECTED:worker-1:polling:null:conn-1:connected:conn-1",
                        "HEARTBEAT:worker-1:polling:null:conn-1:heartbeat:conn-1"
                ),
                presenceIngress.events);

        assertTrue(session.disconnectIfCurrent("disconnect"));
        assertTrue(endpointLeaseStore.currentEndpointLease("group-1", "worker-1").isEmpty());
        assertEquals(List.of(
                        "CONNECTED:worker-1:polling:null:conn-1:connected:conn-1",
                        "HEARTBEAT:worker-1:polling:null:conn-1:heartbeat:conn-1",
                        "DISCONNECTED:worker-1:polling:null:conn-1:disconnect:conn-1"
                ),
                presenceIngress.events);
    }

    private static EmbeddedPullWorkerSession session(DeliveryPullChannel deliveryPullChannel,
                                             TransportResultIngressChannel resultIngestChannel,
                                             InMemoryTransportEndpointLeaseStore endpointLeaseStore) {
        return session("conn-1", deliveryPullChannel, resultIngestChannel, endpointLeaseStore,
                new RecordingWorkerPresenceIngress());
    }

    private static EmbeddedPullWorkerSession staleSession(String sessionToken,
                                                  InMemoryTransportEndpointLeaseStore endpointLeaseStore,
                                                  WorkerPresenceIngress presenceIngress) {
        return session(sessionToken, mock(DeliveryPullChannel.class), mock(TransportResultIngressChannel.class), endpointLeaseStore,
                presenceIngress);
    }

    private static EmbeddedPullWorkerSession session(DeliveryPullChannel deliveryPullChannel,
                                             TransportResultIngressChannel resultIngestChannel,
                                             InMemoryTransportEndpointLeaseStore endpointLeaseStore,
                                             WorkerPresenceIngress presenceIngress) {
        return session(deliveryPullChannel, resultIngestChannel, endpointLeaseStore, presenceIngress,
                mock(WorkerHeartbeatRuntime.class));
    }

    private static EmbeddedPullWorkerSession session(DeliveryPullChannel deliveryPullChannel,
                                             TransportResultIngressChannel resultIngestChannel,
                                             InMemoryTransportEndpointLeaseStore endpointLeaseStore,
                                             WorkerPresenceIngress presenceIngress,
                                             WorkerHeartbeatRuntime workerHeartbeatRuntime) {
        return session("conn-1", deliveryPullChannel, resultIngestChannel, endpointLeaseStore, presenceIngress,
                workerHeartbeatRuntime);
    }

    private static EmbeddedPullWorkerSession session(String sessionToken,
                                             DeliveryPullChannel deliveryPullChannel,
                                             TransportResultIngressChannel resultIngestChannel,
                                             InMemoryTransportEndpointLeaseStore endpointLeaseStore,
                                             WorkerPresenceIngress presenceIngress) {
        return session(sessionToken, deliveryPullChannel, resultIngestChannel, endpointLeaseStore, presenceIngress,
                mock(WorkerHeartbeatRuntime.class));
    }

    private static EmbeddedPullWorkerSession session(String sessionToken,
                                             DeliveryPullChannel deliveryPullChannel,
                                             TransportResultIngressChannel resultIngestChannel,
                                             InMemoryTransportEndpointLeaseStore endpointLeaseStore,
                                             WorkerPresenceIngress presenceIngress,
                                             WorkerHeartbeatRuntime workerHeartbeatRuntime) {
        return new EmbeddedPullWorkerSession(
                "worker-1",
                "group-1",
                sessionToken,
                deliveryPullChannel,
                resultIngestChannel,
                evidenceDriver(endpointLeaseStore, presenceIngress),
                workerHeartbeatRuntime,
                "polling"
        );
    }

    private static PullSessionEvidenceDriver evidenceDriver(InMemoryTransportEndpointLeaseStore endpointLeaseStore,
                                                            WorkerPresenceIngress presenceIngress) {
        return new PollingSessionEvidenceDriver(new AdapterSessionEvidencePublisher(
                "polling",
                "polling",
                endpointLeaseStore,
                presenceIngress
        ));
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

    private static WorkerAction item(String messageId) {
        return new WorkerAction(
                "action-" + messageId,
                correlation(messageId, "attempt-" + messageId),
                "crawler.fetch-page",
                "{\"target\":\"target-1\"}",
                Map.of()
        );
    }

    private static PulledDeliveryMessage message(String messageId) {
        return new PulledDeliveryMessage(
                "delivery-" + messageId,
                "worker-1",
                """
                {
                  "actionId": "action-%s",
                  "replyRef": "%s",
                  "eventCode": "crawler.fetch-page",
                  "body": "{\\"target\\":\\"target-1\\"}",
                  "sharedConfig": {}
                }
                """.formatted(messageId, correlation(messageId, "attempt-" + messageId)),
                correlation(messageId, "attempt-" + messageId),
                1L
        );
    }

    private static String correlation(String messageId, String attemptId) {
        return "corr-" + messageId + "-" + attemptId;
    }
}
