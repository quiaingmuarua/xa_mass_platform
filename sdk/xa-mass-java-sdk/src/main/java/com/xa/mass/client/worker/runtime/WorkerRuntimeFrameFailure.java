package com.xa.mass.client.worker.runtime;

public record WorkerRuntimeFrameFailure(
        String workerId,
        String framePreview,
        int frameLength,
        Throwable cause
) {
}
