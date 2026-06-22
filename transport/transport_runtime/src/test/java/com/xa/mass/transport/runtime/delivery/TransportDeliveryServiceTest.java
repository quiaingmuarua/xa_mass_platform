package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportDeliveryServiceTest {

    private static final String BUCKET = "bucket-1";
    private static final String MAILBOX = "mailbox-1";

    @Test
    void pollReturnsQueuedItems() {
        TransportDeliveryService service = service();
        service.enqueueForMailbox(MAILBOX, List.of(request("msg-1", "worker-1")));

        assertEquals(List.of("msg-1"), service.pollMailboxItemResult(MAILBOX, "worker-1", 10, 0).getItems().stream()
                .map(item -> messageId(item.payload()))
                .toList());
    }

    @Test
    void pollUsesCanonicalMailboxAndWorkerKeys() {
        TransportDeliveryService service = service();
        service.enqueueForMailbox(" " + MAILBOX + " ", List.of(request("delivery-msg-1", "msg-1", " worker-1 ")));

        assertEquals(List.of("msg-1"), service.pollMailboxItemResult(MAILBOX, "worker-1", 10, 0).getItems().stream()
                .map(item -> messageId(item.payload()))
                .toList());
    }

    @Test
    void pollingQueueKeyComesFromAdapterMailboxNotDeliveryBucket() {
        TransportDeliveryService service = service();
        service.enqueueForMailbox(MAILBOX, List.of(request("delivery-msg-1", "msg-1", "worker-1")));

        assertTrue(service.pollMailboxItemResult(BUCKET, "worker-1", 10, 0).getItems().isEmpty());
        assertEquals(List.of("msg-1"), service.pollMailboxItemResult(MAILBOX, "worker-1", 10, 0).getItems().stream()
                .map(item -> messageId(item.payload()))
                .toList());
    }

    @Test
    void selectedWorkerPollDoesNotDrainAnotherWorkerSharingRouteKey() {
        TransportDeliveryService service = service();
        service.enqueueForMailbox(MAILBOX, List.of(request("delivery-msg-1", "msg-1", "worker-2")));

        assertTrue(service.pollMailboxItemResult(MAILBOX, "worker-1", 10, 0).getItems().isEmpty());
        assertEquals(List.of("msg-1"), service.pollMailboxItemResult(MAILBOX, "worker-2", 10, 0).getItems().stream()
                .map(item -> messageId(item.payload()))
                .toList());
    }

    @Test
    void pollDispatchResultPreservesEmptyAndInvalidRequestStatuses() {
        TransportDeliveryService service = service();

        assertEquals(TransportDeliveryPollStatus.EMPTY,
                service.pollMailboxItemResult(MAILBOX, "worker-1", 10, 0).getStatus());
        assertEquals(TransportDeliveryPollStatus.INVALID_REQUEST,
                service.pollMailboxItemResult(MAILBOX, " ", 10, 0).getStatus());
    }

    @Test
    void statsExposeDeliveryStoreSnapshot() {
        TransportDeliveryService service = new TransportDeliveryService(new InMemoryTransportDeliveryStore(10));
        service.enqueueForMailbox(MAILBOX, List.of(request("msg-1", "worker-1")));

        TransportDeliveryServiceStats stats = service.stats();

        assertEquals(1, stats.getQueuedItems());
        assertEquals(1, stats.getQueueCount());
        assertEquals(10, stats.getMaxQueuedItems());
        assertTrue(stats.getOldestQueuedAgeMillis() >= 0L);
        assertEquals(1L, stats.getEnqueuedItems());
        assertEquals(0L, stats.getDrainedItems());
        assertEquals(1, stats.getQueueByAdapter().get(MAILBOX).getQueuedItems());
    }

    @Test
    void queuedDeliveryStatsRemainQueueOnly() {
        TransportDeliveryService service = service();

        List<DispatchOutcome> outcomes = service.enqueueForMailbox(MAILBOX, List.of(request("msg-1", "worker-1")));

        TransportDeliveryServiceStats stats = service.stats();
        assertEquals(List.of(DispatchOutcomeStatus.QUEUED), statuses(outcomes));
        assertEquals(1, stats.getQueuedItems());
        assertEquals(1, stats.getQueueByAdapter().get(MAILBOX).getQueuedItems());
    }

    @Test
    void shutdownStopsQueuedDelivery() {
        TransportDeliveryService service = service();
        service.enqueueForMailbox(MAILBOX, List.of(request("msg-1", "worker-1")));

        service.shutdown();

        assertEquals(0, service.stats().getQueuedItems());
        assertTrue(service.pollMailboxItemResult(MAILBOX, "worker-1", 10, 0).getItems().isEmpty());
        assertEquals(DispatchOutcomeStatus.UNAVAILABLE,
                service.enqueueForMailbox(MAILBOX, List.of(request("msg-2", "worker-1"))).get(0).getStatus());
    }

    private TransportDeliveryService service() {
        return new TransportDeliveryService(new InMemoryTransportDeliveryStore());
    }

    private List<DispatchOutcomeStatus> statuses(List<DispatchOutcome> outcomes) {
        return outcomes.stream().map(DispatchOutcome::getStatus).toList();
    }

    private DispatchRoutingItem request(String messageId, String workerId) {
        return request("delivery-" + messageId, messageId, workerId);
    }

    private DispatchRoutingItem request(String deliveryId,
                                        String messageId,
                                        String workerId) {
        return new DispatchRoutingItem(
                deliveryId,
                workerId,
                "{\"messageId\":\"" + messageId + "\"}",
                "corr-" + messageId,
                0L,
                1L
        );
    }

    private String messageId(String payload) {
        return payload.replace("{\"messageId\":\"", "").replace("\"}", "");
    }
}
