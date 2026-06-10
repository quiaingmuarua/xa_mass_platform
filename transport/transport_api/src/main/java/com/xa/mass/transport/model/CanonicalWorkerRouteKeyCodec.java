package com.xa.mass.transport.model;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Canonical route-key codec for the platform worker subject.
 *
 * <p>The subject is {@code workerGroupId + workerId}. Adapter and connection
 * evidence stays outside this key.</p>
 */
public final class CanonicalWorkerRouteKeyCodec {

    private static final String PREFIX = "wkr1";
    private static final String SEPARATOR = ".";
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder TOKEN_DECODER = Base64.getUrlDecoder();

    private CanonicalWorkerRouteKeyCodec() {
    }

    public static String encode(String workerGroupId, String workerId) {
        return PREFIX
                + SEPARATOR
                + encodeToken(requireText(workerGroupId, "workerGroupId"))
                + SEPARATOR
                + encodeToken(requireText(workerId, "workerId"));
    }

    public static WorkerSubject decode(String routeKey) {
        String normalized = requireText(routeKey, "routeKey");
        String[] parts = normalized.split("\\.", -1);
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            throw new IllegalArgumentException("routeKey is not a canonical worker route key");
        }
        return new WorkerSubject(
                decodeToken(parts[1], "workerGroupId"),
                decodeToken(parts[2], "workerId")
        );
    }

    public static boolean isCanonical(String routeKey) {
        if (routeKey == null || routeKey.isBlank()) {
            return false;
        }
        try {
            decode(routeKey);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static String encodeToken(String value) {
        return TOKEN_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeToken(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " token must not be blank");
        }
        try {
            return requireText(new String(TOKEN_DECODER.decode(value), StandardCharsets.UTF_8), fieldName);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(fieldName + " token is not valid", ex);
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    public record WorkerSubject(String workerGroupId, String workerId) {

        public WorkerSubject {
            workerGroupId = requireText(workerGroupId, "workerGroupId");
            workerId = requireText(workerId, "workerId");
        }
    }
}
