package com.xa.mass.workerdelivery.adapter.application;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class WorkerDeliveryAdapterManager implements AutoCloseable {

    private final LinkedHashMap<String, WorkerDeliveryAdapter> adapters =
            new LinkedHashMap<>();
    private boolean started;
    private boolean closed;

    public synchronized void register(WorkerDeliveryAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        if (closed) {
            throw new IllegalStateException(
                    "Worker Delivery Adapter manager is closed"
            );
        }
        if (started) {
            throw new IllegalStateException(
                    "Adapters can only be registered before start"
            );
        }
        String adapterId = requireAdapterId(adapter.adapterId());
        if (adapter.state() != WorkerDeliveryAdapterState.REGISTERED) {
            throw new IllegalArgumentException(
                    "Registered Adapter must be in REGISTERED state"
            );
        }
        if (adapters.putIfAbsent(adapterId, adapter) != null) {
            throw new IllegalArgumentException(
                    "Duplicate Adapter ID: " + adapterId
            );
        }
    }

    public synchronized WorkerDeliveryAdapter requireAdapter(
            String adapterId
    ) {
        WorkerDeliveryAdapter adapter = adapters.get(
                requireAdapterId(adapterId)
        );
        if (adapter == null) {
            throw new IllegalArgumentException(
                    "Unknown Adapter ID: " + adapterId
            );
        }
        return adapter;
    }

    public synchronized Map<String, WorkerDeliveryAdapter> adapters() {
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(adapters)
        );
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException(
                    "Worker Delivery Adapter manager is closed"
            );
        }
        if (started) {
            return;
        }
        ArrayList<WorkerDeliveryAdapter> startedAdapters =
                new ArrayList<>();
        WorkerDeliveryAdapter starting = null;
        try {
            for (WorkerDeliveryAdapter adapter : adapters.values()) {
                starting = adapter;
                adapter.start();
                startedAdapters.add(adapter);
            }
            started = true;
        } catch (RuntimeException startFailure) {
            closed = true;
            if (starting != null
                    && !startedAdapters.contains(starting)) {
                closeAndSuppress(starting, startFailure);
            }
            for (int index = startedAdapters.size() - 1;
                    index >= 0;
                    index--) {
                closeAndSuppress(
                        startedAdapters.get(index),
                        startFailure
                );
            }
            throw startFailure;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        var registered = new ArrayList<>(adapters.values());
        for (int index = registered.size() - 1; index >= 0; index--) {
            try {
                registered.get(index).close();
            } catch (RuntimeException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static String requireAdapterId(String adapterId) {
        if (adapterId == null || adapterId.isBlank()) {
            throw new IllegalArgumentException(
                    "adapterId must be non-blank"
            );
        }
        if (SYSTEM_POLLING_ENDPOINT_MANAGER_ID.equals(adapterId)) {
            throw new IllegalArgumentException(
                    "system-polling cannot own an active Adapter"
            );
        }
        return adapterId;
    }

    private static void closeAndSuppress(
            WorkerDeliveryAdapter adapter,
            RuntimeException failure
    ) {
        try {
            adapter.close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
