package com.xa.mass.workerdelivery.adapter.application;

public interface WorkerDeliveryAdapter extends AutoCloseable {

    WorkerDeliveryAdapterType adapterType();

    String endpointManagerId();

    WorkerDeliveryAdapterState state();

    void start();

    @Override
    void close();
}
