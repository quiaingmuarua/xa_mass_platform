package com.xa.mass.transport.runtime;

import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelegatingWorkerAdapterTest {

    @Test
    void exposesConfiguredAdapterIdentity() {
        DelegatingWorkerAdapter adapter = new DelegatingWorkerAdapter(
                "websocket",
                WorkerTransportHints.REALTIME,
                items -> List.of(),
                "missing"
        );

        assertEquals("websocket", adapter.protocol());
        assertEquals("websocket", adapter.adapterId());
        assertEquals(WorkerTransportHints.REALTIME, adapter.transportHint());
    }

    @Test
    void delegatesDispatchWhenChannelExists() {
        AtomicReference<List<TransportDispatchEnvelope>> captured = new AtomicReference<>();
        DelegatingWorkerAdapter adapter = new DelegatingWorkerAdapter(
                "socket",
                WorkerTransportHints.REALTIME,
                items -> {
                    captured.set(items);
                    return items.stream()
                            .map(envelope -> DispatchOutcome.sent("socket", envelope))
                            .toList();
                },
                "missing"
        );
        List<TransportDispatchEnvelope> envelopes = List.of(envelope(item("msg-1", "worker-1")));

        List<DispatchOutcome> outcomes = adapter.dispatchEnvelopes(envelopes);

        assertEquals(envelopes, captured.get());
        assertEquals(DispatchOutcomeStatus.SENT, outcomes.get(0).getStatus());
    }

    @Test
    void returnsRuntimeFallbackOutcomesWhenChannelIsMissing() {
        DelegatingWorkerAdapter adapter = new DelegatingWorkerAdapter(
                "websocket",
                WorkerTransportHints.REALTIME,
                null,
                "dispatch channel is unavailable"
        );

        List<DispatchOutcome> outcomes = adapter.dispatchEnvelopes(List.of(
                envelope(item("msg-1", "worker-1")),
                new TransportDispatchEnvelope("delivery-2", "websocket", " ", "attempt-2", item("msg-2", null), 1L)
        ));

        assertEquals(DispatchOutcomeStatus.ADAPTER_UNAVAILABLE, outcomes.get(0).getStatus());
        assertEquals("dispatch channel is unavailable", outcomes.get(0).getReason());
        assertTrue(outcomes.get(0).isRetryable());
        assertEquals(DispatchOutcomeStatus.INVALID_ITEM, outcomes.get(1).getStatus());
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
                "socket",
                item.getWorkerId(),
                item.attemptId(),
                item,
                1L
        );
    }
}
