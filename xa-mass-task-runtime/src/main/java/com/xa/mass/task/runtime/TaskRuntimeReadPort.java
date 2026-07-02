package com.xa.mass.task.runtime;

import java.util.Optional;

public interface TaskRuntimeReadPort {

    Optional<FinalResultRow> getFinalResultByMessageId(String taskId, String messageId);

    default ActiveTaskWorkSnapshot activeWorkForTask(String taskId, int limit) {
        throw new UnsupportedOperationException("activeWorkForTask is not implemented by this runtime");
    }

    ResultCorrelationSnapshot resultCorrelation(String taskId, String messageId);

    TaskRuntimeProgressSnapshot progressSnapshot(String taskId);
}
