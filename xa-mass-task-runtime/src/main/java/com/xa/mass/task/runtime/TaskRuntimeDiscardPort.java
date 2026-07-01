package com.xa.mass.task.runtime;

public interface TaskRuntimeDiscardPort {

    DiscardTaskRuntimeOutcome discardTaskRuntime(DiscardTaskRuntimeCommand command);

    DiscardTaskWorkOutcome discardTaskWork(DiscardTaskWorkCommand command);
}
