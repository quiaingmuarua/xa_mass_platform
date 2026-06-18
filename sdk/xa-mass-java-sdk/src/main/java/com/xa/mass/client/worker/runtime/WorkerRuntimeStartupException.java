package com.xa.mass.client.worker.runtime;

import com.xa.mass.client.http.exception.MassClientException;

public class WorkerRuntimeStartupException extends MassClientException {
    private final WorkerRuntimeFailureEvent failure;

    public WorkerRuntimeStartupException(WorkerRuntimeFailureEvent failure) {
        super("Failed to start worker runtime at " + failure.reason()
                + " for worker " + failure.workerId(), failure.cause());
        this.failure = failure;
    }

    public WorkerRuntimeFailureEvent failure() {
        return failure;
    }
}
