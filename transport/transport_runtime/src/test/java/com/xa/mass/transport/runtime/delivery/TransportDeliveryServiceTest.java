package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.AdapterDispatchRequest;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportDeliveryServiceTest {

    private static final String BUCKET = "bucket-1";
    private static final String QUEUE_KEY = AssignedDeliveryCommandQueueKey.queueKeyFor(BUCKET);

    @Test
    void sendDirectReturnsSentWhenSenderAcceptsItem() {
        TransportDeliveryService service = service();

        List<DispatchOutcome> outcomes = service.sendDirect(
                "websocket",
                List.of(request("msg-1", "worker-1")),
                request -> true,
                "unavailable"
        );

        assertEquals(List.of(DispatchOutcomeStatus.DELIVERED), statuses(outcomes));
        assertEquals(1L, service.stats().getDirectSentItems());
        assertEquals(1L, service.directStatsByAdapter().get("websocket").getSentItems());
    }

    @Test
    void sendDirectReturnsEndpointOfflineWhenSenderRejectsItem() {
        TransportDeliveryService service = service();

        List<DispatchOutcome> outcomes = service.sendDirect(
                "socket",
                List.of(request("msg-1", "worker-1")),
                request -> false,
                "unavailable"
        );

        assertEquals(DispatchOutcomeStatus.NO_ENDPOINT, outcomes.get(0).getStatus());
        assertTrue(outcomes.get(0).isRetryable());
        assertEquals(1L, service.stats().getDirectOfflineItems());
        assertEquals(1L, service.directStatsByAdapter().get("socket").getOfflineItems());
    }

    @Test
    void sendDirectReturnsAdapterUnavailableWhenSenderIsMissing() {
        TransportDeliveryService service = service();

        List<DispatchOutcome> outcomes = service.sendDirect(
                "websocket",
                List.of(request("msg-1", "worker-1")),
                null,
                "dispatcher context is unavailable"
        );

        assertEquals(DispatchOutcomeStatus.UNAVAILABLE, outcomes.get(0).getStatus());
        assertEquals("dispatcher context is unavailable", outcomes.get(0).getReason());
        assertEquals(1L, service.stats().getDirectUnavailableItems());
        assertEquals(1L, service.directStatsByAdapter().get("websocket").getUnavailableItems());
    }

    @Test
    void sendDirectReturnsInvalidItemBeforeCallingSender() {
        TransportDeliveryService service = service();
        AtomicBoolean called = new AtomicBoolean(false);

        List<DispatchOutcome> outcomes = service.sendDirect(
                "websocket",
                java.util.Collections.singletonList(null),
                request -> {
                    called.set(true);
                    return true;
                },
                "unavailable"
        );

        assertEquals(DispatchOutcomeStatus.INVALID, outcomes.get(0).getStatus());
        assertFalse(called.get());
        assertEquals(1L, service.stats().getDirectInvalidItems());
        assertEquals(1L, service.directStatsByAdapter().get("websocket").getInvalidItems());
    }

    @Test
    void sendDirectReturnsFailedWhenSenderThrows() {
        TransportDeliveryService service = service();

        List<DispatchOutcome> outcomes = service.sendDirect(
                "socket",
                List.of(request("msg-1", "worker-1")),
                request -> {
                    throw new IllegalStateException("write failed");
                },
                "unavailable"
        );

        assertEquals(DispatchOutcomeStatus.FAILED, outcomes.get(0).getStatus());
        assertTrue(outcomes.get(0).isRetryable());
        assertEquals("write failed", outcomes.get(0).getReason());
        assertEquals(1L, service.stats().getDirectFailedItems());
        assertEquals(1L, service.directStatsByAdapter().get("socket").getFailedItems());
    }

    @Test
    void directStatsByAdapterNormalizeAdapterIds() {
        TransportDeliveryService service = service();

        service.sendDirect(" WebSocket ", List.of(request("msg-1", "worker-1")), request -> true, "unavailable");
        service.sendDirect("websocket", List.of(request("msg-2", "worker-2")), request -> false, "unavailable");

        TransportDirectDeliveryStats stats = service.directStatsByAdapter().get("websocket");
        assertEquals(1L, stats.getSentItems());
        assertEquals(1L, stats.getOfflineItems());
    }

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
        assertEquals(0L, stats.getDirectSentItems());
        assertEquals(1, stats.getQueueByAdapter().get(QUEUE_KEY).getQueuedItems());
    }

    @Test
    void directSendDoesNotPopulateQueueStats() {
        TransportDeliveryService service = service();

        List<DispatchOutcome> outcomes = service.sendDirect(
                "websocket",
                List.of(request("msg-1", "worker-1")),
                request -> true,
                "unavailable"
        );

        TransportDeliveryServiceStats stats = service.stats();
        assertEquals(List.of(DispatchOutcomeStatus.DELIVERED), statuses(outcomes));
        assertEquals(0, stats.getQueuedItems());
        assertEquals(0, stats.getQueueCount());
        assertEquals(Map.of(), stats.getQueueByAdapter());
        assertEquals(1L, stats.getDirectSentItems());
        assertEquals(1L, service.directStatsByAdapter().get("websocket").getSentItems());
    }

    @Test
    void queuedDeliveryDoesNotPopulateDirectCounters() {
        TransportDeliveryService service = service();

        List<DispatchOutcome> outcomes = service.enqueue("polling", List.of(request("msg-1", "worker-1")));

        TransportDeliveryServiceStats stats = service.stats();
        assertEquals(List.of(DispatchOutcomeStatus.QUEUED), statuses(outcomes));
        assertEquals(1, stats.getQueuedItems());
        assertEquals(1, stats.getQueueByAdapter().get(QUEUE_KEY).getQueuedItems());
        assertEquals(0L, stats.getDirectSentItems());
        assertEquals(0L, stats.getDirectOfflineItems());
        assertEquals(0L, stats.getDirectFailedItems());
        assertEquals(0L, stats.getDirectInvalidItems());
        assertEquals(0L, stats.getDirectUnavailableItems());
        assertEquals(Map.of(), service.directStatsByAdapter());
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

    private AdapterDispatchRequest request(String messageId, String workerId) {
        return request("delivery-" + messageId, BUCKET, messageId, workerId);
    }

    private AdapterDispatchRequest request(String deliveryId,
                                           String deliveryBucketId,
                                           String messageId,
                                           String workerId) {
        return new AdapterDispatchRequest(
                deliveryId,
                deliveryBucketId,
                workerId,
                "{\"messageId\":\"" + messageId + "\"}",
                "corr-" + messageId,
                1L
        );
    }

    private String messageId(String payload) {
        return payload.replace("{\"messageId\":\"", "").replace("\"}", "");
    }
}

