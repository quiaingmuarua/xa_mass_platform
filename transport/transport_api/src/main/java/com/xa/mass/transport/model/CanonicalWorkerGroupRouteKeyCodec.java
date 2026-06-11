package com.xa.mass.transport.model;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Canonical route-key codec for the worker-group consumption route.
 *
 * <p>This codec is an SDK/starter assembly helper. Transport runtime and
 * adapters must treat the resulting routeKey as opaque.</p>
 */
public final class CanonicalWorkerGroupRouteKeyCodec {

    private static final String PREFIX = "wkg1";
    private static final String SEPARATOR = ".";
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder TOKEN_DECODER = Base64.getUrlDecoder();

    private CanonicalWorkerGroupRouteKeyCodec() {
    }

    public static String encode(String workerGroupId) {
        return PREFIX + SEPARATOR + encodeToken(requireText(workerGroupId, "workerGroupId"));
    }

    public static WorkerGroupSubject decode(String routeKey) {
        String normalized = requireText(routeKey, "routeKey");
        String[] parts = normalized.split("\\.", -1);
        if (parts.length != 2 || !PREFIX.equals(parts[0])) {
            throw new IllegalArgumentException("routeKey is not a canonical worker-group route key");
        }
        return new WorkerGroupSubject(decodeToken(parts[1], "workerGroupId"));
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

    public record WorkerGroupSubject(String workerGroupId) {

        public WorkerGroupSubject {
            workerGroupId = requireText(workerGroupId, "workerGroupId");
        }
    }
}
