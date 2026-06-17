package com.xa.mass.transport.polling.worker;

import com.xa.mass.transport.channel.DeliveryPullStatus;
import com.xa.mass.transport.channel.PulledDeliveryMessage;
import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDeliveryStore;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandConsumerClaim;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.NoopDeliveryCommandConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
import com.xa.mass.transport.runtime.lease.InMemoryTransportEndpointLeaseStore;
import com.xa.mass.transport.polling.runtime.DefaultWorkerTransportRuntimeFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollingWorkerAdapterTest {

    private static final String BUCKET = "bucket-1";

    @Test
    void dispatchQueuesItemsForPollingWorker() {
        PollingWorkerAdapter adapter = adapter();

        List<DispatchOutcome> outcomes = adapter.dispatch(List.of(request("msg-1", "worker-1")));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.QUEUED, outcomes.get(0).getStatus());
        assertEquals(DeliveryPullStatus.DELIVERED,
                adapter.pollDeliveryMessagesResult(BUCKET, "worker-1", 1, 0).getStatus());
        adapter.dispatch(List.of(request("msg-2", "worker-1")));
        assertEquals(List.of("msg-2"), adapter.pollDeliveryMessages(BUCKET, "worker-1", 10, 0).stream()
                .map(PulledDeliveryMessage::getPayload)
                .map(this::messageId)
                .toList());
    }

    @Test
    void dispatchRejectsMissingWorkerIdAsInvalidItem() {
        PollingWorkerAdapter adapter = adapter();

        List<DispatchOutcome> outcomes = adapter.dispatch(java.util.Collections.singletonList(null));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.INVALID, outcomes.get(0).getStatus());
        assertTrue(adapter.pollDeliveryMessages(BUCKET, "worker-1", 10, 0).isEmpty());
        assertEquals(DeliveryPullStatus.EMPTY,
                adapter.pollDeliveryMessagesResult(BUCKET, "worker-1", 10, 0).getStatus());
    }

    @Test
    void sharedRouteAndDeliveryQueueDoNotCrossConsumeSelectedWorkerItems() {
        PollingWorkerAdapter adapter = adapter();
        adapter.dispatch(List.of(request("msg-2", "worker-2")));

        assertEquals(DeliveryPullStatus.EMPTY,
                adapter.pollDeliveryMessagesResult(BUCKET, "worker-1", 10, 0).getStatus());
        assertEquals(List.of("msg-2"), adapter.pollDeliveryMessages(BUCKET, "worker-2", 10, 0).stream()
                .map(PulledDeliveryMessage::getPayload)
                .map(this::messageId)
                .toList());
    }

    @Test
    void dispatchReportsBackpressureWhenWorkerInboxIsFull() {
        PollingWorkerAdapter adapter = adapter();
        List<DeliveryCommand> items = new ArrayList<>();
        for (int i = 0; i < PollingWorkerAdapter.MAX_INBOX_SIZE + 1; i++) {
            items.add(request("msg-" + i, "worker-1"));
        }

        List<DispatchOutcome> outcomes = adapter.dispatch(items);

        assertEquals(PollingWorkerAdapter.MAX_INBOX_SIZE + 1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.BACKPRESSURE,
                outcomes.get(outcomes.size() - 1).getStatus());
        assertTrue(outcomes.get(outcomes.size() - 1).isRetryable());
        assertEquals(PollingWorkerAdapter.MAX_INBOX_SIZE,
                adapter.pollDeliveryMessages(BUCKET, "worker-1", PollingWorkerAdapter.MAX_INBOX_SIZE + 10, 0).size());
    }

    @Test
    void pollResultPreservesDeliveredAndInvalidRequestStatuses() {
        PollingWorkerAdapter adapter = adapter();
        adapter.dispatch(List.of(request("msg-1", "worker-1")));

        assertEquals(DeliveryPullStatus.DELIVERED,
                adapter.pollDeliveryMessagesResult(BUCKET, "worker-1", 1, 0).getStatus());
        assertEquals(DeliveryPullStatus.INVALID_REQUEST,
                adapter.pollDeliveryMessagesResult(BUCKET, " ", 10, 0).getStatus());
        assertEquals(DeliveryPullStatus.INVALID_REQUEST,
                adapter.pollDeliveryMessagesResult(BUCKET, "worker-1", 0, 0).getStatus());
    }

    @Test
    void endpointLeaseAnnouncementsUpdateTransportOwnedEndpointLease() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore =
                new InMemoryTransportEndpointLeaseStore(30_000L);
        PollingWorkerAdapter adapter = adapter(endpointLeaseStore);

        adapter.announceWorkerOnline("worker-1", "bucket-1", "route-1", "conn-1", "poll connected");

        var connected = endpointLeaseStore.currentEndpointLease("bucket-1", "worker-1").orElseThrow();
        assertEquals("route-1", connected.endpointAddress());
        assertEquals("conn-1", connected.endpointLeaseId());

        adapter.refreshEndpointLeaseHeartbeat("worker-1", "bucket-1", "route-1", "conn-1", "poll heartbeat");
        assertTrue(endpointLeaseStore.currentEndpointLease("bucket-1", "worker-1").isPresent());

        adapter.announceWorkerOffline("worker-1", "bucket-1", "route-1", "conn-1", "poll disconnect");

        assertTrue(endpointLeaseStore.currentEndpointLease("bucket-1", "worker-1").isEmpty());
    }

    @Test
    void endpointLeaseAnnouncementsClaimAndReleaseSelectedWorkerConsumerEvidence() {
        InMemoryTransportEndpointLeaseStore endpointLeaseStore =
                new InMemoryTransportEndpointLeaseStore(30_000L);
        RecordingConsumerRegistry registry = new RecordingConsumerRegistry();
        PollingWorkerAdapter adapter = new PollingWorkerAdapter(
                endpointLeaseStore,
                deliveryService(),
                registry,
                "polling-worker-a"
        );

        adapter.announceWorkerOnline("worker-1", "bucket-1", "route-1", "conn-1", "poll connected");
        adapter.announceWorkerOffline("worker-1", "bucket-1", "route-1", "conn-1", "poll disconnect");

        assertEquals("bucket-1", registry.claimed.deliveryBucketId());
        assertEquals("worker-1", registry.claimed.selectedWorkerId());
        assertEquals("conn-1", registry.claimed.endpointLeaseId());
        assertEquals("conn-1", registry.released.endpointLeaseId());
    }

    private PollingWorkerAdapter adapter() {
        return adapter(new InMemoryTransportEndpointLeaseStore());
    }

    private PollingWorkerAdapter adapter(InMemoryTransportEndpointLeaseStore endpointLeaseStore) {
        return new PollingWorkerAdapter(
                endpointLeaseStore,
                deliveryService(),
                NoopDeliveryCommandConsumerRegistry.INSTANCE,
                DefaultWorkerTransportRuntimeFactory.DEFAULT_ADAPTER_ID
        );
    }

    private TransportDeliveryService deliveryService() {
        return new TransportDeliveryService(
                new InMemoryTransportDeliveryStore(
                        InMemoryTransportDeliveryStore.DEFAULT_MAX_QUEUED_ITEMS,
                        PollingWorkerAdapter.MAX_INBOX_SIZE
                )
        );
    }

    private DeliveryCommand request(String messageId, String workerId) {
        return request("delivery-" + messageId, messageId, workerId);
    }

    private DeliveryCommand request(String deliveryId, String messageId, String workerId) {
        return new DeliveryCommand(
                deliveryId,
                BUCKET,
                workerId,
                payload(messageId),
                "corr-" + messageId,
                0L,
                1L
        );
    }

    private String payload(String messageId) {
        return "{\"messageId\":\"" + messageId + "\"}";
    }

    private String messageId(String payload) {
        return payload.replace("{\"messageId\":\"", "").replace("\"}", "");
    }

    private static final class RecordingConsumerRegistry implements DeliveryCommandConsumerRegistry {

        private DeliveryCommandConsumerClaim claimed;
        private DeliveryCommandConsumerClaim released;

        @Override
        public void claimConsumer(DeliveryCommandConsumerClaim claim) {
            this.claimed = claim;
        }

        @Override
        public void releaseConsumer(DeliveryCommandConsumerClaim claim) {
            this.released = claim;
        }
    }
}

