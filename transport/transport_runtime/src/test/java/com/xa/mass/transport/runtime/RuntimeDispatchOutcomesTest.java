package com.xa.mass.transport.runtime;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.runtime.packet.TransportPacketFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeDispatchOutcomesTest {

    @Test
    void adapterUnavailableKeepsOneOutcomePerItemAndNormalizesInvalidItems() {
        List<DispatchOutcome> outcomes = RuntimeDispatchOutcomes.adapterUnavailable(
                "websocket",
                List.of(envelope(item("msg-1", "worker-1")), invalidEnvelope(item("msg-2", " "))),
                "dispatch channel is unavailable"
        );

        assertEquals(2, outcomes.size());
        assertEquals(DispatchOutcomeStatus.ADAPTER_UNAVAILABLE, outcomes.get(0).getStatus());
        assertEquals("dispatch channel is unavailable", outcomes.get(0).getReason());
        assertTrue(outcomes.get(0).isRetryable());
        assertEquals(DispatchOutcomeStatus.INVALID_ITEM, outcomes.get(1).getStatus());
        assertEquals("routeKey must not be blank", outcomes.get(1).getReason());
    }

    @Test
    void adapterUnavailableReturnsEmptyForNoItems() {
        assertTrue(RuntimeDispatchOutcomes.adapterUnavailable("socket", null, "missing").isEmpty());
        assertTrue(RuntimeDispatchOutcomes.adapterUnavailable("socket", List.of(), "missing").isEmpty());
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
                "attempt-" + messageId,
                workerId,
                "batch-1",
                Map.of("target", "target-1"),
                Map.of()
        );
    }

    private TransportDispatchEnvelope envelope(TaskDispatchItem item) {
        return envelope("delivery-" + item.getMessageId(), "websocket", "group-route-1", item.attemptId(), item);
    }

    private TransportDispatchEnvelope envelope(String deliveryId,
                                              String adapterId,
                                              String routeKey,
                                              String traceId,
                                              TaskDispatchItem item) {
        return new TransportDispatchEnvelope(
                deliveryId,
                adapterId,
                item.getWorkerId(),
                new TransportPacketFactory(() -> deliveryId)
                        .fromDispatchView(adapterId, routeKey, traceId, item),
                1L
        );
    }

    private TransportDispatchEnvelope invalidEnvelope(TaskDispatchItem item) {
        return envelope("delivery-" + item.getMessageId(), "websocket", " ", item.attemptId(), item);
    }
}

