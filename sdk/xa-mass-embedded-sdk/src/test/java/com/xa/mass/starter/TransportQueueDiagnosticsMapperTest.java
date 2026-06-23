package com.xa.mass.starter;

import com.xa.mass.base.runtime.RuntimeTaskExecutor;
import com.xa.mass.base.runtime.RuntimeTaskExecutorStatistics;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransportQueueDiagnosticsMapperTest {

    @Test
    void mapsQueueDiagnosticsIntoStableControlPlaneShape() {
        Map<String, Object> detail = TransportQueueDiagnosticsMapper.toQueueDetail(
                new FixedStatsExecutor(new RuntimeTaskExecutorStatistics(8L, 7L, 1L, 1, 1, 10_000)),
                null
        );

        Map<?, ?> deliveryDiagnostics = (Map<?, ?>) detail.get("deliveryDiagnostics");
        assertEquals(false, deliveryDiagnostics.get("available"));
        assertEquals(0, deliveryDiagnostics.get("queuedItems"));
        assertEquals(Map.of(), deliveryDiagnostics.get("queueByAdapter"));

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
