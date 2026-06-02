package com.xa.mass.starter;

import java.util.Map;

/**
 * Internal typed view for direct-send per-adapter diagnostics.
 */
record DirectAdapterDiagnosticsView(long sentItems,
                                    long offlineItems,
                                    long failedItems,
                                    long invalidItems,
                                    long unavailableItems) {

    Map<String, Object> toMap() {
        return Map.of(
                "sentItems", sentItems,
                "offlineItems", offlineItems,
                "failedItems", failedItems,
                "invalidItems", invalidItems,
                "unavailableItems", unavailableItems
        );
    }
}
