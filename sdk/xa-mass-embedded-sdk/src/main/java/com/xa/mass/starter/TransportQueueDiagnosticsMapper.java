package com.xa.mass.starter;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.RuntimeTaskExecutorStatistics;

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

    static Map<String, Object> toQueueDetail(RuntimeTaskExecutor transportExecutor,
                                             RuntimeTaskExecutor eventExecutor) {
        return toQueueDetailView(
                transportExecutor,
                eventExecutor
        ).toMap();
    }

    static TransportQueueDetailView toQueueDetailView(RuntimeTaskExecutor transportExecutor,
                                                      RuntimeTaskExecutor eventExecutor) {
        return new TransportQueueDetailView(
                deliveryQueueDetailView(),
                runtimeExecutorDetailView(transportExecutor, eventExecutor)
        );
    }

    private static DeliveryQueueDiagnosticsView deliveryQueueDetailView() {
        return new DeliveryQueueDiagnosticsView(
                false,
                0,
                0,
                0,
                0,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                Map.of()
        );
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
