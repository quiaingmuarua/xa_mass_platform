package com.xa.mass.transport.model;

import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeliveryCommandTest {

    @Test
    void normalizesDeliveryAddressAndKeepsCorrelationOpaque() {
        DeliveryCommand command = new DeliveryCommand(
                " command-1 ",
                " WebSocket ",
                " worker-1 ",
                " lane-1 ",
                " node-1 ",
                " group-route ",
                " conn-token ",
                packet(),
                Map.of(" taskId ", " task-1 ", " blank ", " "),
                100L,
                10L
        );

        assertEquals("command-1", command.getCommandId());
        assertEquals("websocket", command.getAdapterId());
        assertEquals("worker-1", command.getSelectedWorkerId());
        assertEquals("lane-1", command.getDeliveryQueueKey());
        assertEquals("node-1", command.getTargetTransportNodeId());
        assertEquals("group-route", command.getRouteKey());
        assertEquals("conn-token", command.getConnectionToken());
        assertEquals(packet(), command.getPayload());
        assertEquals(Map.of("taskId", "task-1"), command.getCorrelation());
        assertEquals(100L, command.getDeadlineEpochMillis());
        assertEquals(10L, command.getCreatedAtEpochMillis());
    }

    @Test
    void rejectsMissingRequiredDeliveryFields() {
        TransportPacket packet = packet();

        assertThrows(IllegalArgumentException.class, () -> new DeliveryCommand(
                " ",
                "websocket",
                "worker-1",
                "lane-1",
                null,
                null,
                null,
                packet,
                Map.of(),
                0L,
                0L
        ));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryCommand(
                "command-1",
                " ",
                "worker-1",
                "lane-1",
                null,
                null,
                null,
                packet,
                Map.of(),
                0L,
                0L
        ));
        assertThrows(IllegalArgumentException.class, () -> new DeliveryCommand(
                "command-1",
                "websocket",
                " ",
                "lane-1",
                null,
                null,
                null,
                packet,
                Map.of(),
                0L,
                0L
        ));
    }

    private static TransportPacket packet() {
        return new TransportPacket(
                TransportPacket.CURRENT_VERSION,
                "packet-1",
                "trace-1",
                PacketType.TASK_DISPATCH,
                "websocket",
                "group-route",
                "task-1",
                "msg-1",
                "attempt-1",
                "event-1",
                TransportPacket.JSON_CONTENT_TYPE,
                Map.of("input", Map.of("url", "https://example.test"))
        );
    }
}
