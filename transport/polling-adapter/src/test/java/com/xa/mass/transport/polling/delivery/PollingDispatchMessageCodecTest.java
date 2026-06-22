package com.xa.mass.transport.polling.delivery;

import com.xa.mass.runtime.queue.KeyedQueueEntry;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PollingDispatchMessageCodecTest {

    private final PollingDispatchMessageCodec codec = new PollingDispatchMessageCodec();

    @Test
    void keyEncodingTreatsMailboxWorkerSlotAsOpaqueKey() {
        PollingPendingDeliveryQueueKey key = new PollingPendingDeliveryQueueKey("mailbox-a\u001Fworker-1");

        String encoded = codec.encodeKeyPart(key);
        PollingPendingDeliveryQueueKey decoded = codec.decodeKeyPart(encoded);

        assertNotEquals(key.queueKey(), encoded);
        assertEquals(key.queueKey(), decoded.queueKey());
    }

    @Test
    void valueEncodingRoundTripsDispatchMessage() {
        DispatchMessage item = new DispatchMessage(
                "delivery-1",
                "worker-1",
                "{\"messageId\":\"msg-1\"}",
                "corr-1",
                0L,
                123L
        );

        String storedValue = codec.encodeStoredValue(new KeyedQueueEntry<>(item, item.createdAtEpochMillis()));
        KeyedQueueEntry<DispatchMessage> decoded = codec.decodeStoredValue(storedValue);

        assertEquals(item.deliveryId(), decoded.value().deliveryId());
        assertEquals(item.selectedWorkerId(), decoded.value().selectedWorkerId());
        assertEquals(item.payload(), decoded.value().payload());
        assertEquals(item.correlationRef(), decoded.value().correlationRef());
    }
}
