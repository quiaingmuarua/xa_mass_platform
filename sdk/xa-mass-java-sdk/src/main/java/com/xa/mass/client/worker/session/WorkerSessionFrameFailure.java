package com.xa.mass.client.worker.session;

public record WorkerSessionFrameFailure(
        String workerId,
        String framePreview,
        int frameLength,
        Throwable cause
) {
}
