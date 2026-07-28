package com.xa.mass.workerdelivery.adapter.application;

public interface WorkerDeliveryAdapterFactory<
        C extends WorkerDeliveryAdapterPrivateConfig> {

    WorkerDeliveryAdapterType adapterType();

    Class<C> privateConfigType();

    WorkerDeliveryAdapter create(
            WorkerDeliveryAdapterRuntimeConfig runtimeConfig,
            C privateConfig
    );
}
