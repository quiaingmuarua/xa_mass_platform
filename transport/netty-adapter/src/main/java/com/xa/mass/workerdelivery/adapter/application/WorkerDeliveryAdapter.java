package com.xa.mass.workerdelivery.adapter.application;

public interface WorkerDeliveryAdapter extends AutoCloseable {

    String adapterId();

    WorkerDeliveryAdapterState state();

    void start();

    /** Revokes local route admission before requesting terminal physical closes. */
    void revokeWorkerConnection(String workerId, long cooldownUntilMillis);

    @Override
    void close();
}
