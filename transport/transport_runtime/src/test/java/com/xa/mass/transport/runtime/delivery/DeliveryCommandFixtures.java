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
                "bucket-1",
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
                        "batch-1"
                ),
                0L,
                10L
        );
    }

    static DeliveryCommandBatch batch(String targetTransportNodeId, DeliveryCommand... commands) {
        return new DeliveryCommandBatch("bucket-1", "bucket-1", targetTransportNodeId, List.of(commands));
    }

    static List<String> messages(DeliveryCommandBatch batch) {
        return batch.commands().stream()
                .map(command -> command.getContent().messageId())
                .toList();
    }
}
