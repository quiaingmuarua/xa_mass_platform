package com.xa.mass.transport.polling.worker;

import com.xa.mass.transport.channel.PulledTaskDispatch;
import com.xa.mass.transport.channel.TaskPullStatus;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDeliveryStore;
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

        List<DispatchOutcome> outcomes = adapter.dispatchEnvelopes(List.of(envelope("msg-1", "worker-1")));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.QUEUED, outcomes.get(0).getStatus());
        assertEquals(TaskPullStatus.DELIVERED, adapter.pollTaskMessagesResult("worker-1", 1, 0).getStatus());
        adapter.dispatchEnvelopes(List.of(envelope("msg-2", "worker-1")));
        assertEquals(List.of("msg-2"), adapter.pollTaskMessages("worker-1", 10, 0).stream()
                .map(PulledTaskDispatch::getMessageId)
                .toList());
    }

    @Test
    void dispatchRejectsMissingWorkerIdAsInvalidItem() {
        PollingWorkerAdapter adapter = adapter();

        List<DispatchOutcome> outcomes = adapter.dispatchEnvelopes(List.of(invalidEnvelope("msg-1", null)));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.INVALID, outcomes.get(0).getStatus());
        assertTrue(adapter.pollTaskMessages("worker-1", 10, 0).isEmpty());
        assertEquals(TaskPullStatus.EMPTY, adapter.pollTaskMessagesResult("worker-1", 10, 0).getStatus());
    }

    @Test
    void sharedRouteAndDeliveryQueueDoNotCrossConsumeSelectedWorkerItems() {
        PollingWorkerAdapter adapter = adapter();
        adapter.dispatchEnvelopes(List.of(envelope("msg-2", "worker-2")));

        assertEquals(TaskPullStatus.EMPTY, adapter.pollTaskMessagesResult("worker-1", 10, 0).getStatus());
        assertEquals(List.of("msg-2"), adapter.pollTaskMessages("worker-2", 10, 0).stream()
                .map(PulledTaskDispatch::getMessageId)
                .toList());
    }

    @Test
    void dispatchReportsBackpressureWhenWorkerInboxIsFull() {
        PollingWorkerAdapter adapter = adapter();
        List<TransportDispatchEnvelope> items = new ArrayList<>();
        for (int i = 0; i < PollingWorkerAdapter.MAX_INBOX_SIZE + 1; i++) {
            items.add(envelope("msg-" + i, "worker-1"));
        }

        List<DispatchOutcome> outcomes = adapter.dispatchEnvelopes(items);

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
        adapter.dispatchEnvelopes(List.of(envelope("msg-1", "worker-1")));

        assertEquals(TaskPullStatus.DELIVERED, adapter.pollTaskMessagesResult("worker-1", 1, 0).getStatus());
        assertEquals(TaskPullStatus.INVALID_REQUEST, adapter.pollTaskMessagesResult(" ", 10, 0).getStatus());
        assertEquals(TaskPullStatus.INVALID_REQUEST, adapter.pollTaskMessagesResult("worker-1", 0, 0).getStatus());
    }

    @Test
    void routeOwnerAnnouncementsUpdateTransportOwnedRouteOwnerLease() {
        InMemoryTransportRouteOwnerStore routeOwnerStore = new InMemoryTransportRouteOwnerStore(30_000L, "poll-node-1");
        PollingWorkerAdapter adapter = adapter(routeOwnerStore);

        adapter.announceWorkerOnline("worker-1", "route-1", "conn-1", "poll connected");

        assertTrue(routeOwnerStore.activeOwnerForSelectedWorker(PollingWorkerAdapter.PROTOCOL, "worker-1")
                .orElseThrow()
                .isActive(System.currentTimeMillis()));
        assertEquals("poll-node-1", routeOwnerStore.activeOwnerForSelectedWorker(PollingWorkerAdapter.PROTOCOL, "worker-1")
                .orElseThrow()
                .transportNodeId());
        assertTrue(routeOwnerStore.hasActiveRouteOwner(PollingWorkerAdapter.PROTOCOL, "route-1"));

        adapter.refreshRouteOwnerHeartbeat("worker-1", "route-1", "conn-1", "poll heartbeat");
        assertTrue(routeOwnerStore.activeOwnerForSelectedWorker(PollingWorkerAdapter.PROTOCOL, "worker-1")
                .orElseThrow()
                .isActive(System.currentTimeMillis()));

        adapter.announceWorkerOffline("worker-1", "route-1", "conn-1", "poll disconnect");

        assertTrue(routeOwnerStore.activeOwnerForSelectedWorker(PollingWorkerAdapter.PROTOCOL, "worker-1").isEmpty());
        assertTrue(routeOwnerStore.currentOwners("route-1").isEmpty());
    }

    private PollingWorkerAdapter adapter() {
        return adapter(new InMemoryTransportRouteOwnerStore());
    }

    private PollingWorkerAdapter adapter(InMemoryTransportRouteOwnerStore routeOwnerStore) {
        return new PollingWorkerAdapter(
                routeOwnerStore,
                new TransportDeliveryService(
                        new InMemoryTransportDeliveryStore(
                                InMemoryTransportDeliveryStore.DEFAULT_MAX_QUEUED_ITEMS,
                                PollingWorkerAdapter.MAX_INBOX_SIZE
                        )
                )
        );
    }

    private TransportDispatchEnvelope envelope(String messageId, String workerId) {
        return envelope("delivery-" + messageId, "group-route-1", messageId, workerId);
    }

    private TransportDispatchEnvelope invalidEnvelope(String messageId, String workerId) {
        return envelope("delivery-" + messageId, "group-route-1", messageId, workerId);
    }

    private TransportDispatchEnvelope envelope(String deliveryId, String routeKey, String messageId, String workerId) {
        return new TransportDispatchEnvelope(
                deliveryId,
                workerId,
                new TransportPacket(
                        TransportPacket.CURRENT_VERSION,
                        deliveryId,
                        "trace-" + messageId,
                        PacketType.TASK_DISPATCH,
                        PollingWorkerAdapter.PROTOCOL,
                        routeKey,
                        "task-1",
                        messageId,
                        "attempt-" + messageId,
                        "crawler.fetch-page",
                        TransportPacket.JSON_CONTENT_TYPE,
                        Map.of(
                                TransportPacket.PAYLOAD_ATTEMPT_NO, 1,
                                TransportPacket.PAYLOAD_RETRY_COUNT, 0,
                                TransportPacket.PAYLOAD_WORKER_ID, workerId == null ? "" : workerId,
                                TransportPacket.PAYLOAD_BATCH_ID, "batch-1",
                                TransportPacket.PAYLOAD_INPUT, Map.of("target", "target-1"),
                                TransportPacket.PAYLOAD_SHARED_CONFIG, Map.of()
                        )
                ),
                1L
        );
    }
}

