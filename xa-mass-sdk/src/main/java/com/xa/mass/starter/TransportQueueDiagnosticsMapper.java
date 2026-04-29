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
        return toQueueDetailView(
                inputSize,
                outputSize,
                transporterAvailable,
                deliveryAvailable,
                stats,
                directByAdapter,
                transportExecutor,
                eventExecutor
        ).toMap();
    }

    static TransportQueueDetailView toQueueDetailView(int inputSize,
                                                      int outputSize,
                                                      boolean transporterAvailable,
                                                      boolean deliveryAvailable,
                                                      TransportDeliveryStoreStats stats,
                                                      Map<String, TransportDirectDeliveryStats> directByAdapter,
                                                      RuntimeTaskExecutor transportExecutor,
                                                      RuntimeTaskExecutor eventExecutor) {
        return new TransportQueueDetailView(
                inputSize,
                outputSize,
                inputSize,
                outputSize,
                transporterAvailable,
                deliveryQueueDetailView(deliveryAvailable, stats, directByAdapter),
                runtimeExecutorDetailView(transportExecutor, eventExecutor)
        );
    }

    private static DeliveryQueueDiagnosticsView deliveryQueueDetailView(boolean available,
                                                                        TransportDeliveryStoreStats stats,
                                                                        Map<String, TransportDirectDeliveryStats> directByAdapter) {
        return new DeliveryQueueDiagnosticsView(
                available,
                stats != null ? stats.getQueuedItems() : 0,
                stats != null ? stats.getQueueCount() : 0,
                stats != null ? stats.getWaitingPollers() : 0,
                stats != null ? stats.getMaxQueuedItems() : 0,
                stats != null ? stats.getOldestQueuedAgeMillis() : 0L,
                stats != null ? stats.getEnqueuedItems() : 0L,
                stats != null ? stats.getDrainedItems() : 0L,
                stats != null ? stats.getBackpressureRejectedItems() : 0L,
                stats != null ? stats.getInvalidItems() : 0L,
                stats != null ? stats.getUnavailableItems() : 0L,
                stats != null ? stats.getShutdownClearedItems() : 0L,
                stats != null ? stats.getDirectSentItems() : 0L,
                stats != null ? stats.getDirectOfflineItems() : 0L,
                stats != null ? stats.getDirectFailedItems() : 0L,
                stats != null ? stats.getDirectInvalidItems() : 0L,
                stats != null ? stats.getDirectUnavailableItems() : 0L,
                queueByAdapterDetailView(stats != null ? stats.getQueueByAdapter() : Map.of()),
                directByAdapterDetailView(directByAdapter)
        );
    }

    private static Map<String, QueueAdapterDiagnosticsView> queueByAdapterDetailView(
            Map<String, TransportDeliveryQueueStats> queueByAdapter) {
        if (queueByAdapter == null || queueByAdapter.isEmpty()) {
            return Map.of();
        }
        Map<String, QueueAdapterDiagnosticsView> map = new LinkedHashMap<>();
        queueByAdapter.forEach((adapterId, stats) -> map.put(adapterId, new QueueAdapterDiagnosticsView(
                stats != null ? stats.getQueuedItems() : 0,
                stats != null ? stats.getQueueCount() : 0,
                stats != null ? stats.getWaitingPollers() : 0,
                stats != null ? stats.getOldestQueuedAgeMillis() : 0L,
                stats != null ? stats.getBackpressureRejectedItems() : 0L
        )));
        return Map.copyOf(map);
    }

    private static Map<String, DirectAdapterDiagnosticsView> directByAdapterDetailView(
            Map<String, TransportDirectDeliveryStats> directByAdapter) {
        if (directByAdapter == null || directByAdapter.isEmpty()) {
            return Map.of();
        }
        Map<String, DirectAdapterDiagnosticsView> map = new LinkedHashMap<>();
        directByAdapter.forEach((adapterId, stats) -> map.put(adapterId, new DirectAdapterDiagnosticsView(
                stats != null ? stats.getSentItems() : 0L,
                stats != null ? stats.getOfflineItems() : 0L,
                stats != null ? stats.getFailedItems() : 0L,
                stats != null ? stats.getInvalidItems() : 0L,
                stats != null ? stats.getUnavailableItems() : 0L
        )));
        return Map.copyOf(map);
    }

    private static RuntimeExecutorsDiagnosticsView runtimeExecutorDetailView(RuntimeTaskExecutor transportExecutor,
                                                                             RuntimeTaskExecutor eventExecutor) {
        return new RuntimeExecutorsDiagnosticsView(
                executorDetailView(transportExecutor),
                executorDetailView(eventExecutor)
        );
    }

    private static RuntimeExecutorDiagnosticsView executorDetailView(RuntimeTaskExecutor executor) {
        if (executor == null) {
            return new RuntimeExecutorDiagnosticsView(
                    false,
                    0L,
                    0L,
                    0L,
                    0,
                    0,
                    0
            );
        }
        RuntimeTaskExecutorStatistics stats = executor.getStatistics();
        return new RuntimeExecutorDiagnosticsView(
                true,
                stats.getSubmittedTasks(),
                stats.getCompletedTasks(),
                stats.getRejectedTasks(),
                stats.getActiveTasks(),
                stats.getPendingTasks(),
                stats.getMaxPendingTasks()
        );
    }
}
