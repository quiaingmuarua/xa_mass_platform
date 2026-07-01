package com.xa.mass.task.runtime;

public interface TaskRuntimeProgressPort {

    TaskRuntimeProgressSnapshot progressSnapshot(String taskId);
}
