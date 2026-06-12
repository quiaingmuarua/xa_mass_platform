package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.packet.PacketType;
import com.xa.mass.transport.packet.TransportPacket;

import java.util.List;
import java.util.Map;

final class DeliveryCommandFixtures {

    private DeliveryCommandFixtures() {
    }

    static DeliveryCommand command(String messageId, String selectedWorkerId, String targetTransportNodeId) {
        return command(messageId, selectedWorkerId, targetTransportNodeId, "route-" + messageId);
    }

    static DeliveryCommand command(String messageId,
                                   String selectedWorkerId,
                                   String targetTransportNodeId,
                                   String routeKey) {
        String commandId = "cmd-" + messageId;
        return new DeliveryCommand(
                commandId,
                "websocket",
                selectedWorkerId,
                "websocket",
                targetTransportNodeId,
                routeKey,
                null,
                packet(commandId, messageId, routeKey),
                Map.of(
                        "taskId", "task-1",
                        "messageId", messageId,
                        "attemptId", "attempt-" + messageId,
                        "attemptNo", "1"
                ),
                0L,
                10L
        );
    }

    static DeliveryCommandBatch batch(String targetTransportNodeId, DeliveryCommand... commands) {
        return new DeliveryCommandBatch("websocket", targetTransportNodeId, List.of(commands));
    }

    static List<String> messages(DeliveryCommandBatch batch) {
        return batch.commands().stream()
                .map(command -> command.getPayload().messageId())
                .toList();
    }

    private static TransportPacket packet(String packetId, String messageId, String routeKey) {
        return new TransportPacket(
                TransportPacket.CURRENT_VERSION,
                packetId,
                "trace-" + messageId,
                PacketType.TASK_DISPATCH,
                "websocket",
                routeKey,
                "task-1",
                messageId,
                "attempt-" + messageId,
                "event-1",
                TransportPacket.JSON_CONTENT_TYPE,
                Map.of(TransportPacket.PAYLOAD_WORKER_ID, "worker")
        );
    }
}
