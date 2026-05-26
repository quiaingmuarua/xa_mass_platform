package com.xa.mass.engine.watchdog;

import com.xa.mass.engine.command.WorkerCommandLifecycleResult;
import com.xa.mass.engine.worker.WorkerControlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Bounded maintenance loop for owner-backed worker command expiry.
 */
public final class WorkerCommandMaintenanceWatchdog {

    private static final Logger log = LoggerFactory.getLogger(WorkerCommandMaintenanceWatchdog.class);

    private final WorkerControlService workerControlService;
    private final long intervalSeconds;
    private final int scanLimit;
    private final int maxDeliveryAttempts;
    private ScheduledExecutorService scheduler;

    public WorkerCommandMaintenanceWatchdog(WorkerControlService workerControlService,
                                            long intervalSeconds,
                                            int scanLimit,
                                            int maxDeliveryAttempts) {
        if (workerControlService == null) {
            throw new IllegalArgumentException("workerControlService must not be null");
        }
        this.workerControlService = workerControlService;
        this.intervalSeconds = Math.max(1L, intervalSeconds);
        this.scanLimit = Math.max(1, scanLimit);
        this.maxDeliveryAttempts = Math.max(1, maxDeliveryAttempts);
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "WorkerCommandMaintenanceWatchdog");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::scan, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("WorkerCommandMaintenanceWatchdog started (interval={}s, scanLimit={}, maxDeliveryAttempts={})",
                intervalSeconds, scanLimit, maxDeliveryAttempts);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("WorkerCommandMaintenanceWatchdog did not terminate within 10 seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
        log.info("WorkerCommandMaintenanceWatchdog stopped");
    }

    void scan() {
        try {
            List<WorkerCommandLifecycleResult> results =
                    workerControlService.expireDueWorkerCommands(Instant.now(), scanLimit);
            if (!results.isEmpty()) {
                log.info("Expired {} worker commands", results.size());
            }
            int remainingLimit = Math.max(0, scanLimit - results.size());
            if (remainingLimit > 0) {
                List<WorkerCommandLifecycleResult> retryResults =
                        workerControlService.retryPendingWorkerCommandDeliveries(remainingLimit, maxDeliveryAttempts);
                if (!retryResults.isEmpty()) {
                    log.info("Retried {} pending worker command deliveries", retryResults.size());
                }
            }
        } catch (RuntimeException e) {
            log.error("WorkerCommandMaintenanceWatchdog scan failed", e);
        }
    }
}
