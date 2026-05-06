package com.xa.mass.starter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal typed view for combined queue-path and direct-send diagnostics.
 *
 * <p>Field contract split for control-plane readers:
 *
 * <ul>
 *   <li>hard queue fields:
 *     {@code queuedItems}, {@code queueCount}, {@code maxQueuedItems}, and
 *     the nested per-adapter {@code queuedItems}/{@code queueCount}
 *   <li>best-effort queue diagnostics:
 *     {@code waitingPollers}, {@code oldestQueuedAgeMillis},
 *     {@code enqueuedItems}, {@code drainedItems},
 *     {@code backpressureRejectedItems}, {@code invalidItems},
 *     {@code unavailableItems}, {@code shutdownClearedItems}
 *   <li>direct-send diagnostics:
 *     {@code directSentItems}, {@code directOfflineItems},
 *     {@code directFailedItems}, {@code directInvalidItems},
 *     {@code directUnavailableItems}
 * </ul>
 *
 * <p>Best-effort fields remain useful for operator diagnosis, but future
 * distributed queue implementations are not required to preserve the exact
 * local waiter or snapshot timing behavior of the current in-memory store.
 */
record DeliveryQueueDiagnosticsView(boolean available,
                                    int queuedItems,
                                    int queueCount,
                                    int waitingPollers,
                                    int maxQueuedItems,
                                    long oldestQueuedAgeMillis,
                                    long enqueuedItems,
                                    long drainedItems,
                                    long backpressureRejectedItems,
                                    long invalidItems,
                                    long unavailableItems,
                                    long shutdownClearedItems,
                                    long directSentItems,
                                    long directOfflineItems,
                                    long directFailedItems,
                                    long directInvalidItems,
                                    long directUnavailableItems,
                                    Map<String, QueueAdapterDiagnosticsView> queueByAdapter,
                                    Map<String, DirectAdapterDiagnosticsView> directByAdapter) {

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("available", available);
        map.put("queuedItems", queuedItems);
        map.put("queueCount", queueCount);
        map.put("waitingPollers", waitingPollers);
        map.put("maxQueuedItems", maxQueuedItems);
        map.put("oldestQueuedAgeMillis", oldestQueuedAgeMillis);
        map.put("enqueuedItems", enqueuedItems);
        map.put("drainedItems", drainedItems);
        map.put("backpressureRejectedItems", backpressureRejectedItems);
        map.put("invalidItems", invalidItems);
        map.put("unavailableItems", unavailableItems);
        map.put("shutdownClearedItems", shutdownClearedItems);
        map.put("directSentItems", directSentItems);
        map.put("directOfflineItems", directOfflineItems);
        map.put("directFailedItems", directFailedItems);
        map.put("directInvalidItems", directInvalidItems);
        map.put("directUnavailableItems", directUnavailableItems);
        map.put("queueByAdapter", toQueueByAdapterMap(queueByAdapter));
        map.put("directByAdapter", toDirectByAdapterMap(directByAdapter));
        return Map.copyOf(map);
    }

    private static Map<String, Object> toQueueByAdapterMap(Map<String, QueueAdapterDiagnosticsView> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        source.forEach((adapterId, view) -> map.put(adapterId, view != null ? view.toMap() : Map.of()));
        return Map.copyOf(map);
    }

    private static Map<String, Object> toDirectByAdapterMap(Map<String, DirectAdapterDiagnosticsView> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        source.forEach((adapterId, view) -> map.put(adapterId, view != null ? view.toMap() : Map.of()));
        return Map.copyOf(map);
    }
}
