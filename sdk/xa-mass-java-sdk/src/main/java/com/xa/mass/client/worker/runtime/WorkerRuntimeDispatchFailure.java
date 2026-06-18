package com.xa.mass.client.worker.runtime;

import com.xa.mass.client.worker.WorkerInvocation;

public record WorkerRuntimeDispatchFailure(
        String workerId,
        String resultCorrelationRef,
        WorkerInvocation invocation,
        Throwable cause
) {
}
