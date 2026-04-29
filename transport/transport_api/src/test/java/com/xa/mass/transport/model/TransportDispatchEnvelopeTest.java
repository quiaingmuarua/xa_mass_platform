package com.xa.mass.transport.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TransportDispatchEnvelopeTest {

    @Test
    void constructorNormalizesAdapterRouteAndCorrelationKeys() {
        TransportDispatchEnvelope envelope = new TransportDispatchEnvelope(
                "delivery-1",
                " WebSocket ",
                " worker-1 ",
                " attempt-1 ",
                item(),
                10L
        );

        assertEquals("websocket", envelope.getAdapterId());
        assertEquals("worker-1", envelope.getRouteKey());
        assertEquals("attempt-1", envelope.getCorrelationKey());
    }

    @Test
    void constructorCollapsesBlankAdapterRouteAndCorrelationKeysToNull() {
        TransportDispatchEnvelope envelope = new TransportDispatchEnvelope(
                "delivery-1",
                " ",
                " ",
                " ",
                item(),
                10L
        );

        assertNull(envelope.getAdapterId());
        assertNull(envelope.getRouteKey());
        assertNull(envelope.getCorrelationKey());
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
