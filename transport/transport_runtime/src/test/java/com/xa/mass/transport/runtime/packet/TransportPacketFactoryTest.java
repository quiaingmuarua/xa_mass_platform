package com.xa.mass.transport.runtime.packet;

import com.xa.mass.transport.model.TaskDispatchItem;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TransportPacketFactoryTest {

    @Test
    void dispatchPacketProjectsDispatchIdentityAndPayload() {
        TransportPacketFactory factory = new TransportPacketFactory(() -> "packet-1");
        TaskDispatchItem item = new TaskDispatchItem(
                "task-1",
                "msg-1",
                "crawler.fetch-page",
                "task-name",
                "demoApp",
                "user-a",
                2,
                "attempt-1",
                "worker-1",
                "ctx-1",
                "batch-1",
                Map.of("target", "https://example.test"),
                Map.of("textContent", "hello")
        );

        TransportPacket packet = factory.fromDispatchItem("websocket", "route-1", "trace-1", item);

        assertEquals("packet-1", packet.packetId());
        assertEquals(PacketType.TASK_DISPATCH, packet.type());
        assertEquals("websocket", packet.adapterId());
        assertEquals("route-1", packet.routeKey());
        assertEquals("task-1", packet.taskId());
        assertEquals("msg-1", packet.messageId());
        assertEquals("attempt-1", packet.attemptId());
        assertEquals("crawler.fetch-page", packet.eventCode());
        Map<?, ?> payload = assertInstanceOf(Map.class, packet.payload());
        assertEquals("worker-1", payload.get(TransportPacket.PAYLOAD_WORKER_ID));
        assertEquals("https://example.test",
                assertInstanceOf(Map.class, payload.get(TransportPacket.PAYLOAD_INPUT)).get("target"));
    }

    @Test
    void resultPacketStaysTransportScoped() {
        TransportPacketFactory factory = new TransportPacketFactory(() -> "packet-result");
        TaskResultReport report = new TaskResultReport("task-1", "msg-1", true, "ok", null, Map.of("status", "SUCCESS"));

        TransportPacket packet = factory.fromResultReport("socket", "route-2", "trace-2", "attempt-9", report);

        assertEquals(PacketType.TASK_RESULT, packet.type());
        assertEquals("socket", packet.adapterId());
        assertEquals("route-2", packet.routeKey());
        assertEquals("task-1", packet.taskId());
        assertEquals("msg-1", packet.messageId());
        assertEquals("attempt-9", packet.attemptId());
        Map<?, ?> payload = assertInstanceOf(Map.class, packet.payload());
        assertEquals(Boolean.TRUE, payload.get(TransportPacket.PAYLOAD_SUCCESS));
        assertEquals("SUCCESS",
                assertInstanceOf(Map.class, payload.get(TransportPacket.PAYLOAD_OUTPUT)).get("status"));
    }

    @Test
    void workerSystemEventPacketCarriesTypeAndPayload() {
        TransportPacketFactory factory = new TransportPacketFactory(() -> "packet-event");

        TransportPacket packet = factory.workerSystemEvent(
                "worker.online",
                "polling",
                "worker-1",
                "trace-3",
                Map.of("workerId", "worker-1", "reason", "connected")
        );

        assertEquals(PacketType.WORKER_SYSTEM_EVENT, packet.type());
        assertEquals("polling", packet.adapterId());
        assertEquals("worker-1", packet.routeKey());
        assertEquals("worker.online", packet.eventCode());
        assertEquals("connected", assertInstanceOf(Map.class, packet.payload()).get("reason"));
    }
}
