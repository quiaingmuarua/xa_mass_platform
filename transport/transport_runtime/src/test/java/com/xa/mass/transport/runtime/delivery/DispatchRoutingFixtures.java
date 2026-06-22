package com.xa.mass.transport.runtime.delivery;

import com.xa.mass.transport.routing.RoutingTarget;

import java.util.List;

final class DispatchRoutingFixtures {

    private DispatchRoutingFixtures() {
    }

    static DispatchRoutingItem item(String messageId, String selectedWorkerId, String unusedConsumerKey) {
        return item(messageId, selectedWorkerId);
    }

    static DispatchRoutingItem item(String messageId, String selectedWorkerId) {
        return new DispatchRoutingItem(
                "cmd-" + messageId,
                selectedWorkerId,
                payload(messageId),
                correlation(messageId),
                0L,
                10L
        );
    }

    static DispatchRoutingBatch batch(DispatchRoutingItem... items) {
        return new DispatchRoutingBatch(RoutingTarget.adapterMailbox(mailboxKey()), List.of(items));
    }

    static String mailboxKey() {
        return "mailbox-1";
    }

    static List<String> messages(DispatchRoutingBatch batch) {
        return batch.items().stream()
                .map(item -> messageId(item.payload()))
                .toList();
    }

    static List<String> messages(List<DispatchRoutingItem> items) {
        return items.stream()
                .map(item -> messageId(item.payload()))
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
