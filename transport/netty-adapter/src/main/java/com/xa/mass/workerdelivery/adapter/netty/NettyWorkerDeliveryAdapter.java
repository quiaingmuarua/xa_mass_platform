package com.xa.mass.workerdelivery.adapter.netty;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterState;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionInboundHandler;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.NettyWorkerServer;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryCommandProcess;
import com.xa.mass.workerdelivery.adapter.netty.internal.process.DeliveryReportProcess;
import java.time.Duration;
import java.util.Objects;

final class NettyWorkerDeliveryAdapter implements WorkerDeliveryAdapter {

    private final String adapterId;
    private final WorkerConnectionInboundHandler connectionInboundHandler;
    private final WorkerConnectionMechanism connectionMechanism;
    private final DeliveryCommandProcess commandProcess;
    private final DeliveryReportProcess reportProcess;
    private final NettyWorkerServer networkServer;
    private final Duration shutdownTimeout;
    private final Thread commandThread;
    private final Thread reportThread;
    private volatile WorkerDeliveryAdapterState state =
            WorkerDeliveryAdapterState.REGISTERED;

    NettyWorkerDeliveryAdapter(
            String adapterId,
            NettyWorkerServer networkServer,
            WorkerConnectionInboundHandler connectionInboundHandler,
            WorkerConnectionMechanism connectionMechanism,
            DeliveryCommandProcess commandProcess,
            DeliveryReportProcess reportProcess,
            Duration shutdownTimeout
    ) {
        this.adapterId = requireAdapterId(adapterId);
        this.networkServer = Objects.requireNonNull(
                networkServer,
                "networkServer"
        );
        this.connectionInboundHandler = Objects.requireNonNull(
                connectionInboundHandler,
                "connectionInboundHandler"
        );
        this.connectionMechanism = Objects.requireNonNull(
                connectionMechanism,
                "connectionMechanism"
        );
        this.commandProcess = Objects.requireNonNull(
                commandProcess,
                "commandProcess"
        );
        this.reportProcess = Objects.requireNonNull(
                reportProcess,
                "reportProcess"
        );
        this.shutdownTimeout = requirePositive(
                shutdownTimeout,
                "shutdownTimeout"
        );
        reportThread = consumerThread(
                "delivery-report",
                reportProcess::runLoop
        );
        commandThread = consumerThread(
                "delivery-command",
                commandProcess::runLoop
        );
    }

    @Override
    public String adapterId() {
        return adapterId;
    }

    @Override
    public WorkerDeliveryAdapterState state() {
        return state;
    }

    @Override
    public synchronized void start() {
        if (state == WorkerDeliveryAdapterState.RUNNING) {
            return;
        }
        if (state != WorkerDeliveryAdapterState.REGISTERED) {
            throw new IllegalStateException(
                    "Cannot start Adapter from state " + state
            );
        }
        try {
            networkServer.start(connectionInboundHandler);
            reportThread.start();
            commandThread.start();
            state = WorkerDeliveryAdapterState.RUNNING;
        } catch (RuntimeException error) {
            RuntimeException failure = classify(
                    error,
                    WorkerDeliveryAdapterErrorCode.LISTENER_START_FAILED,
                    "netty.start",
                    "Netty Adapter could not start"
            );
            try {
                close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    @Override
    public synchronized void close() {
        if (state == WorkerDeliveryAdapterState.CLOSED) {
            return;
        }
        state = WorkerDeliveryAdapterState.STOPPING;

        boolean interruptedOnEntry = Thread.interrupted();
        RuntimeException failure = interruptedOnEntry
                ? new WorkerDeliveryAdapterException(
                        WorkerDeliveryAdapterErrorCode.SHUTDOWN_INTERRUPTED,
                        "netty.close",
                        "Adapter shutdown was already interrupted",
                        null
                )
                : null;
        commandProcess.stop();
        commandThread.interrupt();
        try {
            networkServer.close();
        } catch (RuntimeException error) {
            failure = accumulate(failure, error);
        } finally {
            connectionMechanism.clear();
        }
        reportProcess.stop();
        reportThread.interrupt();
        failure = joinConsumerThreads(failure);

        state = WorkerDeliveryAdapterState.CLOSED;
        if (interruptedOnEntry) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw classify(
                    failure,
                    WorkerDeliveryAdapterErrorCode.SHUTDOWN_INTERRUPTED,
                    "netty.close",
                    "Netty Adapter could not close cleanly"
            );
        }
    }

    private RuntimeException joinConsumerThreads(
            RuntimeException failure
    ) {
        long deadline = System.nanoTime() + shutdownTimeout.toNanos();
        RuntimeException current = failure;
        boolean interrupted = false;
        boolean allStopped = true;
        for (Thread thread : new Thread[]{commandThread, reportThread}) {
            if (!thread.isAlive()) {
                continue;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                try {
                    join(thread, remaining);
                } catch (InterruptedException error) {
                    interrupted = true;
                    current = accumulate(
                            current,
                            shutdownInterrupted(error)
                    );
                }
            }
            if (thread.isAlive()) {
                allStopped = false;
            }
        }
        if (!allStopped) {
            current = accumulate(current, shutdownTimeout());
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return current;
    }

    private Thread consumerThread(String consumerId, Runnable loop) {
        Thread thread = new Thread(
                loop,
                "worker-delivery-" + adapterId + "-" + consumerId
        );
        thread.setDaemon(true);
        return thread;
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
                "netty.stopConsumerLoops",
                "Adapter consumer loops did not stop within their shared "
                        + "shutdown budget",
                null
        );
    }

    private static WorkerDeliveryAdapterException shutdownInterrupted(
            InterruptedException cause
    ) {
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.SHUTDOWN_INTERRUPTED,
                "netty.stopConsumerLoops",
                "Adapter consumer loop shutdown was interrupted",
                cause
        );
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
        if (current == null) {
            return addition;
        }
        current.addSuppressed(addition);
        return current;
    }

    private static String requireAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must be non-blank");
        }
        return adapterId;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
