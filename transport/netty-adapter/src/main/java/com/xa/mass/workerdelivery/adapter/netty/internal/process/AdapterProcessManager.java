package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Repository-internal owner of one Adapter's fixed resident Process loops.
 */
public final class AdapterProcessManager implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(
            AdapterProcessManager.class.getName()
    );

    private final String adapterId;
    private final Duration shutdownTimeout;
    private final List<AdapterProcessEntry> processes;
    private List<RunningProcess> runningProcesses = List.of();
    private boolean started;
    private boolean closed;

    public AdapterProcessManager(
            String adapterId,
            Duration shutdownTimeout,
            List<AdapterProcessEntry> processes
    ) {
        this.adapterId = requireAdapterId(adapterId);
        this.shutdownTimeout = requirePositive(
                shutdownTimeout,
                "shutdownTimeout"
        );
        this.processes = requireProcesses(processes);
    }

    /** Starts one named daemon platform thread for each fixed Process. */
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

        ArrayList<RunningProcess> created = new ArrayList<>(
                processes.size()
        );
        for (AdapterProcessEntry entry : processes) {
            Thread thread = new Thread(
                    () -> runSafely(entry),
                    threadName(entry.processId())
            );
            thread.setDaemon(true);
            created.add(new RunningProcess(thread));
        }
        runningProcesses = List.copyOf(created);
        for (RunningProcess running : runningProcesses) {
            running.thread().start();
        }
    }

    /** Quiesces and interrupts Processes at one network lifecycle cutpoint. */
    public void quiesce(QuiescePhase phase) {
        Objects.requireNonNull(phase, "phase");
        List<RunningProcess> runningSnapshot;
        synchronized (this) {
            if (closed) {
                return;
            }
            runningSnapshot = runningProcesses;
        }

        RuntimeException failure = null;
        for (int index = 0; index < processes.size(); index++) {
            AdapterProcessEntry entry = processes.get(index);
            if (entry.quiescePhase() != phase) {
                continue;
            }
            try {
                entry.process().quiesce();
            } catch (RuntimeException error) {
                failure = accumulate(failure, error);
            } finally {
                if (index < runningSnapshot.size()) {
                    runningSnapshot.get(index).thread().interrupt();
                }
            }
        }
        throwIfPresent(failure);
    }

    /** Interrupts all loops, joins on one deadline, then finishes in reverse. */
    @Override
    public void close() {
        List<RunningProcess> stopping;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            stopping = runningProcesses;
        }

        for (RunningProcess running : stopping) {
            running.thread().interrupt();
        }

        StopResult stopResult = stopLoops(stopping);
        RuntimeException failure = stopResult.failure();
        if (stopResult.allStopped()) {
            failure = finishProcesses(failure);
        }
        throwIfPresent(failure);
    }

    private void runSafely(AdapterProcessEntry entry) {
        try {
            entry.process().runLoop();
        } catch (RuntimeException error) {
            if (!Thread.currentThread().isInterrupted()) {
                logLoopFailure(error, entry.processId());
            }
        }
    }

    private StopResult stopLoops(List<RunningProcess> stopping) {
        long deadline = System.nanoTime() + shutdownTimeout.toNanos();
        RuntimeException failure = null;
        boolean interrupted = false;
        boolean allStopped = true;

        for (RunningProcess running : stopping) {
            Thread thread = running.thread();
            if (!thread.isAlive()) {
                continue;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                try {
                    join(thread, remaining);
                } catch (InterruptedException error) {
                    interrupted = true;
                    failure = accumulate(
                            failure,
                            shutdownInterrupted(error)
                    );
                }
            }
            if (thread.isAlive()) {
                allStopped = false;
            }
        }

        if (!allStopped) {
            failure = accumulate(failure, shutdownTimeout());
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return new StopResult(allStopped, failure);
    }

    private RuntimeException finishProcesses(RuntimeException failure) {
        RuntimeException current = failure;
        for (int index = processes.size() - 1; index >= 0; index--) {
            try {
                processes.get(index).process().finishAfterLoopStop();
            } catch (RuntimeException error) {
                current = accumulate(current, error);
            }
        }
        return current;
    }

    private void logLoopFailure(RuntimeException error, String processId) {
        WorkerDeliveryAdapterException failure = classify(
                error,
                WorkerDeliveryAdapterErrorCode.DELIVERY_INTERRUPTED,
                "adapterProcess.runLoop",
                "Adapter process loop failed"
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

    private static void join(Thread thread, long remainingNanos)
            throws InterruptedException {
        long millis = remainingNanos / 1_000_000L;
        int nanos = (int) (remainingNanos % 1_000_000L);
        thread.join(millis, nanos);
    }

    private static WorkerDeliveryAdapterException shutdownTimeout() {
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.SHUTDOWN_TIMEOUT,
                "adapterProcess.stopLoops",
                "Adapter process loops did not stop within their shared "
                        + "shutdown budget",
                null
        );
    }

    private static WorkerDeliveryAdapterException shutdownInterrupted(
            InterruptedException cause
    ) {
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.SHUTDOWN_INTERRUPTED,
                "adapterProcess.stopLoops",
                "Adapter process loop shutdown was interrupted",
                cause
        );
    }

    private static List<AdapterProcessEntry> requireProcesses(
            List<AdapterProcessEntry> processes
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

    private String threadName(String processId) {
        return "worker-delivery-" + adapterId + "-"
                + processId.toLowerCase(Locale.ROOT)
                + "-consumer";
    }

    private record RunningProcess(Thread thread) {}

    private record StopResult(
            boolean allStopped,
            RuntimeException failure
    ) {}
}
