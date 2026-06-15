package com.xa.mass.transport.runtime.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TransportDeliveryCommandBatchCodecTest {

    @Test
    void encodesCommandBatchWithoutTopLevelRouteKeyOrNestedTaskBatchJson() {
        DeliveryCommandBatch batch = DeliveryCommandFixtures.batch(
                "node-1",
                DeliveryCommandFixtures.command("msg-1", "worker-1", null),
                DeliveryCommandFixtures.command("msg-2", "worker-2", null)
        );
        TransportDeliveryCommandBatchCodec codec = new TransportDeliveryCommandBatchCodec();

        String json = codec.encode(batch);
        DeliveryCommandBatch decoded = codec.decode(json);

        assertFalse(json.contains("taskBatchJson"), json);
        assertFalse(json.contains("\"payload\""), json);
        assertFalse(json.contains("\"correlation\""), json);
        assertFalse(json.contains("\"connectionToken\""), json);
        assertFalse(json.contains("\"routeKey\""), json);
        assertFalse(json.contains("\"connectionId\""), json);
        assertFalse(json.contains("\"leaseExpireAtEpochMillis\""), json);
        assertEquals(0, occurrences(json, "\"adapterId\""));
        assertEquals(0, occurrences(json, "\"deliveryQueueKey\""));
        assertEquals(1, occurrences(json, "\"deliveryBucketId\""));
        assertEquals(1, occurrences(json, "\"deliveryLaneKey\""));
        assertEquals(1, occurrences(json, "\"targetTransportNodeId\""));
        assertEquals("bucket-1", decoded.deliveryBucketId());
        assertEquals("bucket-1", decoded.deliveryLaneKey());
        assertEquals("node-1", decoded.targetTransportNodeId());
        assertEquals("msg-1", decoded.commands().get(0).getContent().messageId());
        assertEquals("msg-2", decoded.commands().get(1).getContent().messageId());
        assertEquals("worker-1", decoded.commands().get(0).getSelectedWorkerId());
        assertEquals("worker-2", decoded.commands().get(1).getSelectedWorkerId());
    }

    private static int occurrences(String value, String token) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
