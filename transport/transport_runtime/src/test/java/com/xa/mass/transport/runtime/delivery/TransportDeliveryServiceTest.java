package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TaskDispatchItem;
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
                List.of(item("msg-1", "worker-1")),
                item -> true,
                "unavailable"
        );

        assertEquals(List.of(DispatchOutcomeStatus.SENT), statuses(outcomes));
    }

    @Test
    void sendDirectReturnsEndpointOfflineWhenSenderRejectsItem() {
        TransportDeliveryService service = service();

        List<DispatchOutcome> outcomes = service.sendDirect(
                "socket",
                List.of(item("msg-1", "worker-1")),
                item -> false,
                "unavailable"
        );

        assertEquals(DispatchOutcomeStatus.ENDPOINT_OFFLINE, outcomes.get(0).getStatus());
        assertTrue(outcomes.get(0).isRetryable());
    }

    @Test
    void sendDirectReturnsAdapterUnavailableWhenSenderIsMissing() {
        TransportDeliveryService service = service();

        List<DispatchOutcome> outcomes = service.sendDirect(
                "websocket",
                List.of(item("msg-1", "worker-1")),
                null,
                "dispatcher context is unavailable"
        );

        assertEquals(DispatchOutcomeStatus.ADAPTER_UNAVAILABLE, outcomes.get(0).getStatus());
        assertEquals("dispatcher context is unavailable", outcomes.get(0).getReason());
    }

    @Test
    void sendDirectReturnsInvalidItemBeforeCallingSender() {
        TransportDeliveryService service = service();
        AtomicBoolean called = new AtomicBoolean(false);

        List<DispatchOutcome> outcomes = service.sendDirect(
                "websocket",
                List.of(item("msg-1", null)),
                item -> {
                    called.set(true);
                    return true;
                },
                "unavailable"
        );

        assertEquals(DispatchOutcomeStatus.INVALID_ITEM, outcomes.get(0).getStatus());
        assertFalse(called.get());
    }

    @Test
    void sendDirectReturnsFailedWhenSenderThrows() {
        TransportDeliveryService service = service();

        List<DispatchOutcome> outcomes = service.sendDirect(
                "socket",
                List.of(item("msg-1", "worker-1")),
                item -> {
                    throw new IllegalStateException("write failed");
                },
                "unavailable"
        );

        assertEquals(DispatchOutcomeStatus.FAILED, outcomes.get(0).getStatus());
        assertTrue(outcomes.get(0).isRetryable());
        assertEquals("write failed", outcomes.get(0).getReason());
    }

    @Test
    void pollReturnsQueuedItems() {
        TransportDeliveryService service = service();
        TaskDispatchItem item = item("msg-1", "worker-1");
        service.enqueue("polling", List.of(item), 10);

        assertEquals(List.of(item), service.poll("polling", "worker-1", 10, 0));
    }

    @Test
    void statsExposeDeliveryStoreSnapshot() {
        TransportDeliveryService service = new TransportDeliveryService(new InMemoryTransportDeliveryStore(10));
        service.enqueue("polling", List.of(item("msg-1", "worker-1")), 10);

        TransportDeliveryStoreStats stats = service.stats();

        assertEquals(1, stats.getQueuedItems());
        assertEquals(1, stats.getQueueCount());
        assertEquals(10, stats.getMaxQueuedItems());
    }

    @Test
    void shutdownStopsQueuedDelivery() {
        TransportDeliveryService service = service();
        service.enqueue("polling", List.of(item("msg-1", "worker-1")), 10);

        service.shutdown();

        assertEquals(0, service.stats().getQueuedItems());
        assertTrue(service.poll("polling", "worker-1", 10, 0).isEmpty());
        assertEquals(DispatchOutcomeStatus.ADAPTER_UNAVAILABLE,
                service.enqueue("polling", List.of(item("msg-2", "worker-1")), 10).get(0).getStatus());
    }

    private TransportDeliveryService service() {
        return new TransportDeliveryService(new InMemoryTransportDeliveryStore());
    }

    private List<DispatchOutcomeStatus> statuses(List<DispatchOutcome> outcomes) {
        return outcomes.stream().map(DispatchOutcome::getStatus).toList();
    }

    private TaskDispatchItem item(String messageId, String workerId) {
        return new TaskDispatchItem(
                "task-1",
                messageId,
                "crawler.fetch-page",
                "task-name",
                "demoApp",
                "agent",
                0,
                workerId,
                null,
                "batch-1",
                Map.of("target", "target-1"),
                Map.of()
        );
    }
}
