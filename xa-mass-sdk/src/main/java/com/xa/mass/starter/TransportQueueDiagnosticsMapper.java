package com.xa.mass.starter;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.RuntimeTaskExecutorStatistics;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryQueueStats;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryStoreStats;
import com.xa.mass.transport.runtime.delivery.TransportDirectDeliveryStats;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps runtime transport diagnostics into the stable control-plane response
 * shape exposed by SDK/server queue-detail endpoints.
 */
final class TransportQueueDiagnosticsMapper {

    private TransportQueueDiagnosticsMapper() {
    }

    static Map<String, Object> toQueueDetail(int inputSize,
                                             int outputSize,
                                             boolean transporterAvailable,
                                             boolean deliveryAvailable,
                                             TransportDeliveryStoreStats stats,
                                             Map<String, TransportDirectDeliveryStats> directByAdapter,
                                             RuntimeTaskExecutor transportExecutor,
                                             RuntimeTaskExecutor eventExecutor) {
        Map<String, Object> map = new LinkedHashMap<>();
        // Keep both legacy and explicit size keys stable for current server/SDK
        // diagnostics consumers until the control-plane contract is intentionally changed.
        map.put("inputQueue", inputSize);
        map.put("outputQueue", outputSize);
        map.put("inputQueueSize", inputSize);
        map.put("outputQueueSize", outputSize);
        map.put("transporterAvailable", transporterAvailable);
        map.put("deliveryQueue", deliveryQueueDetail(deliveryAvailable, stats, directByAdapter));
        map.put("runtimeExecutors", runtimeExecutorDetail(transportExecutor, eventExecutor));
        return Map.copyOf(map);
    }

    private static Map<String, Object> deliveryQueueDetail(boolean available,
                                                           TransportDeliveryStoreStats stats,
                                                           Map<String, TransportDirectDeliveryStats> directByAdapter) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("available", available);
        map.put("queuedItems", stats != null ? stats.getQueuedItems() : 0);
        map.put("queueCount", stats != null ? stats.getQueueCount() : 0);
        map.put("waitingPollers", stats != null ? stats.getWaitingPollers() : 0);
        map.put("maxQueuedItems", stats != null ? stats.getMaxQueuedItems() : 0);
        map.put("oldestQueuedAgeMillis", stats != null ? stats.getOldestQueuedAgeMillis() : 0L);
        map.put("enqueuedItems", stats != null ? stats.getEnqueuedItems() : 0L);
        map.put("drainedItems", stats != null ? stats.getDrainedItems() : 0L);
        map.put("backpressureRejectedItems", stats != null ? stats.getBackpressureRejectedItems() : 0L);
        map.put("invalidItems", stats != null ? stats.getInvalidItems() : 0L);
        map.put("unavailableItems", stats != null ? stats.getUnavailableItems() : 0L);
        map.put("shutdownClearedItems", stats != null ? stats.getShutdownClearedItems() : 0L);
        map.put("directSentItems", stats != null ? stats.getDirectSentItems() : 0L);
        map.put("directOfflineItems", stats != null ? stats.getDirectOfflineItems() : 0L);
        map.put("directFailedItems", stats != null ? stats.getDirectFailedItems() : 0L);
        map.put("directInvalidItems", stats != null ? stats.getDirectInvalidItems() : 0L);
        map.put("directUnavailableItems", stats != null ? stats.getDirectUnavailableItems() : 0L);
        map.put("queueByAdapter", queueByAdapterDetail(stats != null ? stats.getQueueByAdapter() : Map.of()));
        map.put("directByAdapter", directByAdapterDetail(directByAdapter));
        return Map.copyOf(map);
    }

    private static Map<String, Object> queueByAdapterDetail(Map<String, TransportDeliveryQueueStats> queueByAdapter) {
        if (queueByAdapter == null || queueByAdapter.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        queueByAdapter.forEach((adapterId, stats) -> {
            Map<String, Object> adapterStats = new LinkedHashMap<>();
            adapterStats.put("queuedItems", stats != null ? stats.getQueuedItems() : 0);
            adapterStats.put("queueCount", stats != null ? stats.getQueueCount() : 0);
            adapterStats.put("waitingPollers", stats != null ? stats.getWaitingPollers() : 0);
            adapterStats.put("oldestQueuedAgeMillis", stats != null ? stats.getOldestQueuedAgeMillis() : 0L);
            adapterStats.put("backpressureRejectedItems", stats != null ? stats.getBackpressureRejectedItems() : 0L);
            map.put(adapterId, Map.copyOf(adapterStats));
        });
        return Map.copyOf(map);
    }

    private static Map<String, Object> directByAdapterDetail(Map<String, TransportDirectDeliveryStats> directByAdapter) {
        if (directByAdapter == null || directByAdapter.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        directByAdapter.forEach((adapterId, stats) -> {
            Map<String, Object> adapterStats = new LinkedHashMap<>();
            adapterStats.put("sentItems", stats != null ? stats.getSentItems() : 0L);
            adapterStats.put("offlineItems", stats != null ? stats.getOfflineItems() : 0L);
            adapterStats.put("failedItems", stats != null ? stats.getFailedItems() : 0L);
            adapterStats.put("invalidItems", stats != null ? stats.getInvalidItems() : 0L);
            adapterStats.put("unavailableItems", stats != null ? stats.getUnavailableItems() : 0L);
            map.put(adapterId, Map.copyOf(adapterStats));
        });
        return Map.copyOf(map);
    }

    private static Map<String, Object> runtimeExecutorDetail(RuntimeTaskExecutor transportExecutor,
                                                             RuntimeTaskExecutor eventExecutor) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("transport", executorDetail(transportExecutor));
        map.put("event", executorDetail(eventExecutor));
        return Map.copyOf(map);
    }

    private static Map<String, Object> executorDetail(RuntimeTaskExecutor executor) {
        if (executor == null) {
            return Map.of(
                    "available", false,
                    "submittedTasks", 0L,
                    "completedTasks", 0L,
                    "rejectedTasks", 0L,
                    "activeTasks", 0,
                    "pendingTasks", 0,
                    "maxPendingTasks", 0
            );
        }
        RuntimeTaskExecutorStatistics stats = executor.getStatistics();
        return Map.of(
                "available", true,
                "submittedTasks", stats.getSubmittedTasks(),
                "completedTasks", stats.getCompletedTasks(),
                "rejectedTasks", stats.getRejectedTasks(),
                "activeTasks", stats.getActiveTasks(),
                "pendingTasks", stats.getPendingTasks(),
                "maxPendingTasks", stats.getMaxPendingTasks()
        );
    }
}
