package com.xa.mass.transport;

import java.util.Locale;

/**
 * Transport-neutral worker strategy hints used when selecting an adapter for a
 * worker. These values describe canonical delivery style rather than a specific
 * implementation detail.
 *
 * <p>Compatibility labels such as {@code websocket}, {@code ws},
 * {@code push}, {@code pull}, and {@code queue} are normalized into the
 * stable transport families exposed by this class. These values are coarse
 * transport hints only; concrete runtime routing and worker identity must use
 * adapter-owned {@code adapterId}.
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
            case REALTIME, "websocket", "ws", "push", "websocket_push" -> REALTIME;
            case POLLING, "pull", "queue" -> POLLING;
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
