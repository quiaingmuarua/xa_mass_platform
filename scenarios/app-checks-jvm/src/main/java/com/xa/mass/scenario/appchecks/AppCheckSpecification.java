package com.xa.mass.scenario.appchecks;

import com.xa.mass.server.task.access.TaskCaller;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.*;

/** API admission and immutable submission identity; the Worker validates its separate input boundary. */
record AppCheckSpecification(String requestId, String userId, String checkId, String country) {
    static final Map<String, String> PREFIXES = Map.of("CN", "+86", "US", "+1", "GB", "+44");

    static AppCheckSpecification parse(TaskCaller caller, Map<String, Object> input) {
        if (input == null || !input.keySet().containsAll(Set.of("requestId", "checkId"))
                || !Set.of("requestId", "checkId", "country").containsAll(input.keySet()))
            throw new IllegalArgumentException("Unknown application check fields");
        String app = text(input.get("checkId"), 128);
        String country = input.containsKey("country") ? text(input.get("country"), 2) : null;
        return new AppCheckSpecification(text(input.get("requestId"), 128), caller.userId(), app, country);
    }

    static long integer(Object value) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            return ((Number) value).longValue();
        if (value instanceof BigDecimal decimal) {
            try { return decimal.longValueExact(); } catch (ArithmeticException invalid) { /* rejected below */ }
        }
        throw new IllegalArgumentException("Expected an integer");
    }

    static String text(Object value, int maximum) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > maximum)
            throw new IllegalArgumentException("Invalid text field");
        return text;
    }

    String salt(LocalDate date) {
        return digest("app-checks/v2/salt", checkId, country == null ? "" : country, requestId, date.toString());
    }

    String fingerprint() {
        return digest("app-checks/v5/create", userId, checkId, country == null ? "" : country);
    }

    static String taskName(String checkLabel, String country, java.time.Instant now) {
        String suffix = (country == null ? "" : " · " + country) + " · " + now;
        String prefix = checkLabel;
        int available = 128 - suffix.length();
        if (prefix.length() > available) {
            int end = available - 1;
            if (Character.isHighSurrogate(prefix.charAt(end - 1))) end--;
            prefix = prefix.substring(0, end) + "…";
        }
        return prefix + suffix;
    }

    static String digest(String... values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }
}
