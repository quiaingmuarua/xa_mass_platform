package com.xa.mass.client.worker.runtime;

public interface WorkerRuntimeListener {
    WorkerRuntimeListener NOOP = new WorkerRuntimeListener() {
    };

    default void onFailure(WorkerRuntimeFailureEvent failure) {
    }

    default void onConnectionRecovered(String workerId) {
    }
}
