package com.xa.mass.transport;

import java.util.Locale;

/**
 * Transport-neutral worker strategy hints used when selecting an adapter for a
 * worker. These values describe canonical delivery style rather than a specific
 * implementation detail.
 *
 * <p>These values are coarse transport hints only; concrete runtime routing
 * and worker identity must use adapter-owned {@code adapterId}. Adapter labels
 * such as {@code websocket}, {@code socket}, or {@code queue-consumer} are not
 * transport-hint aliases.
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
        String normalized = strategy.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case REALTIME -> REALTIME;
            case POLLING -> POLLING;
            default -> normalized;
        };
    }

    public static boolean isRealtime(String strategy) {
        return REALTIME.equals(normalize(strategy));
    }

    public static boolean isPolling(String strategy) {
        return POLLING.equals(normalize(strategy));
    }
}
