package com.xa.mass.task.runtime.starter;

import com.xa.mass.task.runtime.TaskRuntimeConvergencePort;
import com.xa.mass.task.runtime.TaskRuntimeReadPort;
import com.xa.mass.task.runtime.TaskRuntimeScorePort;
import com.xa.mass.task.runtime.TaskRuntimeWorkPort;

public interface TaskRuntimePortSet extends TaskRuntimeWorkPort,
        TaskRuntimeScorePort,
        TaskRuntimeConvergencePort,
        TaskRuntimeReadPort {
}
