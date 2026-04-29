package com.xa.mass.transport.model;

import java.util.Locale;

/**
 * Shared normalization rules for transport delivery adapter and route
 * addressing.
 */
public final class TransportDeliveryAddressing {

    private TransportDeliveryAddressing() {
    }

    public static String normalizeAdapterId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public static String normalizeRouteKey(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public static boolean hasRouteKey(String value) {
        return normalizeRouteKey(value) != null;
    }

    public static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
