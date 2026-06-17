package com.xa.mass.client.worker.session;

import com.xa.mass.client.worker.WorkerInvocation;

public record WorkerSessionDispatchFailure(
        String workerId,
        String resultCorrelationRef,
        WorkerInvocation invocation,
        Throwable cause
) {
}
