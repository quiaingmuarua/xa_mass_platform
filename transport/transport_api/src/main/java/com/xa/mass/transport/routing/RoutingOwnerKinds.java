package com.xa.mass.transport.routing;

/**
 * Stable owner layers allowed in transport routing envelopes.
 */
public final class RoutingOwnerKinds {

    public static final String ADAPTER = "adapter";
    public static final String ENGINE = "engine";

    private RoutingOwnerKinds() {
    }

    public static String requireKnownOwnerKind(String ownerKind) {
        String normalized = requireText(ownerKind, "ownerKind").toLowerCase(java.util.Locale.ROOT);
        if (!ADAPTER.equals(normalized) && !ENGINE.equals(normalized)) {
            throw new IllegalArgumentException("unknown routing owner kind: " + ownerKind);
        }
        return normalized;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
