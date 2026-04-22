package com.xa.mass.transport;

import java.util.Locale;

/**
 * Transport-neutral worker strategy hints used when selecting an adapter for a
 * worker. These values describe delivery style rather than a specific
 * implementation.
 */
public final class WorkerTransportHints {

    public static final String REALTIME = "realtime";
    public static final String POLLING = "polling";

    private WorkerTransportHints() {
    }

    public static String normalize(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            return null;
        }
        return strategy.trim().toLowerCase(Locale.ROOT);
    }
}
