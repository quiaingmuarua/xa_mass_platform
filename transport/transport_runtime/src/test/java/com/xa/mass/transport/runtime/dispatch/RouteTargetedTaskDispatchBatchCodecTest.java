package com.xa.mass.transport.runtime.dispatch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RouteTargetedTaskDispatchBatchCodecTest {

    @Test
    void encodesRouteTargetedPayloadWithoutNestedTaskBatchJson() {
        RouteTargetedTaskDispatchBatch batch = RouteTargetedDispatchFixtures.batch(
                "group-route",
                "node-1",
                RouteTargetedDispatchFixtures.delivery("group-route", "node-1", "msg-1", "worker-1")
        );
        RouteTargetedTaskDispatchBatchCodec codec = new RouteTargetedTaskDispatchBatchCodec();

        String json = codec.encode(batch);
        RouteTargetedTaskDispatchBatch decoded = codec.decode(json);

        assertFalse(json.contains("taskBatchJson"), json);
        assertEquals("group-route", decoded.routeKey());
        assertEquals("node-1", decoded.targetTransportNodeId());
        assertEquals(1, decoded.deliveryBindings().size());
        RouteTargetedTaskDispatchBinding delivery = decoded.deliveryBindings().getFirst();
        assertEquals("group-route", delivery.routeKey());
        assertEquals("websocket", delivery.adapterId());
        assertEquals("node-1", delivery.lanePartition());
        assertEquals("worker-1", delivery.selectedWorkerId());
        assertEquals("msg-1", delivery.dispatchBinding().messageId());
    }
}
