package com.xa.mass.engine;

import com.xa.mass.base.runtime.result.TaskResultCorrelation;
import com.xa.mass.task.runtime.ActiveLeaseRepairCandidate;

final class TaskResultCorrelationSupport {

    private TaskResultCorrelationSupport() {
    }

    static TaskResultCorrelation fromRuntimeState(String taskId,
                                                  String messageId,
                                                  String projectedAttemptId,
                                                  ActiveLeaseRepairCandidate activeLease) {
        if (activeLease == null) {
            return TaskResultCorrelation.noActiveLease(taskId, messageId);
        }
        String runtimeAttemptId = TaskWorkAttemptIdSupport.runtimeAttemptId(
                messageId,
                activeLease.attemptNo(),
                activeLease
        );
        String resolvedAttemptId = projectedAttemptId != null && !projectedAttemptId.isBlank()
                ? projectedAttemptId
                : runtimeAttemptId;
        return TaskResultCorrelation.workerLevel(
                taskId,
                messageId,
                resolvedAttemptId,
                activeLease.leaseToken(),
                activeLease.workerId(),
                activeLease.batchId()
        );
    }
}
