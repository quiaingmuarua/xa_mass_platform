package com.xa.mass.task.runtime;

public interface TaskRuntimeResultPort {

    MessageFinalityOutcome applyResult(ResultApplyCommand command);

    ResultCorrelationSnapshot getResultCorrelation(String taskId, String messageId);
}
