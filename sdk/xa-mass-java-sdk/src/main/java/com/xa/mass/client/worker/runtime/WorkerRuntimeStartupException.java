package com.xa.mass.client.worker.runtime;

import com.xa.mass.client.http.exception.MassClientException;

public class WorkerRuntimeStartupException extends MassClientException {
    private final WorkerRuntimeFailureEvent failure;

    WorkerRuntimeStartupException(WorkerRuntimeFailureEvent failure, Throwable cause) {
        super("Failed to start worker runtime at " + failure.reason()
                + " for worker " + failure.workerId(), cause);
        this.failure = failure;
    }

    public WorkerRuntimeFailureEvent failure() {
        return failure;
    }
}
