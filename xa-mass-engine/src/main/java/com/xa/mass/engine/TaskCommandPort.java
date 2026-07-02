package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.engine.model.TaskAppendOutcome;
import com.xa.mass.engine.model.TaskCommandOutcome;
import com.xa.mass.engine.model.TaskDefinitionPatch;

import java.util.List;
import java.util.Map;

/**
 * Narrow task-command surface for task lifecycle and intake mutations.
 */
public interface TaskCommandPort {

    TaskCommandOutcome createTaskShell(TaskShellCreateRequestDto dto);

    TaskCommandOutcome patchTaskDefinition(String taskId, TaskDefinitionPatch patch);

    TaskCommandOutcome deleteTask(String taskId);

    TaskCommandOutcome approveTask(String taskId);

    TaskCommandOutcome rejectTask(String taskId);

    TaskCommandOutcome blockTask(String taskId);

    TaskCommandOutcome pauseTask(String taskId);

    TaskCommandOutcome resumeTask(String taskId);

    TaskCommandOutcome cancelTask(String taskId);

    TaskCommandOutcome terminateTask(String taskId, TaskTerminalReason reason);

    TaskAppendOutcome appendTaskItems(String taskId, List<Map<String, Object>> items);

    TaskCommandOutcome sealTask(String taskId);
}
