package com.xa.mass.client.worker.session;

public record WorkerSessionFrameFailure(
        String workerId,
        String frame,
        Throwable cause
) {
}
