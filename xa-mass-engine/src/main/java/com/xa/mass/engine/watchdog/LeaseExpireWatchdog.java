package com.xa.mass.engine.watchdog;

import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.work.ActiveLeaseRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background watchdog that enforces two time-based policies:
 *
 * <ol>
 *   <li><b>Lease expiry</b>: any active {@code TaskMsgAttempt} whose
 *       {@code leaseExpireTime} has passed is expired via
 *       {@link TaskManager#expireTaskMessage}, which releases the worker
 *       context and re-queues the message (or finalizes it if retries are
 *       exhausted).</li>
 *   <li><b>Max task runtime</b>: any non-terminal {@code Task} with
 *       {@code maxRuntimeSeconds > 0} that has been running longer than
 *       that limit is terminated with
 *       {@link TaskTerminalReason#MAX_RUNTIME_REACHED}.</li>
 * </ol>
 *
 * <p>Start this once from {@code MassEngine.start()} and stop it in
 * {@code MassEngine.stop()}.
 */
public class LeaseExpireWatchdog {

    private static final Logger log = LoggerFactory.getLogger(LeaseExpireWatchdog.class);
    private static final int EXPIRED_LEASE_SCAN_LIMIT = 1000;

    private final TaskManager taskManager;
    private final long intervalSeconds;
    private ScheduledExecutorService scheduler;

    public LeaseExpireWatchdog(TaskManager taskManager, long intervalSeconds) {
        this.taskManager = taskManager;
        this.intervalSeconds = intervalSeconds;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LeaseExpireWatchdog");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::scan, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("LeaseExpireWatchdog started (interval={}s)", intervalSeconds);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("LeaseExpireWatchdog did not terminate within 10 seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
        log.info("LeaseExpireWatchdog stopped");
    }

    private void scan() {
        try {
            LocalDateTime now = LocalDateTime.now();
            scanExpiredLeases(java.time.Instant.now());
            List<Task> tasks = taskManager.getAllTasks();
            for (Task task : tasks) {
                if (task == null || task.getStatus() == null || task.getStatus().isFinal()) {
                    continue;
                }
                scanMaxRuntime(task, now);
            }
        } catch (Exception e) {
            log.error("LeaseExpireWatchdog scan failed", e);
        }
    }

    private void scanExpiredLeases(java.time.Instant now) {
        List<ActiveLeaseRecord> expiredLeases = taskManager.getTaskWorkRuntime()
                .pollExpiredLeases(EXPIRED_LEASE_SCAN_LIMIT, now);
        for (ActiveLeaseRecord lease : expiredLeases) {
            log.warn("[Watchdog] Expiring stale work lease {} for msg {} in task {} (lease expired at {})",
                    lease.leaseToken(), lease.messageId(), lease.taskId(), lease.leaseExpireAt());
            taskManager.expireTaskMessage(lease.taskId(), lease.messageId());
        }
    }

    private void scanMaxRuntime(Task task, LocalDateTime now) {
        int maxRuntimeSeconds = task.getMaxRuntimeSeconds();
        if (maxRuntimeSeconds <= 0 || task.getStartTime() == null) {
            return;
        }
        LocalDateTime deadline = task.getStartTime().plusSeconds(maxRuntimeSeconds);
        if (now.isAfter(deadline)) {
            log.warn("[Watchdog] Task {} exceeded max runtime of {}s (started {}), terminating",
                    task.getTid(), maxRuntimeSeconds, task.getStartTime());
            taskManager.terminateTask(task.getTid(), TaskTerminalReason.MAX_RUNTIME_REACHED);
        }
    }
}
