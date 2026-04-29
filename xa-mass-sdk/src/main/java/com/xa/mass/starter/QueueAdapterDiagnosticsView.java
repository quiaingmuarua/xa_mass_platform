package com.xa.mass.starter;

import java.util.Map;

/**
 * Internal typed view for queue-path per-adapter diagnostics.
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
