package com.xa.mass.transport.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchOutcomeTest {

    @Test
    void sentCopiesDispatchIdentityAndNormalizesAdapterId() {
        TaskDispatchItem item = item();

        DispatchOutcome outcome = DispatchOutcome.sent(" WebSocket ", item);

        assertEquals("websocket", outcome.getAdapterId());
        assertEquals("worker-1", outcome.getWorkerId());
        assertEquals("task-1", outcome.getTaskId());
        assertEquals("msg-1", outcome.getMessageId());
        assertEquals(DispatchOutcomeStatus.SENT, outcome.getStatus());
        assertFalse(outcome.isRetryable());
        assertNull(outcome.getReason());
    }

    @Test
    void factoryMethodsSetRetryabilityDefaults() {
        TaskDispatchItem item = item();

        assertFalse(DispatchOutcome.queued("polling", item).isRetryable());
        assertTrue(DispatchOutcome.endpointOffline("websocket", item, "offline").isRetryable());
        assertTrue(DispatchOutcome.backpressureRejected("polling", item, "full").isRetryable());
        assertFalse(DispatchOutcome.invalid("polling", item, "bad").isRetryable());
        assertTrue(DispatchOutcome.adapterUnavailable("socket", item, "missing").isRetryable());
        assertFalse(DispatchOutcome.failed("socket", item, "bad frame", false).isRetryable());
        assertTrue(DispatchOutcome.failed("socket", item, "io", true).isRetryable());
    }

    @Test
    void invalidOutcomeToleratesNullItem() {
        DispatchOutcome outcome = DispatchOutcome.invalid(null, null, "missing item");

        assertNull(outcome.getAdapterId());
        assertNull(outcome.getWorkerId());
        assertNull(outcome.getTaskId());
        assertNull(outcome.getMessageId());
        assertEquals(DispatchOutcomeStatus.INVALID_ITEM, outcome.getStatus());
        assertEquals("missing item", outcome.getReason());
    }

    private TaskDispatchItem item() {
        return new TaskDispatchItem(
                "task-1",
                "msg-1",
                "crawler.fetch-page",
                "task-name",
                "demoApp",
                "agent",
                0,
                "worker-1",
                "ctx-1",
                "batch-1",
                Map.of("target", "target-1"),
                Map.of()
        );
    }
}
