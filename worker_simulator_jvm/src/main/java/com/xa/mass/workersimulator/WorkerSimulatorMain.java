package com.xa.mass.workersimulator;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** Standalone process entry for inventory-backed device and business simulation. */
public final class WorkerSimulatorMain {

    private static final System.Logger LOGGER = System.getLogger(
            WorkerSimulatorMain.class.getName()
    );

    private WorkerSimulatorMain() {
    }

    public static void main(String[] arguments) throws Exception {
        Path path = configPath(arguments);
        if (path == null) {
            System.out.println("Usage: xa-mass-worker-simulator --config <file> | --help");
            return;
        }
        WorkerSimulatorConfig config = WorkerSimulatorConfig.load(path);
        WorkerSimulatorStartupPlan startupPlan = config.startupPlan();
        WorkerSimulator workers = WorkerSimulator.create(config);
        WorkerSimulatorScheduledStops scheduledStops = null;
        WorkerSimulatorControlServer controlServer = null;
        Thread shutdownHook = null;
        try {
            scheduledStops = new WorkerSimulatorScheduledStops(workers);
            controlServer = WorkerSimulatorControlServer.open(
                    config.controlPort(),
                    workers,
                    scheduledStops
            );
            workers.start(startupPlan);
            scheduleStartupStops(startupPlan, scheduledStops);
            WorkerSimulatorScheduledStops finalScheduledStops =
                    scheduledStops;
            WorkerSimulatorControlServer finalControlServer = controlServer;
            shutdownHook = new Thread(
                    () -> closeHost(
                            finalControlServer,
                            finalScheduledStops,
                            workers
                    ),
                    "worker-simulator-shutdown"
            );
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            controlServer.start();
            LOGGER.log(
                    System.Logger.Level.INFO,
                    "WORKER_SIMULATOR_READY control={0}/lab "
                            + "initialWorkerCount={1} scheduledStopCount={2} groupCount={3}",
                    controlServer.baseUri(),
                    workers.initialWorkerCount(),
                    startupPlan.scheduledStops().size(),
                    config.workerGroups().size()
            );
            new CountDownLatch(1).await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (shutdownHook != null) {
                removeShutdownHook(shutdownHook);
            }
            closeHost(controlServer, scheduledStops, workers);
        }
    }

    private static void removeShutdownHook(Thread shutdownHook) {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignoredDuringShutdown) {
            // The registered hook owns the same idempotent close operation.
        }
    }

    private static void scheduleStartupStops(
            WorkerSimulatorStartupPlan startupPlan,
            WorkerSimulatorScheduledStops scheduledStops
    ) {
        for (WorkerSimulatorStartupPlan.ScheduledStop stop
                : startupPlan.scheduledStops()) {
            WorkerSimulatorCoordinate worker = stop.worker();
            if (!scheduledStops.schedule(
                    worker.workerGroupId(),
                    worker.labWorkerKey(),
                    stop.delayMillis()
            )) {
                throw new IllegalStateException(
                        "Duplicate startup scheduled stop for "
                                + worker.workerGroupId()
                                + "/"
                                + worker.labWorkerKey()
                );
            }
        }
    }

    private static void closeHost(
            WorkerSimulatorControlServer controlServer,
            WorkerSimulatorScheduledStops scheduledStops,
            WorkerSimulator workers
    ) {
        RuntimeException failure = null;
        if (controlServer != null) {
            try {
                controlServer.close();
            } catch (RuntimeException error) {
                failure = error;
            }
        }
        if (scheduledStops != null) {
            try {
                scheduledStops.close();
            } catch (RuntimeException error) {
                failure = accumulate(failure, error);
            }
        }
        try {
            workers.close();
        } catch (RuntimeException error) {
            failure = accumulate(failure, error);
        }
        if (controlServer != null) controlServer.awaitClosed();
        if (failure != null) {
            throw failure;
        }
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

    static Path configPath(String[] arguments) {
        if (arguments == null) throw new IllegalArgumentException("arguments must be present");
        if (arguments.length == 1 && "--help".equals(arguments[0])) return null;
        String path;
        if (arguments.length == 2 && "--config".equals(arguments[0])) {
            path = arguments[1];
        } else if (arguments.length == 1 && arguments[0] != null && arguments[0].startsWith("--config=")) {
            path = arguments[0].substring("--config=".length());
        } else {
            throw new IllegalArgumentException("Use --config <file> or --help; no other arguments are supported");
        }
        if (path == null || path.isBlank() || path.startsWith("--")) {
            throw new IllegalArgumentException("--config requires a file path");
        }
        return Path.of(path);
    }
}
