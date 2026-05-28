package com.xa.mass.engine.strategy;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.worker.runtime.candidate.WorkerTaskSelector;

import java.util.Set;

/**
 * Engine-side adapter from Task shared config to worker runtime selector input.
 */
public final class WorkerTaskSelectorFactory {

    private WorkerTaskSelectorFactory() {
    }

    public static WorkerTaskSelector fromTask(Task task) {
        Set<String> routeBucketKeys = WorkerRoutingPolicy.defaultPolicy().routeBucketKeysForTask(task);
        return new WorkerTaskSelector(
                task == null ? null : task.getTid(),
                TaskSharedConfig.workerGroupSelector(task),
                TaskSharedConfig.adapterNodeId(task),
                TaskSharedConfig.targetWorkerId(task),
                routeBucketKeys
        );
    }
}
