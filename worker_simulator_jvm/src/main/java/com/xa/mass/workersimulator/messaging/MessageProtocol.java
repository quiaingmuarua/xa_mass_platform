package com.xa.mass.workersimulator.messaging;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;

/** Pure Messages wire checks shared by the Host's sender and receiving service. */
public final class MessageProtocol {
    public static final String SEND_EVENT = "extension.worker.message.send";
    private MessageProtocol() {}

    static void validateMessage(Map<String, Object> request) {
        if (!request.keySet().equals(Set.of("campaignId", "messageId", "country", "recipientId", "body")))
            throw new IllegalArgumentException("Invalid send fields");
        text(request, "campaignId", 128); text(request, "messageId", 128); text(request, "recipientId", 128);
        text(request, "body", 4096); text(request, "country", 2);
    }

    static boolean sameRequest(Map<String, Object> request, Map<String, Object> snapshot) {
        return request.entrySet().stream().allMatch(entry -> Objects.equals(entry.getValue(), snapshot.get(entry.getKey())));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Map<String, Object> input, String key) {
        if (!(input.get(key) instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(k -> !(k instanceof String)))
            throw new IllegalArgumentException("Invalid " + key);
        return (Map<String, Object>) map;
    }

    static String text(Map<String, Object> input, String key, int maximum) {
        if (!(input.get(key) instanceof String value) || value.isBlank() || value.length() > maximum)
            throw new IllegalArgumentException("Invalid " + key);
        return value;
    }

    static String nullableCallback(Map<String, Object> value) {
        if (!value.containsKey("callbackId")) throw new IllegalArgumentException("Missing callbackId");
        return value.get("callbackId") == null ? null : text(value, "callbackId", 128);
    }

    static int receiptTag(Map<String, Object> snapshot) {
        int tag = switch (text(snapshot, "status", 16)) {
            case "DELIVERED" -> 7; case "READ" -> 8; case "REPLIED" -> 9;
            default -> throw new IllegalArgumentException("Invalid receipt status");
        };
        Set<String> required = new HashSet<>(Set.of("campaignId", "messageId", "country", "recipientId", "body",
                "status", "workerId", "phone", "observedAtMillis"));
        if (tag == 9) { required.add("reply"); required.add("replyRequestId"); text(snapshot, "reply", 4096); text(snapshot, "replyRequestId", 128); }
        if (!snapshot.keySet().equals(required) || !(snapshot.get("observedAtMillis") instanceof Number time)
                || !Double.isFinite(time.doubleValue()) || time.longValue() <= 0 || time.doubleValue() != time.longValue())
            throw new IllegalArgumentException("Invalid receipt snapshot");
        return tag;
    }

    static void pageBounds(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 1000) throw new IllegalArgumentException("Invalid page");
    }
}
