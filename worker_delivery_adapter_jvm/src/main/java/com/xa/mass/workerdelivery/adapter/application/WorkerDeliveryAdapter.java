package com.xa.mass.workerdelivery.adapter.application;

public interface WorkerDeliveryAdapter extends AutoCloseable {

    String adapterId();

    WorkerDeliveryAdapterState state();

    void start();

    @Override
    void close();
}
