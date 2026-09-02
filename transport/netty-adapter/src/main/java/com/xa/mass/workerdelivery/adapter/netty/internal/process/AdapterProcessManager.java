package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import java.time.Duration;
import java.util.Objects;

/** Fixed lifecycle owner for the Command and Report batch dispatchers. */
public final class AdapterProcessManager {

    private final BatchDispatcher<DeliveryCommandItem> commandDispatcher;
    private final BatchDispatcher<String> reportDispatcher;
    private final Duration shutdownTimeout;

    public AdapterProcessManager(
            BatchDispatcher<DeliveryCommandItem> commandDispatcher,
            BatchDispatcher<String> reportDispatcher,
            Duration shutdownTimeout
    ) {
        this.commandDispatcher = Objects.requireNonNull(
                commandDispatcher,
                "commandDispatcher"
        );
        this.reportDispatcher = Objects.requireNonNull(
                reportDispatcher,
                "reportDispatcher"
        );
        this.shutdownTimeout = requirePositive(
                shutdownTimeout,
                "shutdownTimeout"
        );
    }

    public void start() {
        reportDispatcher.start();
        commandDispatcher.start();
    }

    public void stopCommand() {
        commandDispatcher.stopIngress();
        commandDispatcher.stop();
    }

    public void stopReport() {
        reportDispatcher.stopIngress();
        reportDispatcher.stop();
    }

    public void awaitStopped() {
        long deadline = System.nanoTime() + shutdownTimeout.toNanos();
        RuntimeException failure = null;
        boolean interrupted = false;
        boolean allStopped = true;
        for (BatchDispatcher<?> dispatcher
                : new BatchDispatcher<?>[]{
                        commandDispatcher,
                        reportDispatcher
                }) {
            if (!dispatcher.isAlive()) {
                continue;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                try {
                    dispatcher.join(remaining);
                } catch (InterruptedException error) {
                    interrupted = true;
                    failure = accumulate(
                            failure,
                            shutdownInterrupted(error)
                    );
                }
            }
            if (dispatcher.isAlive()) {
                allStopped = false;
            }
        }
        if (!allStopped) {
            failure = accumulate(failure, shutdownTimeout());
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw failure;
        }
    }

    Thread commandThread() {
        return commandDispatcher.thread();
    }

    Thread reportThread() {
        return reportDispatcher.thread();
    }

    private static WorkerDeliveryAdapterException shutdownTimeout() {
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.SHUTDOWN_TIMEOUT,
                "adapterProcess.awaitStopped",
                "Adapter batch dispatchers did not stop within their shared "
                        + "shutdown budget",
                null
        );
    }

    private static WorkerDeliveryAdapterException shutdownInterrupted(
            InterruptedException cause
    ) {
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.SHUTDOWN_INTERRUPTED,
                "adapterProcess.awaitStopped",
                "Adapter batch dispatcher shutdown was interrupted",
                cause
        );
    }

    private static RuntimeException accumulate(
            RuntimeException current,
            RuntimeException addition
    ) {
        if (current == null) {
            return addition;
        }
        current.addSuppressed(addition);
        return current;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
