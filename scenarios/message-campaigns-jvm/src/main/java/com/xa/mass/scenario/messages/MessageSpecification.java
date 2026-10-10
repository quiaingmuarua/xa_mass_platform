package com.xa.mass.scenario.messages;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.WorkerQuery;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static com.xa.mass.scenario.messages.MessageWorkerSupply.*;
import static com.xa.mass.scenario.messages.MessageInputs.*;

/** Immutable sending configuration; recipient membership is imported separately. */
record MessageSpecification(String requestId, String appId, String name, String recipientCountry, String senderCountry,
                            String body) {
    static MessageSpecification parse(Map<String, Object> input) {
        if (input == null || !Set.of("requestId", "appId", "name", "recipientCountry", "senderCountry", "body").containsAll(input.keySet()))
            throw invalid("Unknown message task fields");
        String country = text(input, "recipientCountry", 2);
        if (!COUNTRIES.contains(country)) throw invalid("Unsupported recipientCountry");
        String sender = input.get("senderCountry") == null ? null : text(input, "senderCountry", 2);
        if (sender != null && !COUNTRIES.contains(sender)) throw invalid("Unsupported senderCountry");
        String body = text(input, "body", 4096);
        return new MessageSpecification(text(input, "requestId", 128), text(input, "appId", 128), text(input, "name", 128), country, sender, body);
    }

    Map<String, String> metadata() {
        var result = new LinkedHashMap<String, String>();
        result.put("scenario", PROJECT); result.put("inputVersion", "3"); result.put("appId", appId);
        result.put("recipientCountry", recipientCountry); result.put("body", body);
        if (senderCountry != null) result.put("senderCountry", senderCountry);
        return Map.copyOf(result);
    }

    List<RefillTarget> refill() {
        return List.of(RefillTarget.of(POOL, new EligibilityQuery(senderCountry == null
                ? Map.of() : Map.of("worker.country", List.of(senderCountry))), 100));
    }

    WorkerQuery selector() {
        var input = new LinkedHashMap<String, Object>();
        if (senderCountry != null) input.put("country", List.of(senderCountry));
        return new WorkerQuery(POOL_FUNCTION, input);
    }

    String fingerprint(String group) {
        return digest("messages/v3/create", appId, group, name, recipientCountry,
                senderCountry == null ? "" : senderCountry, body);
    }

    static String messageId(String taskId, String recipient) { return "message-" + digest("messages/v2/message", taskId, recipient); }

    private static String digest(String... values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static MessageError invalid(String message) { return new MessageError(400, message, null); }
}
