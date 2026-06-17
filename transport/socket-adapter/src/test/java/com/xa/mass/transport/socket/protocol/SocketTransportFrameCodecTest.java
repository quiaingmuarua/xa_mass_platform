package com.xa.mass.transport.socket.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.transport.packet.TransportPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketTransportFrameCodecTest {

    private final SocketTransportFrameCodec codec = new SocketTransportFrameCodec();

    @Test
    void helloFrameCanCarryIndependentRouteKey() {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "hello");
        frame.addProperty(TransportPacket.PAYLOAD_WORKER_ID, "worker-1");
        frame.addProperty("routeKey", "socket-route-5");

        assertTrue(codec.isHelloFrame(frame));
        assertEquals("worker-1", codec.extractWorkerId(frame));
        assertEquals("socket-route-5", codec.extractRouteKey(frame));
    }

    @Test
    void canonicalTaskResultEncodesOpaquePayload() {
        JsonObject frame = new JsonObject();
        frame.addProperty("resultCorrelationRef", "corr-1");
        frame.addProperty(TransportPacket.PAYLOAD_SUCCESS, true);
        frame.addProperty(TransportPacket.PAYLOAD_DETAIL, "completed");
        JsonObject output = new JsonObject();
        output.addProperty("status", "SUCCESS");
        frame.add(TransportPacket.PAYLOAD_OUTPUT, output);

        JsonObject payload = JsonParser.parseString(codec.encodeCanonicalTaskResultPayload(frame)).getAsJsonObject();

        assertEquals("corr-1", payload.get("resultCorrelationRef").getAsString());
        assertFalse(payload.has("taskId"));
        assertFalse(payload.has("messageId"));
        assertTrue(payload.get(TransportPacket.PAYLOAD_SUCCESS).getAsBoolean());
        assertEquals("completed", payload.get(TransportPacket.PAYLOAD_DETAIL).getAsString());
        assertEquals("SUCCESS", payload.getAsJsonObject(TransportPacket.PAYLOAD_OUTPUT).get("status").getAsString());
    }
}
