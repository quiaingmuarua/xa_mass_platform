package com.xa.mass.starter;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.RuntimeTaskExecutorStatistics;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryQueueStats;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryServiceStats;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps runtime transport diagnostics into the stable control-plane response
 * shape exposed by SDK/server queue-detail endpoints.
 *
 * <p>This mapper preserves the current response shape, but callers must not
 * interpret every numeric field as a strong queue contract. Only backlog size
 * and queue-count fields are intended to remain hard queue semantics across
 * in-memory and distributed runtime implementations.
 */
final class TransportQueueDiagnosticsMapper {

    private TransportQueueDiagnosticsMapper() {
    }

    static Map<String, Object> toQueueDetail(int inputSize,
                                             int outputSize,
                                             boolean transporterAvailable,
                                             boolean deliveryAvailable,
                                             TransportDeliveryServiceStats stats,
                                             RuntimeTaskExecutor transportExecutor,
                                             RuntimeTaskExecutor eventExecutor) {
        return toQueueDetailView(
                inputSize,
                outputSize,
                transporterAvailable,
                deliveryAvailable,
                stats,
                transportExecutor,
                eventExecutor
        ).toMap();
    }

    static TransportQueueDetailView toQueueDetailView(int inputSize,
                                                      int outputSize,
                                                      boolean transporterAvailable,
                                                      boolean deliveryAvailable,
                                                      TransportDeliveryServiceStats stats,
                                                      RuntimeTaskExecutor transportExecutor,
                                                      RuntimeTaskExecutor eventExecutor) {
        return new TransportQueueDetailView(
                inputSize,
                outputSize,
                transporterAvailable,
                deliveryQueueDetailView(deliveryAvailable, stats),
                runtimeExecutorDetailView(transportExecutor, eventExecutor)
        );
    }

    private static DeliveryQueueDiagnosticsView deliveryQueueDetailView(boolean available,
                                                                        TransportDeliveryServiceStats stats) {
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
                queueByAdapterDetailView(stats != null ? stats.getQueueByAdapter() : Map.of())
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
