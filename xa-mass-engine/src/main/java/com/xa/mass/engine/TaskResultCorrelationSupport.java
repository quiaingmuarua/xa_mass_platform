package com.xa.mass.engine;

import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.runtime.result.TaskResultCorrelation;
import com.xa.mass.runtime.api.ActiveLeaseRecord;

final class TaskResultCorrelationSupport {

    private TaskResultCorrelationSupport() {
    }

    static TaskResultCorrelation fromRuntimeState(String taskId,
                                                  String messageId,
                                                  TaskMsg taskMsg,
                                                  ActiveLeaseRecord activeLease) {
        return new TaskResultCorrelation(
                taskId,
                messageId,
                activeLease != null,
                taskMsg != null ? taskMsg.latestAttemptId() : null,
                activeLease != null ? activeLease.leaseToken() : null,
                activeLease != null ? activeLease.workerId() : null,
                activeLease != null ? activeLease.workerContextId() : null,
                activeLease != null ? activeLease.batchId() : null
        );
    }
}
