package com.xa.mass.starter;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.RuntimeTaskExecutorStatistics;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryQueueStats;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryServiceStats;
import com.xa.mass.transport.runtime.delivery.TransportDeliveryStoreStats;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransportQueueDiagnosticsMapperTest {

    @Test
    void mapsQueueDiagnosticsIntoStableControlPlaneShape() {
        TransportDeliveryServiceStats stats = new TransportDeliveryServiceStats(new TransportDeliveryStoreStats(
                4, 2, 1, 100_000,
                25L, 10L, 6L, 3L, 1L, 2L, 0L,
                Map.of("polling", new TransportDeliveryQueueStats(4, 2, 1, 25L, 3L))
        ));
        Map<String, Object> detail = TransportQueueDiagnosticsMapper.toQueueDetail(
                -1,
                -1,
                false,
                true,
                stats,
                new FixedStatsExecutor(new RuntimeTaskExecutorStatistics(8L, 7L, 1L, 1, 1, 10_000)),
                null
        );

        assertEquals(-1, detail.get("inputQueueSize"));
        assertEquals(-1, detail.get("outputQueueSize"));
        assertEquals(false, detail.get("transporterAvailable"));

        Map<?, ?> deliveryDiagnostics = (Map<?, ?>) detail.get("deliveryDiagnostics");
        assertEquals(true, deliveryDiagnostics.get("available"));
        assertEquals(4, deliveryDiagnostics.get("queuedItems"));
        assertEquals(3L, ((Map<?, ?>) ((Map<?, ?>) deliveryDiagnostics.get("queueByAdapter")).get("polling"))
                .get("backpressureRejectedItems"));

        Map<?, ?> runtimeExecutors = (Map<?, ?>) detail.get("runtimeExecutors");
        assertEquals(true, ((Map<?, ?>) runtimeExecutors.get("transport")).get("available"));
        assertEquals(10_000, ((Map<?, ?>) runtimeExecutors.get("transport")).get("maxPendingTasks"));
        assertEquals(false, ((Map<?, ?>) runtimeExecutors.get("event")).get("available"));
    }

    private static final class FixedStatsExecutor implements RuntimeTaskExecutor {
        private final RuntimeTaskExecutorStatistics statistics;

        private FixedStatsExecutor(RuntimeTaskExecutorStatistics statistics) {
            this.statistics = statistics;
        }

        @Override
        public Future<?> submit(Runnable task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public RuntimeTaskExecutorStatistics getStatistics() {
            return statistics;
        }
    }
}
