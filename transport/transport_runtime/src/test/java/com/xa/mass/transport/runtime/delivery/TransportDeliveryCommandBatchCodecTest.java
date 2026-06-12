package com.xa.mass.transport.runtime.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TransportDeliveryCommandBatchCodecTest {

    @Test
    void encodesCommandBatchWithoutTopLevelRouteKeyOrNestedTaskBatchJson() {
        DeliveryCommandBatch batch = DeliveryCommandFixtures.batch(
                "node-1",
                DeliveryCommandFixtures.command("msg-1", "worker-1", "node-1", "route-a"),
                DeliveryCommandFixtures.command("msg-2", "worker-2", "node-1", "route-b")
        );
        TransportDeliveryCommandBatchCodec codec = new TransportDeliveryCommandBatchCodec();

        String json = codec.encode(batch);
        DeliveryCommandBatch decoded = codec.decode(json);

        assertFalse(json.contains("taskBatchJson"), json);
        assertFalse(json.contains("\"routeKey\":\"route-a\",\"targetTransportNodeId\""), json);
        assertEquals("websocket", decoded.deliveryQueueKey());
        assertEquals("node-1", decoded.targetTransportNodeId());
        assertEquals("route-a", decoded.commands().get(0).getRouteKey());
        assertEquals("route-b", decoded.commands().get(1).getRouteKey());
        assertEquals("worker-1", decoded.commands().get(0).getSelectedWorkerId());
        assertEquals("worker-2", decoded.commands().get(1).getSelectedWorkerId());
    }
}
