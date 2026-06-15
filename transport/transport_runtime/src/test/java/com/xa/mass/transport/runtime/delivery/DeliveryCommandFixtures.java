package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;
import com.xa.mass.transport.model.TaskDispatchContent;
import com.xa.mass.transport.model.TaskDispatchExecutionContext;

import java.util.List;
import java.util.Map;

final class DeliveryCommandFixtures {

    private DeliveryCommandFixtures() {
    }

    static DeliveryCommand command(String messageId, String selectedWorkerId, String unusedConsumerKey) {
        return command(messageId, selectedWorkerId, unusedConsumerKey, "route-" + messageId);
    }

    static DeliveryCommand command(String messageId,
                                   String selectedWorkerId,
                                   String unusedConsumerKey,
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

    static DeliveryCommandBatch batch(String unusedConsumerKey, DeliveryCommand... commands) {
        return new DeliveryCommandBatch(queueKey(), List.of(commands));
    }

    static DeliveryQueueOffer offer(DeliveryCommand... commands) {
        return new DeliveryQueueOffer(queueKey(), List.of(commands));
    }

    static String queueKey() {
        return AssignedDeliveryCommandQueueKey.queueKeyFor("bucket-1");
    }

    static List<String> messages(DeliveryCommandBatch batch) {
        return batch.commands().stream()
                .map(command -> command.getContent().messageId())
                .toList();
    }
}
