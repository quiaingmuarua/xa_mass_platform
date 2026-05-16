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
        if (activeLease == null) {
            return TaskResultCorrelation.noActiveLease(taskId, messageId);
        }
        String runtimeAttemptId = TaskWorkAttemptIdSupport.runtimeAttemptId(
                messageId,
                Math.max(1, activeLease.retryCount() + 1),
                activeLease
        );
        String resolvedAttemptId = projectedAttemptId != null && !projectedAttemptId.isBlank()
                ? projectedAttemptId
                : runtimeAttemptId;
        if (activeLease.workerContextId() == null || activeLease.workerContextId().isBlank()) {
            return TaskResultCorrelation.workerLevel(
                    taskId,
                    messageId,
                    resolvedAttemptId,
                    activeLease.leaseToken(),
                    activeLease.workerId(),
                    activeLease.batchId()
            );
        }
        return TaskResultCorrelation.legacyContextBacked(
                taskId,
                messageId,
                resolvedAttemptId,
                activeLease.leaseToken(),
                activeLease.workerId(),
                activeLease.workerContextId(),
                activeLease.batchId()
        );
    }
}
