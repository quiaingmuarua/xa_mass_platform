package com.xa.mass.transport.runtime;

import com.xa.mass.transport.WorkerTransportHints;
import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TaskDispatchItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DelegatingWorkerAdapterTest {

    @Test
    void exposesConfiguredAdapterIdentityAndAliases() {
        DelegatingWorkerAdapter adapter = new DelegatingWorkerAdapter(
                "websocket",
                WorkerTransportHints.REALTIME,
                Set.of("ws"),
                items -> List.of(),
                "missing"
        );

        assertEquals("websocket", adapter.protocol());
        assertEquals("websocket", adapter.adapterId());
        assertEquals(WorkerTransportHints.REALTIME, adapter.transportHint());
        assertEquals(Set.of("ws"), adapter.aliases());
    }

    @Test
    void delegatesDispatchWhenChannelExists() {
        AtomicReference<List<TaskDispatchItem>> captured = new AtomicReference<>();
        DelegatingWorkerAdapter adapter = new DelegatingWorkerAdapter(
                "socket",
                WorkerTransportHints.REALTIME,
                Set.of("tcp-socket"),
                items -> {
                    captured.set(items);
                    return items.stream()
                            .map(item -> DispatchOutcome.sent("socket", item))
                            .toList();
                },
                "missing"
        );
        List<TaskDispatchItem> items = List.of(item("msg-1", "worker-1"));

        List<DispatchOutcome> outcomes = adapter.dispatchTaskItems(items);

        assertEquals(items, captured.get());
        assertEquals(DispatchOutcomeStatus.SENT, outcomes.get(0).getStatus());
    }

    @Test
    void returnsRuntimeFallbackOutcomesWhenChannelIsMissing() {
        DelegatingWorkerAdapter adapter = new DelegatingWorkerAdapter(
                "websocket",
                WorkerTransportHints.REALTIME,
                Set.of("ws"),
                null,
                "dispatch channel is unavailable"
        );

        List<DispatchOutcome> outcomes = adapter.dispatchTaskItems(List.of(
                item("msg-1", "worker-1"),
                item("msg-2", null)
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
}
