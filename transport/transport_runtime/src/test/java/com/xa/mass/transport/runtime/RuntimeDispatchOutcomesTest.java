package com.xa.mass.transport.runtime;

import com.xa.mass.transport.model.DispatchOutcome;
import com.xa.mass.transport.model.DispatchOutcomeStatus;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;
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
                List.of(envelope("msg-1", "worker-1"), invalidEnvelope("msg-2", " ")),
                "dispatch channel is unavailable"
        );

        assertEquals(2, outcomes.size());
        assertEquals(DispatchOutcomeStatus.UNAVAILABLE, outcomes.get(0).getStatus());
        assertEquals("dispatch channel is unavailable", outcomes.get(0).getReason());
        assertTrue(outcomes.get(0).isRetryable());
        assertEquals(DispatchOutcomeStatus.INVALID, outcomes.get(1).getStatus());
        assertEquals("routeKey must not be blank", outcomes.get(1).getReason());
    }

    @Test
    void adapterUnavailableReturnsEmptyForNoItems() {
        assertTrue(RuntimeDispatchOutcomes.adapterUnavailable("socket", null, "missing").isEmpty());
        assertTrue(RuntimeDispatchOutcomes.adapterUnavailable("socket", List.of(), "missing").isEmpty());
    }

    private TransportDispatchEnvelope envelope(String messageId, String workerId) {
        return envelope("delivery-" + messageId, "websocket", "group-route-1", "attempt-" + messageId, messageId, workerId);
    }

    private TransportDispatchEnvelope envelope(String deliveryId,
                                              String adapterId,
                                              String routeKey,
                                              String traceId,
                                              String messageId,
                                              String workerId) {
        return new TransportDispatchEnvelope(
                deliveryId,
                workerId,
                new TransportPacket(
                        TransportPacket.CURRENT_VERSION,
                        deliveryId,
                        traceId,
                        PacketType.TASK_DISPATCH,
                        adapterId,
                        routeKey,
                        "task-1",
                        messageId,
                        traceId,
                        "crawler.fetch-page",
                        TransportPacket.JSON_CONTENT_TYPE,
                        Map.of(
                                TransportPacket.PAYLOAD_WORKER_ID, workerId == null ? "" : workerId,
                                TransportPacket.PAYLOAD_INPUT, Map.of("target", "target-1"),
                                TransportPacket.PAYLOAD_SHARED_CONFIG, Map.of()
                        )
                ),
                1L
        );
    }

    private TransportDispatchEnvelope invalidEnvelope(String messageId, String workerId) {
        return envelope("delivery-" + messageId, "websocket", " ", "attempt-" + messageId, messageId, workerId);
    }
}

