package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskCreateRequestDto;
import com.xa.mass.engine.model.TaskResumeResult;

import java.util.List;
import java.util.Map;

/**
 * Narrow task-command surface for task lifecycle and intake mutations.
 */
public interface TaskCommandPort {

    Task createTask(TaskCreateRequestDto dto);

    boolean updateTask(Task task);

    boolean deleteTask(String taskId);

    boolean approveTask(String taskId);

    boolean rejectTask(String taskId);

    boolean blockTask(String taskId);

    boolean pauseTask(String taskId);

    TaskResumeResult resumeTaskDetailed(String taskId);

    boolean resumeTask(String taskId);

    boolean cancelTask(String taskId);

    boolean terminateTask(String taskId, TaskTerminalReason reason);

    int appendTaskItems(String taskId, List<Map<String, Object>> inputs);

    boolean sealTask(String taskId);
}
