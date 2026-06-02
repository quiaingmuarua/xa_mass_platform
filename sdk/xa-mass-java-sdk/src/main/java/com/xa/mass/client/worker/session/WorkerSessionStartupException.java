package com.xa.mass.client.worker.session;

import com.xa.mass.client.http.exception.MassClientException;

public class WorkerSessionStartupException extends MassClientException {
    private final WorkerSessionStartupFailure failure;

    public WorkerSessionStartupException(WorkerSessionStartupFailure failure) {
        super("Failed to start polling worker session at " + failure.failedStep()
                + " for worker " + failure.workerId(), failure.cause());
        this.failure = failure;
    }

    public WorkerSessionStartupFailure failure() {
        return failure;
    }
}
