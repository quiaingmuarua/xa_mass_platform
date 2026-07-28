package com.xa.mass.workerdelivery.adapter.application;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class WorkerDeliveryAdapterManager implements AutoCloseable {

    private final Map<
            WorkerDeliveryAdapterType,
            WorkerDeliveryAdapterFactory<?>
            > factories;
    private WorkerDeliveryAdapter adapter;
    private boolean closed;

    public WorkerDeliveryAdapterManager(
            List<WorkerDeliveryAdapterFactory<?>> factories
    ) {
        Objects.requireNonNull(factories, "factories");
        Map<
                WorkerDeliveryAdapterType,
                WorkerDeliveryAdapterFactory<?>
                > byType = new EnumMap<>(WorkerDeliveryAdapterType.class);
        for (WorkerDeliveryAdapterFactory<?> factory : factories) {
            Objects.requireNonNull(factory, "factory");
            WorkerDeliveryAdapterFactory<?> previous = byType.put(
                    factory.adapterType(),
                    factory
            );
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate Adapter factory: "
                                + factory.adapterType()
                );
            }
        }
        this.factories = Map.copyOf(byType);
    }

    public synchronized void register(
            WorkerDeliveryAdapterDefinition definition
    ) {
        Objects.requireNonNull(definition, "definition");
        if (closed) {
            throw new IllegalStateException(
                    "Worker Delivery Adapter manager is closed"
            );
        }
        if (adapter != null) {
            throw new IllegalStateException(
                    "A Worker Delivery Adapter is already registered"
            );
        }
        WorkerDeliveryAdapterFactory<?> factory = factories.get(
                definition.adapterType()
        );
        if (factory == null) {
            throw new IllegalArgumentException(
                    "No factory for Adapter type: "
                            + definition.adapterType()
            );
        }
        if (definition.privateConfig().getClass()
                != factory.privateConfigType()) {
            throw new IllegalArgumentException(
                    "Private config does not match Adapter type "
                            + definition.adapterType()
            );
        }
        WorkerDeliveryAdapter created = create(
                factory,
                definition
        );
        if (created.adapterType() != definition.adapterType()
                || !created.endpointManagerId().equals(
                definition.runtimeConfig().endpointManagerId()
        )) {
            created.close();
            throw new IllegalStateException(
                    "Adapter factory returned inconsistent identity"
            );
        }
        adapter = created;
    }

    public synchronized void start() {
        WorkerDeliveryAdapter current = requireAdapter();
        if (current.state() == WorkerDeliveryAdapterState.RUNNING) {
            return;
        }
        if (current.state() != WorkerDeliveryAdapterState.REGISTERED) {
            throw new IllegalStateException(
                    "Cannot start Adapter from state " + current.state()
            );
        }
        try {
            current.start();
        } catch (RuntimeException error) {
            current.close();
            throw error;
        }
    }

    public synchronized WorkerDeliveryAdapterState state() {
        if (adapter == null) {
            if (closed) {
                return WorkerDeliveryAdapterState.CLOSED;
            }
            throw new IllegalStateException(
                    "No Worker Delivery Adapter is registered"
            );
        }
        return adapter.state();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (adapter != null) {
            adapter.close();
        }
    }

    private WorkerDeliveryAdapter requireAdapter() {
        if (adapter == null) {
            throw new IllegalStateException(
                    "No Worker Delivery Adapter is registered"
            );
        }
        return adapter;
    }

    @SuppressWarnings("unchecked")
    private static <C extends WorkerDeliveryAdapterPrivateConfig>
    WorkerDeliveryAdapter create(
            WorkerDeliveryAdapterFactory<?> factory,
            WorkerDeliveryAdapterDefinition definition
    ) {
        WorkerDeliveryAdapterFactory<C> typedFactory =
                (WorkerDeliveryAdapterFactory<C>) factory;
        return typedFactory.create(
                definition.runtimeConfig(),
                (C) definition.privateConfig()
        );
    }
}
