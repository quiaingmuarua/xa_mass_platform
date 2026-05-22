package com.xa.mass.transport.socket.protocol;

import com.google.gson.JsonObject;
import com.xa.mass.transport.model.TaskResultReport;
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

    @Test
    void canonicalTaskResultDecodesIntoTaskResultReport() {
        JsonObject frame = new JsonObject();
        frame.addProperty("messageId", "msg-1");
        frame.addProperty("taskId", "task-1");
        frame.addProperty(TransportPacket.PAYLOAD_SUCCESS, true);
        frame.addProperty(TransportPacket.PAYLOAD_DETAIL, "completed");
        JsonObject output = new JsonObject();
        output.addProperty("status", "SUCCESS");
        frame.add(TransportPacket.PAYLOAD_OUTPUT, output);

        TaskResultReport report = codec.decodeCanonicalTaskResult(frame);

        assertEquals("task-1", report.getTaskId());
        assertEquals("msg-1", report.getMessageId());
        assertTrue(report.isSuccess());
        assertEquals("completed", report.getDetail());
        assertEquals("SUCCESS", report.getOutput().get("status"));
    }
}
