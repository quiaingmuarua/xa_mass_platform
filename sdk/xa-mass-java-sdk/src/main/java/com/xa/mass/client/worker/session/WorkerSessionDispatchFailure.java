package com.xa.mass.client.worker.session;

import com.xa.mass.client.worker.handler.DispatchContext;

public record WorkerSessionDispatchFailure(
        DispatchContext dispatch,
        Throwable cause
) {
}
