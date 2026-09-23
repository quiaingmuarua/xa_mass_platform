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
record AppCheckSpecification(String requestId, String name, String appId, String country,
                             List<String> numbers, Map<String, Object> simulation) {
    static final int MAX_NUMBERS = 1000;
    static final Map<String, String> PREFIXES = Map.of("CN", "+86", "US", "+1", "GB", "+44");

    static AppCheckSpecification parse(Map<String, Object> input) {
        if (input == null || !Set.of("requestId", "name", "appId", "country", "numbers", "simulation").containsAll(input.keySet()))
            throw new IllegalArgumentException("Unknown application check fields");
        String app = text(input.get("appId"), 128);
        if (!AppCheckTaskService.APPS.containsKey(app)) throw new IllegalArgumentException("Unsupported appId");
        String country = text(input.get("country"), 2);
        String prefix = PREFIXES.get(country);
        if (prefix == null) throw new IllegalArgumentException("Unsupported country");
        if (!(input.get("numbers") instanceof List<?> numbers) || numbers.isEmpty() || numbers.size() > MAX_NUMBERS)
            throw new IllegalArgumentException("Expected 1..1000 unique international numbers");
        var normalized = new LinkedHashSet<String>();
        for (Object entry : numbers) {
            String number = text(entry, 128).strip();
            if (!number.matches("\\+[1-9][0-9]{1,14}") || !number.startsWith(prefix) || number.length() <= prefix.length())
                throw new IllegalArgumentException("Invalid number or country prefix");
            if (!normalized.add(number)) throw new IllegalArgumentException("Duplicate number");
        }
        return new AppCheckSpecification(text(input.get("requestId"), 128),
                input.get("name") == null ? null : text(input.get("name"), 128), app, country,
                List.copyOf(normalized), simulation(input.get("simulation")));
    }

    private static Map<String, Object> simulation(Object value) {
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
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : List.of("app-checks/v1/salt", appId, country, Integer.toString(numbers.size()), date.toString())) {
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
