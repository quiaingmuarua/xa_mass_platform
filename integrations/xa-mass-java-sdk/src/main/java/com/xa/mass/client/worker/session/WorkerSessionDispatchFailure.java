package com.xa.mass.client.worker.session;

public record WorkerSessionDispatchFailure(
        DispatchContext dispatch,
        Throwable cause
) {
}
