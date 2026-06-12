package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.runtime.queue.KeyedQueueEntry;
import com.xa.mass.transport.model.TaskDispatchContent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

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
                new TaskDispatchContent(
                        "task-1",
                        "msg-1",
                        "crawler.fetch-page",
                        Map.of("target", "https://example.test"),
                        Map.of("debug", true)
                ),
                "attempt-1",
                2,
                1,
                "batch-1",
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
        assertEquals("task-1", decoded.value().content().taskId());
        assertEquals("msg-1", decoded.value().content().messageId());
        assertEquals("attempt-1", decoded.value().attemptId());
        assertEquals(2, decoded.value().attemptNo());
        assertEquals(1, decoded.value().retryCount());
        assertEquals("batch-1", decoded.value().batchId());
        assertEquals(Map.of("target", "https://example.test"), decoded.value().content().input());
    }
}
