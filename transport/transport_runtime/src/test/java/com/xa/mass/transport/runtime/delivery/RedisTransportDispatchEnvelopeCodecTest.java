package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.runtime.queue.KeyedQueueEntry;
import com.xa.mass.transport.model.TransportDispatchEnvelope;
import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RedisTransportDispatchEnvelopeCodecTest {

    private final RedisTransportDispatchEnvelopeCodec codec = new RedisTransportDispatchEnvelopeCodec();

    @Test
    void keyPartRoundTripsWithSpecialCharacters() {
        DeliveryQueueKey key = new DeliveryQueueKey("polling", "worker/route:cn?demo=1");

        String encoded = codec.encodeKeyPart(key);
        DeliveryQueueKey decoded = codec.decodeKeyPart(encoded);

        assertEquals("polling", decoded.deliveryQueueKey());
        assertEquals("worker/route:cn?demo=1", decoded.selectedWorkerId());
    }

    @Test
    void dispatchEnvelopeEntryRoundTripsThroughJsonBytes() {
        TransportDispatchEnvelope envelope = new TransportDispatchEnvelope(
                "delivery-1",
                "worker-1",
                new TransportPacket(
                        TransportPacket.CURRENT_VERSION,
                        "packet-1",
                        "trace-1",
                        PacketType.TASK_DISPATCH,
                        "websocket",
                        "route-1",
                        "task-1",
                        "msg-1",
                        "attempt-1",
                        "crawler.fetch-page",
                        TransportPacket.JSON_CONTENT_TYPE,
                        Map.of(
                                TransportPacket.PAYLOAD_TASK_NAME, "task-name",
                                TransportPacket.PAYLOAD_PROJECT, "demoApp",
                                TransportPacket.PAYLOAD_USER_ID, "agent",
                                TransportPacket.PAYLOAD_RETRY_COUNT, 1,
                                TransportPacket.PAYLOAD_WORKER_ID, "worker-1",
                                TransportPacket.PAYLOAD_BATCH_ID, "batch-1",
                                TransportPacket.PAYLOAD_INPUT, Map.of("target", "https://example.test"),
                                TransportPacket.PAYLOAD_SHARED_CONFIG, Map.of("debug", true)
                        )
                ),
                1_234L
        );

        byte[] encoded = codec.encodeEntry(new KeyedQueueEntry<>(envelope, envelope.getCreatedAtEpochMillis()));
        assertFalse(new String(encoded, java.nio.charset.StandardCharsets.UTF_8).contains("deliveryQueueKey"));
        KeyedQueueEntry<TransportDispatchEnvelope> decoded = codec.decodeEntry(encoded);

        assertEquals("delivery-1", decoded.value().getDeliveryId());
        assertEquals("worker-1", decoded.value().getSelectedWorkerId());
        assertEquals(1_234L, decoded.createdAtEpochMillis());
        assertEquals("packet-1", decoded.value().getPacket().packetId());
        assertEquals("trace-1", decoded.value().getPacket().traceId());
        assertEquals("websocket", decoded.value().getPacket().adapterId());
        assertEquals("route-1", decoded.value().getPacket().routeKey());
        assertEquals("msg-1", decoded.value().getPacket().messageId());
        assertEquals(Map.of("target", "https://example.test"),
                ((Map<?, ?>) decoded.value().getPacket().payload()).get(TransportPacket.PAYLOAD_INPUT));
    }
}
