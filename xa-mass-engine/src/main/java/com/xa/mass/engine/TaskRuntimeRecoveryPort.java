package com.xa.mass.engine;

import com.xa.mass.base.model.Task;

import java.util.List;

/**
 * Narrow runtime recovery surface for startup redispatch and event replay.
 */
public interface TaskRuntimeRecoveryPort {

    List<Task> getRuntimeDispatchableTasks(int limit);
}
