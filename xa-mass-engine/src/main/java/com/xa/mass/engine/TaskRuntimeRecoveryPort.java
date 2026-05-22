package com.xa.mass.engine;

import com.xa.mass.base.model.Task;

import java.util.List;

/**
 * Narrow runtime recovery surface for startup redispatch and event replay.
 *
 * <p>Recovery must trust runtime-owned ready-queue truth rather than infer
 * dispatchable work from storage task status or projection scans.</p>
 */
public interface TaskRuntimeRecoveryPort {

    List<Task> getRuntimeDispatchableTasks(int limit);
}
