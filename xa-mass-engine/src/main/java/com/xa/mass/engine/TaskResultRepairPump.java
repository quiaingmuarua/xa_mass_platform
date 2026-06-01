package com.xa.mass.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntUnaryOperator;

/**
 * Owns the result-runtime repair pump scheduling lifecycle.
 */
final class TaskResultRepairPump {

    private static final Logger logger = LoggerFactory.getLogger(TaskResultRepairPump.class);

    private final IntUnaryOperator repairBatch;
    private final ScheduledExecutorService executor;

    TaskResultRepairPump(IntUnaryOperator repairBatch) {
        this.repairBatch = repairBatch;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "engine-result-repair-");
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        if (Boolean.getBoolean("xa.mass.engine.resultRepairPumpDisabled")) {
            return;
        }
        long intervalMillis = Long.getLong("xa.mass.engine.resultRepairPumpIntervalMillis", 1_000L);
        executor.scheduleWithFixedDelay(
                this::repairSafely,
                intervalMillis,
                intervalMillis,
                TimeUnit.MILLISECONDS
        );
    }

    void shutdown() {
        executor.shutdownNow();
    }

    private void repairSafely() {
        try {
            repairBatch.applyAsInt(Integer.getInteger("xa.mass.engine.resultRepairPumpBatchSize", 100));
        } catch (Exception e) {
            logger.warn("Result runtime repair pump failed: {}", e.getMessage(), e);
        }
    }
}
