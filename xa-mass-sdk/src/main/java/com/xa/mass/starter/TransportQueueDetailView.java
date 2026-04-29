package com.xa.mass.starter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal typed view for the stable transport queue-detail control-plane shape.
 */
record TransportQueueDetailView(int inputQueue,
                                int outputQueue,
                                int inputQueueSize,
                                int outputQueueSize,
                                boolean transporterAvailable,
                                DeliveryQueueDiagnosticsView deliveryQueue,
                                RuntimeExecutorsDiagnosticsView runtimeExecutors) {

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("inputQueue", inputQueue);
        map.put("outputQueue", outputQueue);
        map.put("inputQueueSize", inputQueueSize);
        map.put("outputQueueSize", outputQueueSize);
        map.put("transporterAvailable", transporterAvailable);
        map.put("deliveryQueue", deliveryQueue != null ? deliveryQueue.toMap() : Map.of());
        map.put("runtimeExecutors", runtimeExecutors != null ? runtimeExecutors.toMap() : Map.of());
        return Map.copyOf(map);
    }
}
