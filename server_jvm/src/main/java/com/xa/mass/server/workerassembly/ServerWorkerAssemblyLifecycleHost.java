package com.xa.mass.server.workerassembly;

import com.xa.mass.workerdelivery.adapter.application
        .WorkerDeliveryAdapterManager;
import java.util.Objects;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.DisposableBean;

public final class ServerWorkerAssemblyLifecycleHost
        implements ApplicationRunner, DisposableBean {

    private final WorkerDeliveryAdapterManager adapterManager;
    private final ServerWorkerBundleManager bundleManager;
    private boolean started;
    private boolean closed;

    public ServerWorkerAssemblyLifecycleHost(
            WorkerDeliveryAdapterManager adapterManager,
            ServerWorkerBundleManager bundleManager
    ) {
        this.adapterManager = Objects.requireNonNull(
                adapterManager,
                "adapterManager"
        );
        this.bundleManager = Objects.requireNonNull(
                bundleManager,
                "bundleManager"
        );
    }

    @Override
    public void run(ApplicationArguments arguments) {
        start();
    }

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
            adapterManager.start();
            bundleManager.start();
            started = true;
        } catch (RuntimeException failure) {
            closeAndSuppress(bundleManager, failure);
            closeAndSuppress(adapterManager, failure);
            closed = true;
            throw failure;
        }
    }

    @Override
    public synchronized void destroy() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        try {
            bundleManager.close();
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
