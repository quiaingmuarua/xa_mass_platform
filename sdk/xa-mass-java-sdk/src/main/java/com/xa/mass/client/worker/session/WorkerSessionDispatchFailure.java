package com.xa.mass.client.worker.session;

import com.xa.mass.client.worker.ResultCorrelationRef;
import com.xa.mass.client.worker.handler.WorkerInvocation;

public record WorkerSessionDispatchFailure(
        String workerId,
        ResultCorrelationRef resultCorrelationRef,
        WorkerInvocation invocation,
        Throwable cause
) {
}
