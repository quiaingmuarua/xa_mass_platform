package com.xa.mass.kernel.spi.task;

import com.xa.mass.base.model.Task;

import java.time.LocalDateTime;
import java.util.List;

/** Runtime-kernel lifecycle query for bounded shell maintenance policies. */
public interface TaskShellRuntimeLifecycleQuery {

    List<Task> pollTasksPastMaxRuntimeDeadline(LocalDateTime now, int limit);
}
