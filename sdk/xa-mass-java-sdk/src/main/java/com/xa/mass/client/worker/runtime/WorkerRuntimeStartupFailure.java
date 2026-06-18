package com.xa.mass.client.worker.runtime;

public record WorkerRuntimeStartupFailure(
        String workerId,
        WorkerRuntimeStartupStep failedStep,
        WorkerRuntimeStartupStep lastSuccessfulStep,
        Throwable cause
) {
}
