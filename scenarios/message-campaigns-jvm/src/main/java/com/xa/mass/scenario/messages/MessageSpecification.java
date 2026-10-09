package com.xa.mass.scenario.messages;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.WorkerQuery;
import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static com.xa.mass.scenario.messages.MessageWorkerSupply.*;

/** Immutable sending configuration; recipient membership is imported separately. */
record MessageSpecification(String requestId, String name, String recipientCountry, String senderCountry,
                            String body, String senderPhone) {
    static MessageSpecification parse(Map<String, Object> input) {
        if (input == null || !Set.of("requestId", "name", "recipientCountry", "senderCountry", "body", "senderPhone").containsAll(input.keySet()))
            throw invalid("Unknown message task fields");
        String country = text(input, "recipientCountry", 2);
        if (!MessageTaskService.COUNTRIES.contains(country)) throw invalid("Unsupported recipientCountry");
        String sender = input.get("senderCountry") == null ? null : text(input, "senderCountry", 2);
        if (sender != null && !MessageTaskService.COUNTRIES.contains(sender)) throw invalid("Unsupported senderCountry");
        Object rawPhone = input.get("senderPhone");
        String phone = rawPhone == null || rawPhone instanceof String value && value.isBlank()
                ? null : text(input, "senderPhone", 128);
        String body = text(input, "body", 4096);
        try { Jsons.parseObject(body); }
        catch (RuntimeException failure) { throw invalid("body must be a JSON object"); }
        return new MessageSpecification(text(input, "requestId", 128), text(input, "name", 128), country, sender, body, phone);
    }

    Map<String, String> metadata() {
        var result = new LinkedHashMap<String, String>();
        result.put("scenario", PROJECT); result.put("inputVersion", "2");
        result.put("recipientCountry", recipientCountry); result.put("body", body);
        if (senderCountry != null) result.put("senderCountry", senderCountry);
        if (senderPhone != null) result.put("senderPhone", senderPhone);
        return Map.copyOf(result);
    }

    List<RefillTarget> refill() {
        if (senderPhone != null) return List.of();
        return List.of(RefillTarget.of(POOL, new EligibilityQuery(senderCountry == null
                ? Map.of() : Map.of("worker.country", List.of(senderCountry))), 100));
    }

    WorkerQuery selector() {
        var input = new LinkedHashMap<String, Object>();
        if (senderCountry != null) input.put("country", List.of(senderCountry));
        if (senderPhone != null) input.put("phone", senderPhone);
        return new WorkerQuery(senderPhone == null ? POOL_FUNCTION : PHONE_FUNCTION, input);
    }

    String fingerprint(String group) {
        return digest("messages/v2/create", group, name, recipientCountry,
                senderCountry == null ? "" : senderCountry, senderPhone == null ? "" : senderPhone, body);
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

    static String text(Map<String, Object> input, String key, int max) {
        if (!(input.get(key) instanceof String value) || value.isBlank() || value.length() > max) throw invalid("Invalid " + key);
        return value;
    }
    private static MessageTaskService.ProductError invalid(String message) { return new MessageTaskService.ProductError(400, message, null); }
}
