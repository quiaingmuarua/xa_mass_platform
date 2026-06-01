package com.xa.mass.engine.watchdog;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.runtime.VirtualThreadRuntimeTaskExecutor;
import com.xa.mass.engine.ExponentialPollingIdleBackoffPolicy;
import com.xa.mass.engine.PollingIdleBackoffPolicy;
import com.xa.mass.engine.PollingResourceKey;
import com.xa.mass.engine.TaskRuntimeRecoveryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Runtime-driven redispatch pump for bulk/batch tasks.
 *
 * <p>This keeps bulk dispatch aligned with runtime ready visibility instead of
 * relying on task-signal retry queues and per-task delayed wakeups.
 */
public class RuntimeReadyDispatchPump {

    private static final Logger log = LoggerFactory.getLogger(RuntimeReadyDispatchPump.class);
    private static final String POLLING_SOURCE = "runtime-ready-dispatch";
    private static final long DEFAULT_MAX_IDLE_BACKOFF_MILLIS = 30_000L;

    private final TaskRuntimeRecoveryPort recoveryPort;
    private final Predicate<Task> dispatchAttempt;
    private final long intervalMillis;
    private final int scanLimit;
    private final long baseIdleBackoffMillis;
    private final long maxIdleBackoffMillis;
    private final PollingIdleBackoffPolicy idleBackoffPolicy;
    private final PollingIdleAdmissionTracker idleAdmissionTracker;
    private final VirtualThreadRuntimeTaskExecutor dispatchExecutor;
    private final Set<String> inFlightTaskIds = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService scheduler;

    public RuntimeReadyDispatchPump(TaskRuntimeRecoveryPort recoveryPort,
                                    Predicate<Task> dispatchAttempt,
                                    long intervalMillis,
                                    int scanLimit) {
        this(recoveryPort, dispatchAttempt, intervalMillis, scanLimit,
                Math.max(intervalMillis * 4L, 1_000L), DEFAULT_MAX_IDLE_BACKOFF_MILLIS);
    }

    public RuntimeReadyDispatchPump(TaskRuntimeRecoveryPort recoveryPort,
                                    Predicate<Task> dispatchAttempt,
                                    long intervalMillis,
                                    int scanLimit,
                                    long baseIdleBackoffMillis,
                                    long maxIdleBackoffMillis) {
        this(recoveryPort, dispatchAttempt, intervalMillis, scanLimit,
                baseIdleBackoffMillis, maxIdleBackoffMillis,
                ExponentialPollingIdleBackoffPolicy.INSTANCE);
    }

    public RuntimeReadyDispatchPump(TaskRuntimeRecoveryPort recoveryPort,
                                    Predicate<Task> dispatchAttempt,
                                    long intervalMillis,
                                    int scanLimit,
                                    long baseIdleBackoffMillis,
                                    long maxIdleBackoffMillis,
                                    PollingIdleBackoffPolicy idleBackoffPolicy) {
        this.recoveryPort = Objects.requireNonNull(recoveryPort, "recoveryPort");
        this.dispatchAttempt = Objects.requireNonNull(dispatchAttempt, "dispatchAttempt");
        this.intervalMillis = Math.max(intervalMillis, 50L);
        this.scanLimit = Math.max(scanLimit, 1);
        this.baseIdleBackoffMillis = Math.max(baseIdleBackoffMillis, this.intervalMillis);
        this.maxIdleBackoffMillis = Math.max(maxIdleBackoffMillis, this.baseIdleBackoffMillis);
        this.idleBackoffPolicy = idleBackoffPolicy != null
                ? idleBackoffPolicy
                : ExponentialPollingIdleBackoffPolicy.INSTANCE;
        this.idleAdmissionTracker = new PollingIdleAdmissionTracker(
                this.intervalMillis,
                this.baseIdleBackoffMillis,
                this.maxIdleBackoffMillis,
                this.idleBackoffPolicy
        );
        this.dispatchExecutor = new VirtualThreadRuntimeTaskExecutor(
                "runtime-ready-dispatch-",
                Integer.getInteger("xa.mass.engine.runtimeReadyDispatchMaxPendingTasks", 20_000)
        );
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RuntimeReadyDispatchPump");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::scan, 0L, intervalMillis, TimeUnit.MILLISECONDS);
        log.info("RuntimeReadyDispatchPump started (intervalMs={}, scanLimit={}, idleBackoffBaseMs={}, idleBackoffMaxMs={}, idleBackoffPolicy={})",
                intervalMillis, scanLimit, baseIdleBackoffMillis, maxIdleBackoffMillis,
                idleBackoffPolicy.getClass().getSimpleName());
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("RuntimeReadyDispatchPump did not terminate within 10 seconds");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
        dispatchExecutor.shutdown();
        log.info("RuntimeReadyDispatchPump stopped");
    }

    public void wakeIdleAdmissions() {
        idleAdmissionTracker.wakeAll();
    }

    private void scan() {
        try {
            long nowMillis = System.currentTimeMillis();
            idleAdmissionTracker.pruneStale(nowMillis);
            List<Task> readyTasks = recoveryPort.getRuntimeDispatchableTasks(scanLimit);
            for (Task task : readyTasks) {
                PollingResourceKey resourceKey = pollingResourceKey(task);
                if (!isRuntimeDrivenBatchTask(task)) {
                    idleAdmissionTracker.recordProgress(resourceKey);
                    continue;
                }
                String taskId = task.getTid();
                if (taskId == null || taskId.isBlank()
                        || !idleAdmissionTracker.admit(resourceKey, nowMillis)
                        || !inFlightTaskIds.add(taskId)) {
                    continue;
                }
                dispatchExecutor.submit(() -> {
                    try {
                        boolean dispatched = dispatchAttempt.test(task);
                        if (dispatched) {
                            idleAdmissionTracker.recordProgress(resourceKey);
                        } else {
                            idleAdmissionTracker.recordIdle(resourceKey, System.currentTimeMillis());
                        }
                    } catch (Exception e) {
                        idleAdmissionTracker.recordIdle(resourceKey, System.currentTimeMillis());
                        log.warn("RuntimeReadyDispatchPump dispatch attempt failed for task {}", taskId, e);
                    } finally {
                        inFlightTaskIds.remove(taskId);
                    }
                });
            }
        } catch (Exception e) {
            log.error("RuntimeReadyDispatchPump scan failed", e);
        }
    }

    private boolean isRuntimeDrivenBatchTask(Task task) {
        if (task == null) {
            return false;
        }
        TaskStatus status = task.getStatus();
        return task.getContract() == TaskContract.BATCH
                && (status == TaskStatus.READY || status == TaskStatus.RUNNING);
    }

    private PollingResourceKey pollingResourceKey(Task task) {
        if (task == null || task.getTid() == null || task.getTid().isBlank()) {
            return null;
        }
        return new PollingResourceKey(POLLING_SOURCE, "task:" + task.getTid());
    }
}
