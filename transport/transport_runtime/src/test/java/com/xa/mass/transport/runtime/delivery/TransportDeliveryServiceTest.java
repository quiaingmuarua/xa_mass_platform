package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportDeliveryServiceTest {

    private static final String BUCKET = "bucket-1";
    private static final String QUEUE_KEY = AssignedDeliveryCommandQueueKey.queueKeyFor(BUCKET);

    @Test
    void pollReturnsQueuedItems() {
        TransportDeliveryService service = service();
        service.enqueue("polling", List.of(request("msg-1", "worker-1")));

        assertEquals(List.of("msg-1"), service.pollItems(BUCKET, "worker-1", 10, 0).stream()
                .map(item -> messageId(item.payload()))
                .toList());
    }

    @Test
    void pollUsesCanonicalBucketAndWorkerKeys() {
        TransportDeliveryService service = service();
        service.enqueue("polling", List.of(request("delivery-msg-1", " " + BUCKET + " ", "msg-1", " worker-1 ")));

        assertEquals(List.of("msg-1"), service.pollItems(BUCKET, "worker-1", 10, 0).stream()
                .map(item -> messageId(item.payload()))
                .toList());
    }

    @Test
    void pollingQueueKeyComesFromDeliveryBucketNotAdapterId() {
        TransportDeliveryService service = service();
        service.enqueue("polling-adapter-a", List.of(request("delivery-msg-1", BUCKET, "msg-1", "worker-1")));

        assertTrue(service.pollItems("polling-adapter-a", "worker-1", 10, 0).isEmpty());
        assertEquals(List.of("msg-1"), service.pollItems(BUCKET, "worker-1", 10, 0).stream()
                .map(item -> messageId(item.payload()))
                .toList());
    }

    @Test
    void selectedWorkerPollDoesNotDrainAnotherWorkerSharingRouteKey() {
        TransportDeliveryService service = service();
        service.enqueue("polling", List.of(request("delivery-msg-1", BUCKET, "msg-1", "worker-2")));

        assertTrue(service.pollItems(BUCKET, "worker-1", 10, 0).isEmpty());
        assertEquals(List.of("msg-1"), service.pollItems(BUCKET, "worker-2", 10, 0).stream()
                .map(item -> messageId(item.payload()))
                .toList());
    }

    @Test
    void pollDispatchResultPreservesEmptyAndInvalidRequestStatuses() {
        TransportDeliveryService service = service();

        assertEquals(TransportDeliveryPollStatus.EMPTY,
                service.pollItemResult(BUCKET, "worker-1", 10, 0).getStatus());
        assertEquals(TransportDeliveryPollStatus.INVALID_REQUEST,
                service.pollItemResult(BUCKET, " ", 10, 0).getStatus());
    }

    @Test
    void statsExposeDeliveryStoreSnapshot() {
        TransportDeliveryService service = new TransportDeliveryService(new InMemoryTransportDeliveryStore(10));
        service.enqueue("polling", List.of(request("msg-1", "worker-1")));

        TransportDeliveryServiceStats stats = service.stats();

        assertEquals(1, stats.getQueuedItems());
        assertEquals(1, stats.getQueueCount());
        assertEquals(10, stats.getMaxQueuedItems());
        assertTrue(stats.getOldestQueuedAgeMillis() >= 0L);
        assertEquals(1L, stats.getEnqueuedItems());
        assertEquals(0L, stats.getDrainedItems());
        assertEquals(1, stats.getQueueByAdapter().get(QUEUE_KEY).getQueuedItems());
    }

    @Test
    void queuedDeliveryStatsRemainQueueOnly() {
        TransportDeliveryService service = service();

        List<DispatchOutcome> outcomes = service.enqueue("polling", List.of(request("msg-1", "worker-1")));

        TransportDeliveryServiceStats stats = service.stats();
        assertEquals(List.of(DispatchOutcomeStatus.QUEUED), statuses(outcomes));
        assertEquals(1, stats.getQueuedItems());
        assertEquals(1, stats.getQueueByAdapter().get(QUEUE_KEY).getQueuedItems());
    }

    @Test
    void shutdownStopsQueuedDelivery() {
        TransportDeliveryService service = service();
        service.enqueue("polling", List.of(request("msg-1", "worker-1")));

        service.shutdown();

        assertEquals(0, service.stats().getQueuedItems());
        assertTrue(service.pollItems(BUCKET, "worker-1", 10, 0).isEmpty());
        assertEquals(DispatchOutcomeStatus.UNAVAILABLE,
                service.enqueue("polling", List.of(request("msg-2", "worker-1"))).get(0).getStatus());
    }

    private TransportDeliveryService service() {
        return new TransportDeliveryService(new InMemoryTransportDeliveryStore());
    }

    private List<DispatchOutcomeStatus> statuses(List<DispatchOutcome> outcomes) {
        return outcomes.stream().map(DispatchOutcome::getStatus).toList();
    }

    private DeliveryCommand request(String messageId, String workerId) {
        return request("delivery-" + messageId, BUCKET, messageId, workerId);
    }

    private DeliveryCommand request(String deliveryId,
                                    String deliveryBucketId,
                                    String messageId,
                                    String workerId) {
        return new DeliveryCommand(
                deliveryId,
                deliveryBucketId,
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

