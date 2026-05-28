package com.xa.mass.client.worker.session;

public record WorkerSessionStartupFailure(
        String workerId,
        WorkerSessionStartupStep failedStep,
        WorkerSessionStartupStep lastSuccessfulStep,
        Throwable cause
) {
}
