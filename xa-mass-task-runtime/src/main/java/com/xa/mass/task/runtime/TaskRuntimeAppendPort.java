package com.xa.mass.task.runtime;

public interface TaskRuntimeAppendPort {

    AppendBatchOutcome appendBatch(AppendBatchCommand command);
}
