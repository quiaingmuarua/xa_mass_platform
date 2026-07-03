package com.xa.mass.engine.watchdog;

import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.engine.EngineRuntimeLoop;
import com.xa.mass.engine.TaskLeaseMaintenancePort;
import com.xa.mass.engine.TaskShellLifecycleMaintenancePort;
import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Background watchdog that enforces two time-based policies:
 *
 * <ol>
 *   <li><b>Lease expiry</b>: any active leased work item whose
 *       {@code leaseExpireTime} has passed is expired via
 *       {@link TaskLeaseMaintenancePort#expireLeasedWork}, which releases the worker
 *       context and re-queues the work item (or finalizes it if retries are
 *       exhausted).</li>
 *   <li><b>Max task runtime</b>: any non-terminal {@code Task} with
 *       {@code maxRuntimeSeconds > 0} that has been running longer than
 *       that limit is terminated with
 *       {@link TaskTerminalReason#MAX_RUNTIME_REACHED}.</li>
 * </ol>
 *
 * <p>The task-runtime owns lease repair/finality. Engine hosts this loop as
 * orchestration and consumes runtime outcomes for resource release, trace, and
 * projection; it must not derive lifecycle truth from shell status here.
 */
public class LeaseExpireWatchdog implements EngineRuntimeLoop {

    private static final Logger log = LoggerFactory.getLogger(LeaseExpireWatchdog.class);
    private static final int EXPIRED_LEASE_SCAN_LIMIT = 1000;
    private static final int EXPIRED_TASK_RUNTIME_SCAN_LIMIT = 1000;

    private final TaskLeaseMaintenancePort leaseMaintenancePort;
    private final TaskShellLifecycleMaintenancePort shellLifecycleMaintenancePort;
    private final long intervalSeconds;

    public LeaseExpireWatchdog(TaskLeaseMaintenancePort leaseMaintenancePort,
                               TaskShellLifecycleMaintenancePort shellLifecycleMaintenancePort,
                               long intervalSeconds) {
        this.leaseMaintenancePort = leaseMaintenancePort;
        this.shellLifecycleMaintenancePort = shellLifecycleMaintenancePort;
        this.intervalSeconds = intervalSeconds;
    }

    @Override
    public String name() {
        return "lease-expire-watchdog";
    }

    @Override
    public long intervalMillis() {
        return Math.max(1L, intervalSeconds) * 1000L;
    }

    @Override
    public void runOnce() {
        try {
            LocalDateTime now = LocalDateTime.now();
            scanExpiredLeases(Instant.now());
            scanMaxRuntime(now);
        } catch (Exception e) {
            log.error("LeaseExpireWatchdog scan failed", e);
        }
    }

    private void scanExpiredLeases(java.time.Instant now) {
        List<ActiveLeaseRepairCandidate> expiredLeases = leaseMaintenancePort.pollExpiredLeaseCandidates(EXPIRED_LEASE_SCAN_LIMIT, now);
        for (ActiveLeaseRepairCandidate lease : expiredLeases) {
            log.warn("[Watchdog] Expiring stale work lease {} for msg {} in task {} (lease expired at {})",
                    lease.leaseToken(), lease.messageId(), lease.taskId(), lease.leaseExpireAtMillis());
            leaseMaintenancePort.expireLeasedWork(lease.taskId(), lease.messageId());
        }
    }

    private void scanMaxRuntime(LocalDateTime now) {
        List<Task> expiredTasks = shellLifecycleMaintenancePort.pollTasksPastMaxRuntimeDeadline(
                now, EXPIRED_TASK_RUNTIME_SCAN_LIMIT);
        for (Task task : expiredTasks) {
            log.warn("[Watchdog] Task {} exceeded max runtime of {}s (started {}), terminating",
                    task.getTid(), task.getExecutionSpec().getMaxRuntimeSeconds(), task.getStartTime());
            shellLifecycleMaintenancePort.terminateTask(task.getTid(), TaskTerminalReason.MAX_RUNTIME_REACHED);
        }
    }
}

