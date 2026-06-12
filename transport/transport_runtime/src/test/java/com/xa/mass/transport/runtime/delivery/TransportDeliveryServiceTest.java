package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportDeliveryServiceTest {

    @Test
    void sendDirectReturnsSentWhenSenderAcceptsItem() {
        TransportDeliveryService service = service();

        List<DispatchOutcome> outcomes = service.sendDirect(
                "websocket",
                List.of(envelope("msg-1", "worker-1")),
                envelope -> true,
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
                List.of(envelope("msg-1", "worker-1")),
                envelope -> false,
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
                List.of(envelope("msg-1", "worker-1")),
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
                List.of(invalidEnvelope("msg-1", null)),
                envelope -> {
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
                List.of(envelope("msg-1", "worker-1")),
                envelope -> {
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

        service.sendDirect(" WebSocket ", List.of(envelope("msg-1", "worker-1")), envelope -> true, "unavailable");
        service.sendDirect("websocket", List.of(envelope("msg-2", "worker-2")), envelope -> false, "unavailable");

        TransportDirectDeliveryStats stats = service.directStatsByAdapter().get("websocket");
        assertEquals(1L, stats.getSentItems());
        assertEquals(1L, stats.getOfflineItems());
    }

    @Test
    void pollReturnsQueuedItems() {
        TransportDeliveryService service = service();
        service.enqueue("polling", List.of(envelope("msg-1", "worker-1")));

        assertEquals(List.of("msg-1"), service.pollEnvelopes("polling", "worker-1", 10, 0).stream()
                .map(envelope -> envelope.getPacket().messageId())
                .toList());
    }

    @Test
    void pollUsesCanonicalAdapterAndRouteKeys() {
        TransportDeliveryService service = service();
        service.enqueue("polling", List.of(envelope("delivery-msg-1", " Polling ", " group-route-1 ", "msg-1", " worker-1 ")));

        assertEquals(List.of("msg-1"), service.pollEnvelopes("polling", "worker-1", 10, 0).stream()
                .map(envelope -> envelope.getPacket().messageId())
                .toList());
    }

    @Test
    void selectedWorkerPollDoesNotDrainAnotherWorkerSharingRouteKey() {
        TransportDeliveryService service = service();
        service.enqueue("polling", List.of(envelope("delivery-msg-1", "polling", "group-route-1", "msg-1", "worker-2")));

        assertTrue(service.pollEnvelopes("polling", "worker-1", 10, 0).isEmpty());
        assertEquals(List.of("msg-1"), service.pollEnvelopes("polling", "worker-2", 10, 0).stream()
                .map(envelope -> envelope.getPacket().messageId())
                .toList());
    }

    @Test
    void pollDispatchResultPreservesEmptyAndInvalidRequestStatuses() {
        TransportDeliveryService service = service();

        assertEquals(TransportDeliveryPollStatus.EMPTY,
                service.pollEnvelopeResult("polling", "worker-1", 10, 0).getStatus());
        assertEquals(TransportDeliveryPollStatus.INVALID_REQUEST,
                service.pollEnvelopeResult("polling", " ", 10, 0).getStatus());
    }

    @Test
    void statsExposeDeliveryStoreSnapshot() {
        TransportDeliveryService service = new TransportDeliveryService(new InMemoryTransportDeliveryStore(10));
        service.enqueue("polling", List.of(envelope("msg-1", "worker-1")));

        TransportDeliveryServiceStats stats = service.stats();

        assertEquals(1, stats.getQueuedItems());
        assertEquals(1, stats.getQueueCount());
        assertEquals(10, stats.getMaxQueuedItems());
        assertTrue(stats.getOldestQueuedAgeMillis() >= 0L);
        assertEquals(1L, stats.getEnqueuedItems());
        assertEquals(0L, stats.getDrainedItems());
        assertEquals(0L, stats.getDirectSentItems());
        assertEquals(1, stats.getQueueByAdapter().get("polling").getQueuedItems());
    }

    @Test
    void directSendDoesNotPopulateQueueStats() {
        TransportDeliveryService service = service();

        List<DispatchOutcome> outcomes = service.sendDirect(
                "websocket",
                List.of(envelope("msg-1", "worker-1")),
                envelope -> true,
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

        List<DispatchOutcome> outcomes = service.enqueue("polling", List.of(envelope("msg-1", "worker-1")));

        TransportDeliveryServiceStats stats = service.stats();
        assertEquals(List.of(DispatchOutcomeStatus.QUEUED), statuses(outcomes));
        assertEquals(1, stats.getQueuedItems());
        assertEquals(1, stats.getQueueByAdapter().get("polling").getQueuedItems());
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
        service.enqueue("polling", List.of(envelope("msg-1", "worker-1")));

        service.shutdown();

        assertEquals(0, service.stats().getQueuedItems());
        assertTrue(service.pollEnvelopes("polling", "worker-1", 10, 0).isEmpty());
        assertEquals(DispatchOutcomeStatus.UNAVAILABLE,
                service.enqueue("polling", List.of(envelope("msg-2", "worker-1"))).get(0).getStatus());
    }

    private TransportDeliveryService service() {
        return new TransportDeliveryService(new InMemoryTransportDeliveryStore());
    }

    private List<DispatchOutcomeStatus> statuses(List<DispatchOutcome> outcomes) {
        return outcomes.stream().map(DispatchOutcome::getStatus).toList();
    }

    private TransportDispatchEnvelope envelope(String messageId, String workerId) {
        return envelope("delivery-" + messageId, "polling", "group-route-1", messageId, workerId);
    }

    private TransportDispatchEnvelope invalidEnvelope(String messageId, String workerId) {
        return envelope("delivery-" + messageId, "polling", " ", messageId, workerId);
    }

    private TransportDispatchEnvelope envelope(String deliveryId,
                                              String adapterId,
                                              String routeKey,
                                              String messageId,
                                              String workerId) {
        return new TransportDispatchEnvelope(
                deliveryId,
                workerId,
                new TransportPacket(
                        TransportPacket.CURRENT_VERSION,
                        deliveryId,
                        "trace-" + messageId,
                        PacketType.TASK_DISPATCH,
                        adapterId,
                        routeKey,
                        "task-1",
                        messageId,
                        "attempt-" + messageId,
                        "crawler.fetch-page",
                        TransportPacket.JSON_CONTENT_TYPE,
                        Map.of(
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

