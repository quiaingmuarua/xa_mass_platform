package com.xa.mass.transport.runtime.delivery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TransportDispatchBatchCodecTest {

    @Test
    void encodesDispatchBatchWithoutLegacyCommandOrBucketFields() {
        AdapterMailboxDispatchBatch batch = DispatchMessageFixtures.batch(
                DispatchMessageFixtures.item("msg-1", "worker-1"),
                DispatchMessageFixtures.item("msg-2", "worker-2")
        );
        TransportDispatchBatchCodec codec = new TransportDispatchBatchCodec();

        String json = codec.encode(batch);
        AdapterMailboxDispatchBatch decoded = codec.decode(json);

        assertFalse(json.contains("taskBatchJson"), json);
        assertFalse(json.contains("TaskDispatchContentRecord"), json);
        assertFalse(json.contains("TaskDispatchExecutionContextRecord"), json);
        assertEquals(2, occurrences(json, "\"payload\""));
        assertEquals(2, occurrences(json, "\"correlationRef\""));
        assertFalse(json.contains("\"connectionToken\""), json);
        assertFalse(json.contains("\"routeKey\""), json);
        assertFalse(json.contains("\"connectionId\""), json);
        assertFalse(json.contains("\"leaseExpireAtEpochMillis\""), json);
        assertEquals(0, occurrences(json, "\"adapterId\""));
        assertEquals(0, occurrences(json, "\"deliveryQueueKey\""));
        assertEquals(1, occurrences(json, "\"adapterMailboxKey\""));
        assertEquals(0, occurrences(json, "\"deliveryBucketId\""));
        assertEquals(0, occurrences(json, "\"deliveryLaneKey\""));
        assertEquals(0, occurrences(json, "\"targetTransportNodeId\""));
        assertEquals(DispatchMessageFixtures.mailboxKey(), decoded.adapterMailboxKey());
        assertEquals("msg-1", DispatchMessageFixtures.messageId(decoded.items().get(0).payload()));
        assertEquals("msg-2", DispatchMessageFixtures.messageId(decoded.items().get(1).payload()));
        assertEquals("worker-1", decoded.items().get(0).selectedWorkerId());
        assertEquals("worker-2", decoded.items().get(1).selectedWorkerId());
        assertEquals("corr-msg-1", decoded.items().get(0).correlationRef());
        assertEquals("corr-msg-2", decoded.items().get(1).correlationRef());
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
