package com.xa.mass.starter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal typed view for the stable transport queue-detail control-plane shape.
 */
record TransportQueueDetailView(int inputQueueSize,
                                int outputQueueSize,
                                boolean transporterAvailable,
                                DeliveryQueueDiagnosticsView deliveryDiagnostics,
                                RuntimeExecutorsDiagnosticsView runtimeExecutors) {

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("inputQueueSize", inputQueueSize);
        map.put("outputQueueSize", outputQueueSize);
        map.put("transporterAvailable", transporterAvailable);
        map.put("deliveryDiagnostics", deliveryDiagnostics != null ? deliveryDiagnostics.toMap() : Map.of());
        map.put("runtimeExecutors", runtimeExecutors != null ? runtimeExecutors.toMap() : Map.of());
        return Map.copyOf(map);
    }
}
