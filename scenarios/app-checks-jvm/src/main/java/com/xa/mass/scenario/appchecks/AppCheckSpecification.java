package com.xa.mass.scenario.appchecks;

import com.xa.mass.workerdelivery.json.Jsons;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.*;

/** API admission and immutable submission identity; the Worker validates its separate input boundary. */
record AppCheckSpecification(String requestId, String appId, String country, Map<String, Object> simulation) {
    static final Map<String, String> PREFIXES = Map.of("CN", "+86", "US", "+1", "GB", "+44");

    static AppCheckSpecification parse(Map<String, Object> input) {
        if (input == null || !Set.of("requestId", "appId", "country", "simulation").containsAll(input.keySet()))
            throw new IllegalArgumentException("Unknown application check fields");
        String app = text(input.get("appId"), 128);
        if (!AppCheckTaskService.APPS.containsKey(app)) throw new IllegalArgumentException("Unsupported appId");
        String country = text(input.get("country"), 2);
        String prefix = PREFIXES.get(country);
        if (prefix == null) throw new IllegalArgumentException("Unsupported country");
        return new AppCheckSpecification(text(input.get("requestId"), 128), app, country, simulation(input.get("simulation")));
    }

    static Map<String, Object> simulation(Object value) {
        if (!(value instanceof Map<?, ?> description) || !description.keySet().equals(Set.of("ranges", "delayMs")))
            throw new IllegalArgumentException("Expected simulation ranges and delayMs");
        if (Jsons.toJson(description).length() > 4096) throw new IllegalArgumentException("Simulation exceeds 4096 characters");
        if (!(description.get("ranges") instanceof Map<?, ?> ranges)
                || !ranges.keySet().equals(Set.of("registered", "unregistered", "failed")))
            throw new IllegalArgumentException("Expected registered, unregistered and failed ranges");
        var normalized = new LinkedHashMap<String, Object>();
        boolean[] covered = new boolean[1000];
        for (String key : List.of("registered", "unregistered", "failed")) {
            List<Long> bounds = range(ranges.get(key), 1000);
            for (int bucket = bounds.getFirst().intValue(); bucket < bounds.getLast(); bucket++) {
                if (covered[bucket]) throw new IllegalArgumentException("Ranges overlap");
                covered[bucket] = true;
            }
            normalized.put(key, bounds);
        }
        for (boolean present : covered) if (!present) throw new IllegalArgumentException("Ranges have a gap");
        var result = new LinkedHashMap<String, Object>();
        result.put("ranges", Collections.unmodifiableMap(normalized));
        result.put("delayMs", range(description.get("delayMs"), 30_000));
        return Collections.unmodifiableMap(result);
    }

    private static List<Long> range(Object value, long maximum) {
        if (!(value instanceof List<?> values) || values.size() != 2) throw new IllegalArgumentException("Expected two integer bounds");
        long first = integer(values.get(0)), last = integer(values.get(1));
        if (first < 0 || first > last || last > maximum) throw new IllegalArgumentException("Invalid range bounds");
        return List.of(first, last);
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
        return digest("app-checks/v2/salt", appId, country, requestId, date.toString());
    }

    String fingerprint() {
        return digest("app-checks/v2/create", appId, country, Jsons.toJson(simulation));
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
