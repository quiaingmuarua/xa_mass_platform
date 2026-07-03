package com.xa.mass.engine;

import com.xa.mass.base.enums.task.TaskTerminalReason;
import com.xa.mass.base.model.Task;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Current task-shell lifecycle maintenance surface.
 */
public interface TaskShellLifecycleMaintenancePort {

    List<Task> pollTasksPastMaxRuntimeDeadline(LocalDateTime now, int limit);

    boolean terminateTask(String taskId, TaskTerminalReason reason);
}
