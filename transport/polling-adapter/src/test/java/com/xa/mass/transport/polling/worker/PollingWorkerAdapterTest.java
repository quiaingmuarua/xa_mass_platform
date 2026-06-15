package com.xa.mass.transport.polling.worker;

import com.xa.mass.transport.channel.PulledTaskDispatch;
import com.xa.mass.transport.channel.TaskPullStatus;
import com.xa.mass.transport.model.AdapterDispatchRequest;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TaskDispatchContent;
import com.xa.mass.transport.model.TaskDispatchExecutionContext;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDeliveryStore;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandConsumerClaim;
import com.xa.mass.transport.runtime.delivery.DeliveryCommandConsumerRegistry;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
import com.xa.mass.transport.runtime.route.InMemoryTransportRouteOwnerStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollingWorkerAdapterTest {

    @Test
    void dispatchQueuesItemsForPollingWorker() {
        PollingWorkerAdapter adapter = adapter();

        List<DispatchOutcome> outcomes = adapter.dispatch(List.of(request("msg-1", "worker-1")));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.QUEUED, outcomes.get(0).getStatus());
        assertEquals(TaskPullStatus.DELIVERED, adapter.pollTaskMessagesResult("worker-1", 1, 0).getStatus());
        adapter.dispatch(List.of(request("msg-2", "worker-1")));
        assertEquals(List.of("msg-2"), adapter.pollTaskMessages("worker-1", 10, 0).stream()
                .map(PulledTaskDispatch::getMessageId)
                .toList());
    }

    @Test
    void dispatchRejectsMissingWorkerIdAsInvalidItem() {
        PollingWorkerAdapter adapter = adapter();

        List<DispatchOutcome> outcomes = adapter.dispatch(java.util.Collections.singletonList(null));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.INVALID, outcomes.get(0).getStatus());
        assertTrue(adapter.pollTaskMessages("worker-1", 10, 0).isEmpty());
        assertEquals(TaskPullStatus.EMPTY, adapter.pollTaskMessagesResult("worker-1", 10, 0).getStatus());
    }

    @Test
    void sharedRouteAndDeliveryQueueDoNotCrossConsumeSelectedWorkerItems() {
        PollingWorkerAdapter adapter = adapter();
        adapter.dispatch(List.of(request("msg-2", "worker-2")));

        assertEquals(TaskPullStatus.EMPTY, adapter.pollTaskMessagesResult("worker-1", 10, 0).getStatus());
        assertEquals(List.of("msg-2"), adapter.pollTaskMessages("worker-2", 10, 0).stream()
                .map(PulledTaskDispatch::getMessageId)
                .toList());
    }

    @Test
    void dispatchReportsBackpressureWhenWorkerInboxIsFull() {
        PollingWorkerAdapter adapter = adapter();
        List<AdapterDispatchRequest> items = new ArrayList<>();
        for (int i = 0; i < PollingWorkerAdapter.MAX_INBOX_SIZE + 1; i++) {
            items.add(request("msg-" + i, "worker-1"));
        }

        List<DispatchOutcome> outcomes = adapter.dispatch(items);

        assertEquals(PollingWorkerAdapter.MAX_INBOX_SIZE + 1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.BACKPRESSURE,
                outcomes.get(outcomes.size() - 1).getStatus());
        assertTrue(outcomes.get(outcomes.size() - 1).isRetryable());
        assertEquals(PollingWorkerAdapter.MAX_INBOX_SIZE,
                adapter.pollTaskMessages("worker-1", PollingWorkerAdapter.MAX_INBOX_SIZE + 10, 0).size());
    }

    @Test
    void pollResultPreservesDeliveredAndInvalidRequestStatuses() {
        PollingWorkerAdapter adapter = adapter();
        adapter.dispatch(List.of(request("msg-1", "worker-1")));

        assertEquals(TaskPullStatus.DELIVERED, adapter.pollTaskMessagesResult("worker-1", 1, 0).getStatus());
        assertEquals(TaskPullStatus.INVALID_REQUEST, adapter.pollTaskMessagesResult(" ", 10, 0).getStatus());
        assertEquals(TaskPullStatus.INVALID_REQUEST, adapter.pollTaskMessagesResult("worker-1", 0, 0).getStatus());
    }

    @Test
    void routeOwnerAnnouncementsUpdateTransportOwnedRouteOwnerLease() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore(30_000L, "poll-node-1");
        PollingWorkerAdapter adapter = adapter(routeOwnerStore);

        adapter.announceWorkerOnline("worker-1", "bucket-1", "route-1", "conn-1", "poll connected");

        assertTrue(routeOwnerStore.endpointForSelectedWorker("bucket-1", "worker-1")
                .orElseThrow()
                .isActive(System.currentTimeMillis()));
        assertEquals("poll-node-1", routeOwnerStore.targetForSelectedWorker("bucket-1", "worker-1")
                .orElseThrow()
                .targetTransportNodeId());
        assertTrue(routeOwnerStore.hasActiveRouteOwner(PollingWorkerAdapter.PROTOCOL, "route-1"));

        adapter.refreshRouteOwnerHeartbeat("worker-1", "bucket-1", "route-1", "conn-1", "poll heartbeat");
        assertTrue(routeOwnerStore.endpointForSelectedWorker("bucket-1", "worker-1")
                .orElseThrow()
                .isActive(System.currentTimeMillis()));

        adapter.announceWorkerOffline("worker-1", "bucket-1", "route-1", "conn-1", "poll disconnect");

        assertTrue(routeOwnerStore.endpointForSelectedWorker("bucket-1", "worker-1").isEmpty());
        assertTrue(routeOwnerStore.currentOwners("route-1").isEmpty());
    }

    @Test
    void routeOwnerAnnouncementsClaimAndReleaseSelectedWorkerConsumerEvidence() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore(30_000L, "poll-node-1");
        RecordingConsumerRegistry registry = new RecordingConsumerRegistry();
        PollingWorkerAdapter adapter = new PollingWorkerAdapter(
                routeOwnerStore,
                deliveryService(),
                registry,
                "poll-node-1"
        );

        adapter.announceWorkerOnline("worker-1", "bucket-1", "route-1", "conn-1", "poll connected");
        adapter.announceWorkerOffline("worker-1", "bucket-1", "route-1", "conn-1", "poll disconnect");

        assertEquals("bucket-1", registry.claimed.deliveryBucketId());
        assertEquals("worker-1", registry.claimed.selectedWorkerId());
        assertEquals("poll-node-1", registry.claimed.queueConsumerKey());
        assertEquals("conn-1", registry.claimed.consumerEvidenceId());
        assertEquals(PollingWorkerAdapter.PROTOCOL, registry.claimed.adapterId());
        assertEquals("conn-1", registry.released.consumerEvidenceId());
    }

    private PollingWorkerAdapter adapter() {
        return adapter(new InMemoryTransportRouteOwnerStore());
    }

    private PollingWorkerAdapter adapter(InMemoryTransportRouteOwnerStore routeOwnerStore) {
        return new PollingWorkerAdapter(
                routeOwnerStore,
                deliveryService()
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

    private AdapterDispatchRequest request(String messageId, String workerId) {
        return request("delivery-" + messageId, messageId, workerId);
    }

    private AdapterDispatchRequest request(String deliveryId, String messageId, String workerId) {
        return new AdapterDispatchRequest(
                deliveryId,
                PollingWorkerAdapter.PROTOCOL,
                workerId,
                new TaskDispatchContent(
                        "task-1",
                        messageId,
                        "crawler.fetch-page",
                        Map.of("target", "target-1"),
                        Map.of()
                ),
                new TaskDispatchExecutionContext("attempt-" + messageId, 1, 0, "batch-1"),
                1L
        );
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

