package com.xa.mass.kernel.assignment;

/** Bounded handoff of PRECOMPUTED matching demands. */
public interface WorkerMatchQueue {

    /** Offers one complete Demand without waiting for capacity. */
    boolean offer(TaskRuleMatchDemand demand);

    /** Returns the current number of queued Demands. */
    int size();

    /** Waits for and atomically consumes the next Demand. */
    TaskRuleMatchDemand consume() throws InterruptedException;
}
