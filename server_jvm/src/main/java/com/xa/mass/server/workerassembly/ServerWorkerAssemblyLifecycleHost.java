package com.xa.mass.server.workerassembly;

import com.xa.mass.scenarioworkers.ScenarioWorkers;
import com.xa.mass.workerdelivery.adapter.application
        .WorkerDeliveryAdapterManager;
import java.util.Objects;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;

public final class ServerWorkerAssemblyLifecycleHost
        implements SmartLifecycle {

    private final ServerWorkerGroupInitializer groupInitializer;
    private final WorkerDeliveryAdapterManager adapterManager;
    private final ScenarioWorkers scenarioWorkers;
    private boolean started;
    private boolean closed;

    public ServerWorkerAssemblyLifecycleHost(
            ServerWorkerGroupInitializer groupInitializer,
            WorkerDeliveryAdapterManager adapterManager,
            ScenarioWorkers scenarioWorkers
    ) {
        this.groupInitializer = Objects.requireNonNull(
                groupInitializer,
                "groupInitializer"
        );
        this.adapterManager = Objects.requireNonNull(
                adapterManager,
                "adapterManager"
        );
        this.scenarioWorkers = Objects.requireNonNull(
                scenarioWorkers,
                "scenarioWorkers"
        );
    }

    @Override
    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException(
                    "Server Worker assembly lifecycle is closed"
            );
        }
        if (started) {
            return;
        }

        try {
            groupInitializer.initialize();
        } catch (RuntimeException failure) {
            closed = true;
            throw failure;
        }

        try {
            adapterManager.start();
            scenarioWorkers.start();
            started = true;
        } catch (RuntimeException failure) {
            closeAndSuppress(scenarioWorkers, failure);
            closeAndSuppress(adapterManager, failure);
            closed = true;
            throw failure;
        }
    }

    @Override
    public synchronized void stop() {
        if (closed) {
            return;
        }
        closed = true;
        started = false;
        RuntimeException failure = null;
        try {
            scenarioWorkers.close();
        } catch (RuntimeException error) {
            failure = error;
        }
        try {
            adapterManager.close();
        } catch (RuntimeException error) {
            if (failure == null) {
                failure = error;
            } else {
                failure.addSuppressed(error);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return started && !closed;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        // Start only after the web server has completed its lifecycle phase.
        // Stop before graceful HTTP shutdown; lower-phase infrastructure is
        // then released by its own lifecycle owner.
        return WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE + 1;
    }

    private static void closeAndSuppress(
            AutoCloseable closeable,
            RuntimeException failure
    ) {
        try {
            closeable.close();
        } catch (Exception closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
