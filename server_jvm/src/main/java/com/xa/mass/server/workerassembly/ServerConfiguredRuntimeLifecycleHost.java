package com.xa.mass.server.workerassembly;

import com.xa.mass.workerdelivery.adapter.application
        .WorkerDeliveryAdapterManager;
import java.util.Objects;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;

public final class ServerConfiguredRuntimeLifecycleHost
        implements SmartLifecycle {

    private final ServerWorkerGroupInitializer groupInitializer;
    private final WorkerDeliveryAdapterManager adapterManager;
    private boolean started;
    private boolean closed;

    public ServerConfiguredRuntimeLifecycleHost(
            ServerWorkerGroupInitializer groupInitializer,
            WorkerDeliveryAdapterManager adapterManager
    ) {
        this.groupInitializer = Objects.requireNonNull(
                groupInitializer,
                "groupInitializer"
        );
        this.adapterManager = Objects.requireNonNull(
                adapterManager,
                "adapterManager"
        );
    }

    @Override
    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException(
                    "Server configured Runtime lifecycle is closed"
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
            started = true;
        } catch (RuntimeException failure) {
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
        adapterManager.close();
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
