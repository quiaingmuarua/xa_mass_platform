package com.xa.mass.workerdelivery.adapter.application;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ScheduledWorkerDeliveryAdapter
        implements WorkerDeliveryAdapter {

    private final WorkerDeliveryAdapterType adapterType;
    private final String endpointManagerId;
    private final Duration dispatchInterval;
    private final WorkerDeliveryAdapterCore core;
    private volatile WorkerDeliveryAdapterState state =
            WorkerDeliveryAdapterState.REGISTERED;
    private ScheduledExecutorService executor;

    public ScheduledWorkerDeliveryAdapter(
            WorkerDeliveryAdapterType adapterType,
            String endpointManagerId,
            Duration dispatchInterval,
            WorkerDeliveryAdapterCore core
    ) {
        this.adapterType = Objects.requireNonNull(
                adapterType,
                "adapterType"
        );
        if (endpointManagerId == null || endpointManagerId.isBlank()) {
            throw new IllegalArgumentException(
                    "endpointManagerId must be non-blank"
            );
        }
        this.endpointManagerId = endpointManagerId;
        this.dispatchInterval = Objects.requireNonNull(
                dispatchInterval,
                "dispatchInterval"
        );
        if (dispatchInterval.isZero() || dispatchInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "dispatchInterval must be positive"
            );
        }
        this.core = Objects.requireNonNull(core, "core");
    }

    @Override
    public WorkerDeliveryAdapterType adapterType() {
        return adapterType;
    }

    @Override
    public String endpointManagerId() {
        return endpointManagerId;
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
        ScheduledExecutorService created =
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "worker-delivery-adapter"
                    );
                    thread.setDaemon(true);
                    return thread;
                });
        executor = created;
        state = WorkerDeliveryAdapterState.RUNNING;
        try {
            created.scheduleWithFixedDelay(
                    this::dispatchSafely,
                    0,
                    dispatchInterval.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (RuntimeException error) {
            state = WorkerDeliveryAdapterState.STOPPING;
            created.shutdownNow();
            try {
                core.close();
            } finally {
                state = WorkerDeliveryAdapterState.CLOSED;
                executor = null;
            }
            throw error;
        }
    }

    @Override
    public synchronized void close() {
        if (state == WorkerDeliveryAdapterState.CLOSED) {
            return;
        }
        state = WorkerDeliveryAdapterState.STOPPING;
        ScheduledExecutorService current = executor;
        executor = null;
        if (current != null) {
            current.shutdownNow();
        }
        try {
            core.close();
        } finally {
            state = WorkerDeliveryAdapterState.CLOSED;
        }
    }

    private void dispatchSafely() {
        if (state != WorkerDeliveryAdapterState.RUNNING) {
            return;
        }
        try {
            core.dispatchOnce();
        } catch (RuntimeException ignored) {
            // The next bounded round retries pending results and Gateway I/O.
        }
    }
}
