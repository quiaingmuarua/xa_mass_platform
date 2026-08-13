package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Repository-internal owner of one Adapter's fixed Process set and scheduler.
 */
public final class AdapterProcessManager implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(
            AdapterProcessManager.class.getName()
    );

    private final String adapterId;
    private final Duration shutdownTimeout;
    private final List<ScheduledAdapterProcess> processes;
    private ScheduledExecutorService scheduler;
    private boolean started;
    private boolean closed;

    public AdapterProcessManager(
            String adapterId,
            Duration shutdownTimeout,
            List<ScheduledAdapterProcess> processes
    ) {
        this.adapterId = requireAdapterId(adapterId);
        this.shutdownTimeout = requirePositive(
                shutdownTimeout,
                "shutdownTimeout"
        );
        this.processes = requireProcesses(processes);
    }

    /** Starts the one scheduler shared by the fixed Process set. */
    public synchronized void start() {
        if (started) {
            throw new IllegalStateException(
                    "Adapter processes have already started"
            );
        }
        if (closed) {
            throw new IllegalStateException(
                    "Adapter processes cannot start after shutdown"
            );
        }
        started = true;
        scheduler = Executors.newScheduledThreadPool(
                2,
                daemonThreadFactory(adapterId + "-loop")
        );
        for (ScheduledAdapterProcess process : processes) {
            scheduler.scheduleWithFixedDelay(
                    () -> runSafely(process),
                    process.initialDelay().toMillis(),
                    process.interval().toMillis(),
                    TimeUnit.MILLISECONDS
            );
        }
    }

    /** Quiesces all Processes at one network lifecycle cutpoint. */
    public void quiesce(QuiescePhase phase) {
        Objects.requireNonNull(phase, "phase");
        synchronized (this) {
            if (closed) {
                return;
            }
        }
        RuntimeException failure = null;
        for (ScheduledAdapterProcess scheduled : processes) {
            if (scheduled.quiescePhase() != phase) {
                continue;
            }
            try {
                scheduled.process().quiesce();
            } catch (RuntimeException error) {
                failure = accumulate(failure, error);
            }
        }
        throwIfPresent(failure);
    }

    /** Stops the shared scheduler and finishes Processes in reverse order. */
    @Override
    public void close() {
        ScheduledExecutorService stoppingScheduler;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            stoppingScheduler = scheduler;
            scheduler = null;
        }

        RuntimeException failure = null;
        boolean schedulerStopped = stoppingScheduler == null;
        try {
            stopScheduler(stoppingScheduler);
            schedulerStopped = true;
        } catch (RuntimeException error) {
            failure = error;
            schedulerStopped = stoppingScheduler != null
                    && stoppingScheduler.isTerminated();
        }
        if (schedulerStopped) {
            failure = finishProcesses(failure);
        }
        throwIfPresent(failure);
    }

    private void runSafely(ScheduledAdapterProcess scheduledProcess) {
        try {
            scheduledProcess.process().round();
        } catch (RuntimeException error) {
            logRoundFailure(error, scheduledProcess.processId());
        }
    }

    private RuntimeException finishProcesses(RuntimeException failure) {
        RuntimeException current = failure;
        for (int index = processes.size() - 1; index >= 0; index--) {
            try {
                processes.get(index).process()
                        .finishAfterSchedulerStop();
            } catch (RuntimeException error) {
                current = accumulate(current, error);
            }
        }
        return current;
    }

    private void stopScheduler(
            ScheduledExecutorService stoppingScheduler
    ) {
        if (stoppingScheduler == null) {
            return;
        }
        long deadline = System.nanoTime() + shutdownTimeout.toNanos();
        stoppingScheduler.shutdown();
        try {
            if (!awaitSchedulerUntil(stoppingScheduler, deadline)) {
                stoppingScheduler.shutdownNow();
                if (!awaitSchedulerUntil(stoppingScheduler, deadline)) {
                    throw new WorkerDeliveryAdapterException(
                            WorkerDeliveryAdapterErrorCode.SHUTDOWN_TIMEOUT,
                            "adapterProcess.stopScheduler",
                            "Adapter process scheduler did not stop within "
                                    + "its shutdown budget",
                            null
                    );
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            stoppingScheduler.shutdownNow();
            throw new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.SHUTDOWN_INTERRUPTED,
                    "adapterProcess.stopScheduler",
                    "Adapter process scheduler shutdown was interrupted",
                    error
            );
        }
    }

    private void logRoundFailure(
            RuntimeException error,
            String processId
    ) {
        WorkerDeliveryAdapterException failure = classify(
                error,
                WorkerDeliveryAdapterErrorCode.DELIVERY_INTERRUPTED,
                "adapterProcess.round",
                "Adapter process round failed"
        );
        LOGGER.log(
                System.Logger.Level.WARNING,
                "errorCode={0} operation={1} adapterId={2} processId={3} "
                        + "message={4}",
                failure.errorCode().code(),
                failure.operation(),
                adapterId,
                processId,
                failure.getMessage()
        );
    }

    private static boolean awaitSchedulerUntil(
            ScheduledExecutorService scheduler,
            long deadline
    ) throws InterruptedException {
        if (scheduler.isTerminated()) {
            return true;
        }
        long remaining = deadline - System.nanoTime();
        return remaining > 0
                && scheduler.awaitTermination(
                        remaining,
                        TimeUnit.NANOSECONDS
                );
    }

    private static List<ScheduledAdapterProcess> requireProcesses(
            List<ScheduledAdapterProcess> processes
    ) {
        Objects.requireNonNull(processes, "processes");
        if (processes.isEmpty()) {
            throw new IllegalArgumentException(
                    "processes must not be empty"
            );
        }
        return List.copyOf(processes);
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static String requireAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must be non-blank");
        }
        return adapterId;
    }

    private static WorkerDeliveryAdapterException classify(
            RuntimeException error,
            WorkerDeliveryAdapterErrorCode errorCode,
            String operation,
            String message
    ) {
        if (error instanceof WorkerDeliveryAdapterException classified) {
            return classified;
        }
        return new WorkerDeliveryAdapterException(
                errorCode,
                operation,
                message,
                error
        );
    }

    private static RuntimeException accumulate(
            RuntimeException current,
            RuntimeException addition
    ) {
        if (addition == null) {
            return current;
        }
        if (current == null) {
            return addition;
        }
        current.addSuppressed(addition);
        return current;
    }

    private static void throwIfPresent(RuntimeException failure) {
        if (failure != null) {
            throw failure;
        }
    }

    private static java.util.concurrent.ThreadFactory daemonThreadFactory(
            String prefix
    ) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "worker-delivery-" + prefix + "-"
                            + sequence.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };
    }

}
