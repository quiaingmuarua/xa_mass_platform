package com.xa.mass.server.assembly.runtime;

import com.xa.mass.workerdelivery.adapter.application
        .WorkerDeliveryAdapterManager;
import com.xa.mass.server.delivery.adapter.WorkerRouteVerificationBatcher;
import java.util.Objects;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.SmartLifecycle;

public final class ServerConfiguredRuntimeLifecycleHost
        implements SmartLifecycle {

    private final ServerWorkerGroupInitializer groupInitializer;
    private final WorkerDeliveryAdapterManager adapterManager;
    private final WorkerRouteVerificationBatcher routeVerificationBatcher;
    private boolean started;
    private boolean closed;

    public ServerConfiguredRuntimeLifecycleHost(
            ServerWorkerGroupInitializer groupInitializer,
            WorkerDeliveryAdapterManager adapterManager,
            WorkerRouteVerificationBatcher routeVerificationBatcher
    ) {
        this.groupInitializer = Objects.requireNonNull(
                groupInitializer,
                "groupInitializer"
        );
        this.adapterManager = Objects.requireNonNull(
                adapterManager,
                "adapterManager"
        );
        this.routeVerificationBatcher = Objects.requireNonNull(
                routeVerificationBatcher,
                "routeVerificationBatcher"
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
            routeVerificationBatcher.start();
            adapterManager.start();
            started = true;
        } catch (RuntimeException failure) {
            closeAndSuppress(adapterManager, failure);
            closeAndSuppress(routeVerificationBatcher, failure);
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
        routeVerificationBatcher.stopIngress();
        RuntimeException failure = null;
        try {
            adapterManager.close();
        } catch (RuntimeException error) {
            failure = error;
        }
        try {
            routeVerificationBatcher.close();
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
