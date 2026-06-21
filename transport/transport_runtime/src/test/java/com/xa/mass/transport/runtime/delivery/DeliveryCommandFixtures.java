package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.model.DeliveryCommand;

import java.util.List;

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
                payload(messageId),
                correlation(messageId),
                0L,
                10L
        );
    }

    static DeliveryCommandBatch batch(String unusedConsumerKey, DeliveryCommand... commands) {
        return new DeliveryCommandBatch(mailboxKey(), List.of(commands));
    }

    static AdapterMailboxDeliveryOffer offer(DeliveryCommand... commands) {
        return new AdapterMailboxDeliveryOffer(mailboxKey(), List.of(commands));
    }

    static List<AdapterMailboxDeliveryCommand> routed(DeliveryCommand... commands) {
        return List.of(commands).stream()
                .map(command -> new AdapterMailboxDeliveryCommand(mailboxKey(), command))
                .toList();
    }

    static String mailboxKey() {
        return "mailbox-1";
    }

    static List<String> messages(DeliveryCommandBatch batch) {
        return batch.commands().stream()
                .map(command -> messageId(command.getPayload()))
                .toList();
    }

    static String payload(String messageId) {
        return "{\"messageId\":\"" + messageId + "\"}";
    }

    static String correlation(String messageId) {
        return "corr-" + messageId;
    }

    static String messageId(String payload) {
        return payload.replace("{\"messageId\":\"", "").replace("\"}", "");
    }
}
