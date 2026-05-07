package com.xa.mass.engine;

import com.xa.mass.base.runtime.result.TaskResultCorrelation;
import com.xa.mass.runtime.api.ActiveLeaseRecord;

final class TaskResultCorrelationSupport {

    private TaskResultCorrelationSupport() {
    }

    static TaskResultCorrelation fromRuntimeState(String taskId,
                                                  String messageId,
                                                  String projectedAttemptId,
                                                  ActiveLeaseRecord activeLease) {
        String runtimeAttemptId = activeLease != null
                ? TaskMessageAttemptSupport.runtimeAttemptId(
                messageId,
                Math.max(1, activeLease.retryCount() + 1),
                activeLease
        )
                : null;
        return new TaskResultCorrelation(
                taskId,
                messageId,
                activeLease != null,
                projectedAttemptId != null && !projectedAttemptId.isBlank()
                        ? projectedAttemptId
                        : runtimeAttemptId,
                activeLease != null ? activeLease.leaseToken() : null,
                activeLease != null ? activeLease.workerId() : null,
                activeLease != null ? activeLease.workerContextId() : null,
                activeLease != null ? activeLease.batchId() : null
        );
    }
}
