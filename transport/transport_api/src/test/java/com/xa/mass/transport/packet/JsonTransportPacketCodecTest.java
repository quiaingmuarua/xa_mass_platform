package com.xa.mass.transport.packet;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class JsonTransportPacketCodecTest {

    @Test
    void roundTripPreservesPacketFieldsAndJsonPayload() {
        JsonTransportPacketCodec codec = new JsonTransportPacketCodec();
        TransportPacket packet = new TransportPacket(
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
                        "workerId", "worker-1",
                        "retryCount", 2,
                        "input", Map.of("target", "https://example.test"),
                        "steps", List.of("a", "b")
                )
        );

        TransportPacket decoded = codec.decode(codec.encode(packet));

        assertEquals(TransportPacket.CURRENT_VERSION, decoded.version());
        assertEquals("packet-1", decoded.packetId());
        assertEquals("trace-1", decoded.traceId());
        assertEquals(PacketType.TASK_DISPATCH, decoded.type());
        assertEquals("websocket", decoded.adapterId());
        assertEquals("route-1", decoded.routeKey());
        assertEquals("task-1", decoded.taskId());
        assertEquals("msg-1", decoded.messageId());
        assertEquals("attempt-1", decoded.attemptId());
        assertEquals("crawler.fetch-page", decoded.eventCode());
        assertEquals(TransportPacket.JSON_CONTENT_TYPE, decoded.contentType());
        Map<?, ?> payload = assertInstanceOf(Map.class, decoded.payload());
        assertEquals("worker-1", payload.get("workerId"));
        assertEquals(2.0d, payload.get("retryCount"));
        assertEquals("https://example.test", assertInstanceOf(Map.class, payload.get("input")).get("target"));
        assertEquals(List.of("a", "b"), payload.get("steps"));
    }
}
