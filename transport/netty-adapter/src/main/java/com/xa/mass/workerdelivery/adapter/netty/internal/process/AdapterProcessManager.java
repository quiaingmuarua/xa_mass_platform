package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism;
import com.xa.mass.workerdelivery.adapter.netty.internal.remote.WorkerDeliveryRemoteApi;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fixed lifecycle owner for the Command and Report batch dispatchers. */
public final class AdapterProcessManager {

    private final BatchDispatcher<DeliveryCommandItem> commandDispatcher;
    private final BatchDispatcher<String> reportDispatcher;
    private final Duration shutdownTimeout;

    public AdapterProcessManager(
            String adapterId,
            WorkerDeliveryRemoteApi remoteApi,
            WorkerConnectionMechanism connectionMechanism,
            AdapterEventDispatcher adapterEventDispatcher,
            BatchDispatcher<String> reportDispatcher,
            WorkerDeliveryCodec codec,
            int commandConsumeLimit,
            int commandRetryCapacity,
            Duration commandBackoff,
            Duration shutdownTimeout
    ) {
        this(
                BatchDispatcher.pulling(
                        adapterId,
                        "delivery-command",
                        commandRetryCapacity,
                        commandConsumeLimit,
                        commandBackoff,
                        () -> acquireCommands(
                                remoteApi,
                                adapterId,
                                commandConsumeLimit
                        ),
                        new DeliveryCommandProcess(
                                connectionMechanism,
                                adapterEventDispatcher,
                                reportDispatcher,
                                codec,
                                adapterId
                        )
                ),
                reportDispatcher,
                shutdownTimeout
        );
    }

    AdapterProcessManager(
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

    private static List<DeliveryCommandItem> acquireCommands(
            WorkerDeliveryRemoteApi remoteApi,
            String adapterId,
            int consumeLimit
    ) {
        Map<String, DeliveryCommand> acquired = remoteApi.consumeCommands(
                adapterId,
                consumeLimit
        );
        if (acquired.isEmpty()) {
            return List.of();
        }
        ArrayList<DeliveryCommandItem> batch = new ArrayList<>(
                acquired.size()
        );
        acquired.forEach((entryKey, command) -> batch.add(
                new DeliveryCommandItem(entryKey, command)
        ));
        return List.copyOf(batch);
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
