package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.TaskDispatchContent;
import com.xa.mass.transport.model.TaskDispatchExecutionContext;

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
        return new DeliveryCommand(
                "cmd-" + messageId,
                selectedWorkerId,
                new TaskDispatchContent(
                        "task-1",
                        messageId,
                        "event-1",
                        Map.of("input", messageId),
                        Map.of()
                ),
                new TaskDispatchExecutionContext(
                        "attempt-" + messageId,
                        1,
                        0,
                        "batch-1",
                        "task-name",
                        "demoApp",
                        "agent"
                ),
                0L,
                10L
        );
    }

    static ResolvedDeliveryItem item(DeliveryCommand command, String targetTransportNodeId, String routeKey) {
        return new ResolvedDeliveryItem(
                command,
                new EndpointLease(
                        command.getSelectedWorkerId(),
                        routeKey,
                        targetTransportNodeId,
                        "conn-" + command.getSelectedWorkerId(),
                        System.currentTimeMillis() + 30_000L
                )
        );
    }

    static DeliveryCommandBatch batch(String targetTransportNodeId, DeliveryCommand... commands) {
        List<ResolvedDeliveryItem> items = List.of(commands).stream()
                .map(command -> item(command, targetTransportNodeId, "route-" + command.getContent().messageId()))
                .toList();
        return new DeliveryCommandBatch("websocket", "websocket", targetTransportNodeId, items);
    }

    static DeliveryCommandBatch batch(String targetTransportNodeId, ResolvedDeliveryItem... items) {
        return new DeliveryCommandBatch("websocket", "websocket", targetTransportNodeId, List.of(items));
    }

    static DeliveryCommandGroup group(DeliveryCommand... commands) {
        return new DeliveryCommandGroup("websocket", List.of(commands));
    }

    static List<String> messages(DeliveryCommandBatch batch) {
        return batch.commands().stream()
                .map(command -> command.getContent().messageId())
                .toList();
    }
}
