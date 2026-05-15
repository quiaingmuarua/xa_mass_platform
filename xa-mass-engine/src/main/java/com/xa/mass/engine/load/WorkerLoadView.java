package com.xa.mass.engine.load;

/**
 * Push-updated worker load read view for scheduling.
 *
 * <p>This is observational in the current phase: matching can read it, but it
 * does not gate eligibility, capacity, lock acquisition, or dispatch binding.</p>
 */
public interface WorkerLoadView {

    int getActiveLeaseCount(String workerId);

    int getReservedCount(String workerId);

    double getEstimatedLoadRatio(String workerId);

    WorkerLoadSnapshot snapshot(String workerId);

    void recordWorkClaimed(String workerId, String taskId);

    void recordWorkFinal(String workerId, String taskId);
}
