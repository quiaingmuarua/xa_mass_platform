package com.xa.mass.task.runtime.command;

import com.xa.mass.task.runtime.AppendBatchOutcome;
import com.xa.mass.task.runtime.AppendItemInput;

import java.util.List;

public interface TaskRuntimeCommandPort {

    TaskRuntimeCommandOutcome create(String taskId);

    TaskRuntimeCommandOutcome approve(String taskId);

    TaskRuntimeCommandOutcome reject(String taskId);

    TaskRuntimeCommandOutcome block(String taskId);

    TaskRuntimeCommandOutcome pause(String taskId);

    TaskRuntimeCommandOutcome resume(String taskId);

    TaskRuntimeCommandOutcome cancel(String taskId);

    TaskRuntimeCommandOutcome terminate(String taskId, String reasonCode);

    AppendBatchOutcome append(String taskId, List<AppendItemInput> items, int maxBatchSize);
}
