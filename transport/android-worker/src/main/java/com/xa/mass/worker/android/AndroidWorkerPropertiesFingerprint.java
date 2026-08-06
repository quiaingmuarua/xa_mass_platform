package com.xa.mass.worker.android;

import com.xa.mass.workerdelivery.json.Jsons;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class AndroidWorkerPropertiesFingerprint {

    private AndroidWorkerPropertiesFingerprint() {
    }

    static String sha256(Map<String, Object> properties) {
        if (properties == null) {
            throw new IllegalArgumentException(
                    "workerProperties must be present"
            );
        }
        String canonicalJson = Jsons.toJson(canonicalObject(properties));
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(
                    canonicalJson.getBytes(StandardCharsets.UTF_8)
            );
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
        StringBuilder encoded = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            encoded.append(Character.forDigit((value >>> 4) & 0xf, 16));
            encoded.append(Character.forDigit(value & 0xf, 16));
        }
        return encoded.toString();
    }

    static Map<String, Object> snapshot(Map<String, Object> properties) {
        if (properties == null) {
            throw new IllegalArgumentException(
                    "workerProperties must be present"
            );
        }
        return Collections.unmodifiableMap(canonicalObject(properties));
    }

    private static Map<String, Object> canonicalObject(
            Map<?, ?> value
    ) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException(
                        "JSON object keys must be strings"
                );
            }
            sorted.put(
                    (String) entry.getKey(),
                    canonicalValue(entry.getValue())
            );
        }
        return new LinkedHashMap<>(sorted);
    }

    private static Object canonicalValue(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Number) {
            return value;
        }
        if (value instanceof Map<?, ?>) {
            return canonicalObject((Map<?, ?>) value);
        }
        if (value instanceof List<?>) {
            List<Object> canonical = new ArrayList<>();
            for (Object item : (List<?>) value) {
                canonical.add(canonicalValue(item));
            }
            return canonical;
        }
        throw new IllegalArgumentException(
                "workerProperties contain a non-JSON value"
        );
    }
}
