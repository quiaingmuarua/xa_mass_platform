package com.xa.mass.transport.socket.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.contract.worker.WorkerChannelFrame;
import com.xa.mass.contract.worker.WorkerChannelFrameJsonCodec;
import com.xa.mass.transport.packet.TransportPacket;
import com.xa.mass.transport.runtime.delivery.DispatchMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketTransportFrameCodecTest {

    private final SocketTransportFrameCodec codec = new SocketTransportFrameCodec();
    private final WorkerChannelFrameJsonCodec workerFrameCodec = new WorkerChannelFrameJsonCodec();

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
    void dispatchPayloadIsEncodedAsWorkerActionFrame() {
        String payload = "{\"replyRef\":\"corr-1\",\"eventCode\":\"demo.dispatch\",\"body\":\"{}\"}";
        String encoded = codec.encodeCanonicalTaskDispatch(new DispatchMessage(
                "message-1",
                "worker-1",
                payload,
                "corr-1",
                0L,
                1L
        ));

        WorkerChannelFrame frame = workerFrameCodec.decode(encoded);
        assertEquals(WorkerChannelFrame.ACTION, frame.kind());
        assertEquals(payload, frame.body());
        JsonObject body = JsonParser.parseString(frame.body()).getAsJsonObject();
        assertEquals("corr-1", body.get("replyRef").getAsString());
        assertEquals("demo.dispatch", body.get("eventCode").getAsString());
    }
}
