package com.xa.mass.engine.strategy;

import com.xa.mass.base.model.Task;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;

import java.util.List;

/**
 * Strategy SPI for choosing workers for a task.
 *
 * <p>This is the main extension seam for library/SDK usage where consumers
 * want to provide different matching policies without changing the task
 * lifecycle state machine.</p>
 */
public interface TaskWorkerMatchingStrategy {

    /**
     * Match workers for the given task.
     *
     * @param task task being assigned
     * @param maxWorkerCount upper bound for matched workers
     * @return matched worker/context candidates, never {@code null}
     */
    List<WorkerSchedulingCandidate> matchWorkers(Task task, int maxWorkerCount);
}
