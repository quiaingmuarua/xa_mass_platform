package com.xa.mass.client.worker.runtime;

import com.xa.mass.client.http.exception.MassClientException;

public class WorkerRuntimeStartupException extends MassClientException {
    private final WorkerRuntimeStartupFailure failure;

    public WorkerRuntimeStartupException(WorkerRuntimeStartupFailure failure) {
        super("Failed to start polling worker session at " + failure.failedStep()
                + " for worker " + failure.workerId(), failure.cause());
        this.failure = failure;
    }

    public WorkerRuntimeStartupFailure failure() {
        return failure;
    }
}
