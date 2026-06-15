package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.runtime.queue.KeyedQueueEntry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RedisQueuedPulledDispatchCodecTest {

    private final RedisQueuedPulledDispatchCodec codec = new RedisQueuedPulledDispatchCodec();

    @Test
    void keyPartRoundTripsWithSpecialCharacters() {
        DeliveryQueueKey key = new DeliveryQueueKey("polling", "worker/route:cn?demo=1");

        String encoded = codec.encodeKeyPart(key);
        DeliveryQueueKey decoded = codec.decodeKeyPart(encoded);

        assertEquals("polling", decoded.deliveryQueueKey());
        assertEquals("worker/route:cn?demo=1", decoded.selectedWorkerId());
    }

    @Test
    void queuedDispatchEntryRoundTripsThroughJsonBytesWithoutPacketResidue() {
        QueuedPulledDispatch item = new QueuedPulledDispatch(
                "delivery-1",
                "worker-1",
                "{\"messageId\":\"msg-1\",\"input\":{\"target\":\"https://example.test\"}}",
                "corr-1",
                1_234L
        );

        byte[] encoded = codec.encodeEntry(new KeyedQueueEntry<>(item, item.createdAtEpochMillis()));
        String json = new String(encoded, StandardCharsets.UTF_8);
        assertFalse(json.contains("packet"));
        assertFalse(json.contains("routeKey"));
        assertFalse(json.contains("transportPayload"));
        assertFalse(json.contains("workerId"));
        assertFalse(json.contains("taskName"));
        assertFalse(json.contains("project"));
        assertFalse(json.contains("userId"));

        KeyedQueueEntry<QueuedPulledDispatch> decoded = codec.decodeEntry(encoded);

        assertEquals("delivery-1", decoded.value().deliveryId());
        assertEquals("worker-1", decoded.value().selectedWorkerId());
        assertEquals(1_234L, decoded.createdAtEpochMillis());
        assertEquals("{\"messageId\":\"msg-1\",\"input\":{\"target\":\"https://example.test\"}}",
                decoded.value().payload());
        assertEquals("corr-1", decoded.value().correlationRef());
    }
}
