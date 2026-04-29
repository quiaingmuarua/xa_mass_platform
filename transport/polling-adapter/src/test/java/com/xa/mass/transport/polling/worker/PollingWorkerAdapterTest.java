package com.xa.mass.transport.polling.worker;

import com.xa.mass.transport.channel.NoopWorkerSystemEventChannel;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.runtime.delivery.InMemoryTransportDeliveryStore;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryService;
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
        TaskDispatchItem item = item("msg-1", "worker-1");

        List<DispatchOutcome> outcomes = adapter.dispatchEnvelopes(List.of(envelope(item)));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.QUEUED, outcomes.get(0).getStatus());
        assertEquals(List.of(item), adapter.pollTaskMessages("worker-1", 10, 0));
    }

    @Test
    void dispatchRejectsMissingWorkerIdAsInvalidItem() {
        PollingWorkerAdapter adapter = adapter();

        List<DispatchOutcome> outcomes = adapter.dispatchEnvelopes(List.of(invalidEnvelope(item("msg-1", null))));

        assertEquals(1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.INVALID_ITEM, outcomes.get(0).getStatus());
        assertTrue(adapter.pollTaskMessages("worker-1", 10, 0).isEmpty());
    }

    @Test
    void dispatchReportsBackpressureWhenWorkerInboxIsFull() {
        PollingWorkerAdapter adapter = adapter();
        List<TransportDispatchEnvelope> items = new ArrayList<>();
        for (int i = 0; i < PollingWorkerAdapter.MAX_INBOX_SIZE + 1; i++) {
            items.add(envelope(item("msg-" + i, "worker-1")));
        }

        List<DispatchOutcome> outcomes = adapter.dispatchEnvelopes(items);

        assertEquals(PollingWorkerAdapter.MAX_INBOX_SIZE + 1, outcomes.size());
        assertEquals(DispatchOutcomeStatus.BACKPRESSURE_REJECTED,
                outcomes.get(outcomes.size() - 1).getStatus());
        assertTrue(outcomes.get(outcomes.size() - 1).isRetryable());
        assertEquals(PollingWorkerAdapter.MAX_INBOX_SIZE,
                adapter.pollTaskMessages("worker-1", PollingWorkerAdapter.MAX_INBOX_SIZE + 10, 0).size());
    }

    private PollingWorkerAdapter adapter() {
        return new PollingWorkerAdapter(
                NoopWorkerSystemEventChannel.INSTANCE,
                new TransportDeliveryService(new InMemoryTransportDeliveryStore())
        );
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

    private TransportDispatchEnvelope envelope(TaskDispatchItem item) {
        return new TransportDispatchEnvelope(
                "delivery-" + item.getMessageId(),
                PollingWorkerAdapter.PROTOCOL,
                item.getWorkerId(),
                item.attemptId(),
                item,
                1L
        );
    }

    private TransportDispatchEnvelope invalidEnvelope(TaskDispatchItem item) {
        return new TransportDispatchEnvelope(
                "delivery-" + item.getMessageId(),
                PollingWorkerAdapter.PROTOCOL,
                " ",
                item.attemptId(),
                item,
                1L
        );
    }
}
