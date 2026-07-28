package com.xa.mass.workerdelivery.adapter.application;

import java.util.Objects;

public record WorkerDeliveryAdapterDefinition(
        WorkerDeliveryAdapterType adapterType,
        WorkerDeliveryAdapterRuntimeConfig runtimeConfig,
        WorkerDeliveryAdapterPrivateConfig privateConfig
) {

    public WorkerDeliveryAdapterDefinition {
        Objects.requireNonNull(adapterType, "adapterType");
        Objects.requireNonNull(runtimeConfig, "runtimeConfig");
        Objects.requireNonNull(privateConfig, "privateConfig");
    }
}
