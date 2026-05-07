package com.xa.mass.transport.socket.protocol;

import com.google.gson.JsonObject;
import com.xa.mass.transport.packet.TransportPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
