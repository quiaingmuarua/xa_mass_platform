package com.xa.mass.engine.watchdog;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.runtime.VirtualThreadRuntimeTaskExecutor;
import com.xa.mass.engine.TaskRuntimeRecoveryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Runtime-driven redispatch pump for bulk/batch tasks.
 *
 * <p>This keeps bulk dispatch aligned with runtime ready visibility instead of
 * relying on task-signal retry queues and per-task delayed wakeups.
 */
public class RuntimeReadyDispatchPump {

    private static final Logger log = LoggerFactory.getLogger(RuntimeReadyDispatchPump.class);

    private final TaskRuntimeRecoveryPort recoveryPort;
    private final Consumer<Task> dispatchConsumer;
    private final long intervalMillis;
    private final int scanLimit;
    private final VirtualThreadRuntimeTaskExecutor dispatchExecutor;
    private final Set<String> inFlightTaskIds = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService scheduler;

    public RuntimeReadyDispatchPump(TaskRuntimeRecoveryPort recoveryPort,
                                    Consumer<Task> dispatchConsumer,
                                    long intervalMillis,
                                    int scanLimit) {
        this.recoveryPort = Objects.requireNonNull(recoveryPort, "recoveryPort");
        this.dispatchConsumer = Objects.requireNonNull(dispatchConsumer, "dispatchConsumer");
        this.intervalMillis = Math.max(intervalMillis, 50L);
        this.scanLimit = Math.max(scanLimit, 1);
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
        log.info("RuntimeReadyDispatchPump started (intervalMs={}, scanLimit={})", intervalMillis, scanLimit);
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

    private void scan() {
        try {
            List<Task> readyTasks = recoveryPort.getRuntimeDispatchableTasks(scanLimit);
            for (Task task : readyTasks) {
                if (!isRuntimeDrivenBatchTask(task)) {
                    continue;
                }
                String taskId = task.getTid();
                if (taskId == null || taskId.isBlank() || !inFlightTaskIds.add(taskId)) {
                    continue;
                }
                dispatchExecutor.submit(() -> {
                    try {
                        dispatchConsumer.accept(task);
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
        return task.getExecutionSpec().getContract() == TaskContract.BATCH
                && (status == TaskStatus.READY || status == TaskStatus.RUNNING);
    }
}
