package com.xa.mass.workerdelivery.adapter.netty;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapter;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterState;
import com.xa.mass.workerdelivery.adapter.netty.internal.connection.WorkerConnectionMechanism;
import com.xa.mass.workerdelivery.adapter.netty.internal.gateway.DeliveryCommandPump;
import com.xa.mass.workerdelivery.adapter.netty.internal.gateway.DeliveryReportPump;
import com.xa.mass.workerdelivery.adapter.netty.internal.network.NettyWorkerServer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class NettyWorkerDeliveryAdapter implements WorkerDeliveryAdapter {

    private static final System.Logger LOGGER = System.getLogger(
            NettyWorkerDeliveryAdapter.class.getName()
    );

    private final String adapterId;
    private final Duration commandPumpInterval;
    private final Duration reportSubmitInterval;
    private final Duration shutdownTimeout;
    private final WorkerConnectionMechanism connectionMechanism;
    private final DeliveryCommandPump commandPump;
    private final DeliveryReportPump reportPump;
    private final NettyWorkerServer networkServer;
    private volatile WorkerDeliveryAdapterState state =
            WorkerDeliveryAdapterState.REGISTERED;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> commandTask;
    private ScheduledFuture<?> reportTask;

    NettyWorkerDeliveryAdapter(
            String adapterId,
            Duration commandLoopInterval,
            Duration reportSubmitInterval,
            Duration shutdownTimeout,
            NettyWorkerServer networkServer,
            WorkerConnectionMechanism connectionMechanism,
            DeliveryCommandPump commandPump,
            DeliveryReportPump reportPump
    ) {
        this.adapterId = requireAdapterId(adapterId);
        commandPumpInterval = Objects.requireNonNull(
                commandLoopInterval,
                "commandLoopInterval"
        );
        this.reportSubmitInterval = Objects.requireNonNull(
                reportSubmitInterval,
                "reportSubmitInterval"
        );
        this.shutdownTimeout = Objects.requireNonNull(
                shutdownTimeout,
                "shutdownTimeout"
        );
        this.networkServer = Objects.requireNonNull(
                networkServer,
                "networkServer"
        );
        this.connectionMechanism = Objects.requireNonNull(
                connectionMechanism,
                "connectionMechanism"
        );
        this.commandPump = Objects.requireNonNull(commandPump, "commandPump");
        this.reportPump = Objects.requireNonNull(reportPump, "reportPump");
    }

    @Override
    public String adapterId() {
        return adapterId;
    }

    @Override
    public WorkerDeliveryAdapterState state() {
        return state;
    }

    int activeConnectionCount() {
        return connectionMechanism.activeConnectionCount();
    }

    int trackedConnectionCount() {
        return networkServer.trackedConnectionCount();
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
            scheduler = Executors.newScheduledThreadPool(
                    2,
                    daemonThreadFactory(adapterId + "-loop")
            );
            networkServer.start(connectionMechanism);
            state = WorkerDeliveryAdapterState.RUNNING;
            commandTask = scheduler.scheduleWithFixedDelay(
                    this::runCommandPumpSafely,
                    0,
                    commandPumpInterval.toMillis(),
                    TimeUnit.MILLISECONDS
            );
            reportTask = scheduler.scheduleWithFixedDelay(
                    this::runReportPumpSafely,
                    reportSubmitInterval.toMillis(),
                    reportSubmitInterval.toMillis(),
                    TimeUnit.MILLISECONDS
            );
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
    public void close() {
        ScheduledExecutorService stoppingScheduler;
        ScheduledFuture<?> stoppingCommandTask;
        ScheduledFuture<?> stoppingReportTask;
        synchronized (this) {
            if (state == WorkerDeliveryAdapterState.CLOSED) {
                return;
            }
            state = WorkerDeliveryAdapterState.STOPPING;
            stoppingScheduler = scheduler;
            stoppingCommandTask = commandTask;
            stoppingReportTask = reportTask;
            scheduler = null;
            commandTask = null;
            reportTask = null;
        }

        boolean interruptedOnEntry = Thread.interrupted();
        RuntimeException failure = interruptedOnEntry
                ? new WorkerDeliveryAdapterException(
                        WorkerDeliveryAdapterErrorCode.SHUTDOWN_INTERRUPTED,
                        "netty.stopScheduler",
                        "Adapter shutdown was already interrupted",
                        null
                )
                : null;
        cancel(stoppingCommandTask);
        commandPump.close();
        try {
            networkServer.close();
        } catch (RuntimeException error) {
            failure = accumulate(failure, error);
        } finally {
            connectionMechanism.clear();
        }
        cancel(stoppingReportTask);
        failure = stopScheduler(stoppingScheduler, failure);
        try {
            reportPump.stopAccepting();
            reportPump.closeAndFlush();
        } catch (RuntimeException error) {
            failure = accumulate(failure, error);
        }

        synchronized (this) {
            state = WorkerDeliveryAdapterState.CLOSED;
        }
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

    private boolean isRunning() {
        return state == WorkerDeliveryAdapterState.RUNNING;
    }

    private void runCommandPumpSafely() {
        if (!isRunning()) {
            return;
        }
        try {
            commandPump.run();
        } catch (RuntimeException error) {
            logPumpFailure(error, "commandPump", "command pump");
        }
    }

    private void runReportPumpSafely() {
        if (!isRunning()) {
            return;
        }
        try {
            reportPump.run();
        } catch (RuntimeException error) {
            logPumpFailure(error, "reportPump", "report pump");
        }
    }

    private void logPumpFailure(
            RuntimeException error,
            String action,
            String description
    ) {
        WorkerDeliveryAdapterException failure = classify(
                error,
                WorkerDeliveryAdapterErrorCode.DELIVERY_INTERRUPTED,
                "netty." + action,
                "Netty Adapter " + description + " failed"
        );
        LOGGER.log(
                System.Logger.Level.WARNING,
                "errorCode={0} operation={1} adapterId={2} message={3}",
                failure.errorCode().code(),
                failure.operation(),
                adapterId,
                failure.getMessage()
        );
    }

    private RuntimeException stopScheduler(
            ScheduledExecutorService executor,
            RuntimeException failure
    ) {
        if (executor == null) {
            return failure;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(
                    shutdownTimeout.toMillis(),
                    TimeUnit.MILLISECONDS
            )) {
                executor.shutdownNow();
                executor.awaitTermination(
                        shutdownTimeout.toMillis(),
                        TimeUnit.MILLISECONDS
                );
            }
            return failure;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
            return accumulate(
                    failure,
                    new WorkerDeliveryAdapterException(
                            WorkerDeliveryAdapterErrorCode
                                    .SHUTDOWN_INTERRUPTED,
                            "netty.stopScheduler",
                            "Adapter scheduler shutdown was interrupted",
                            error
                    )
            );
        }
    }

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(true);
        }
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

    private static String requireAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException("adapterId must be non-blank");
        }
        return adapterId;
    }
}
