package com.xa.mass.starter;

import java.util.Map;

/**
 * Internal typed view for queue-path per-adapter diagnostics.
 *
 * <p>{@code queuedItems} and {@code queueCount} are the hard queue contract
 * fields. {@code waitingPollers}, {@code oldestQueuedAgeMillis}, and
 * {@code backpressureRejectedItems} are best-effort diagnostics for operator
 * use rather than strong distributed queue guarantees.
 */
record QueueAdapterDiagnosticsView(int queuedItems,
                                   int queueCount,
                                   int waitingPollers,
                                   long oldestQueuedAgeMillis,
                                   long backpressureRejectedItems) {

    Map<String, Object> toMap() {
        return Map.of(
                "queuedItems", queuedItems,
                "queueCount", queueCount,
                "waitingPollers", waitingPollers,
                "oldestQueuedAgeMillis", oldestQueuedAgeMillis,
                "backpressureRejectedItems", backpressureRejectedItems
        );
    }
}
