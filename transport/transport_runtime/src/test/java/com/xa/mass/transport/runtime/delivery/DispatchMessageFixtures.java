package com.xa.mass.transport.runtime.delivery;

import java.util.List;

final class DispatchMessageFixtures {

    private DispatchMessageFixtures() {
    }

    static DispatchMessage item(String messageId, String selectedWorkerId, String unusedConsumerKey) {
        return item(messageId, selectedWorkerId);
    }

    static DispatchMessage item(String messageId, String selectedWorkerId) {
        return new DispatchMessage(
                "cmd-" + messageId,
                selectedWorkerId,
                payload(messageId),
                correlation(messageId),
                0L,
                10L
        );
    }

    static AdapterMailboxDispatchBatch batch(DispatchMessage... items) {
        return new AdapterMailboxDispatchBatch(mailboxKey(), List.of(items));
    }

    static String mailboxKey() {
        return "mailbox-1";
    }

    static List<String> messages(AdapterMailboxDispatchBatch batch) {
        return batch.items().stream()
                .map(item -> messageId(item.payload()))
                .toList();
    }

    static List<String> messages(List<DispatchMessage> items) {
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

