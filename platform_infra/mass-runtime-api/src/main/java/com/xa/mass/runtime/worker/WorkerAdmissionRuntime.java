package com.xa.mass.runtime.worker;

/**
 * Runtime admission and occupancy surface for matched workers.
 */
public interface WorkerAdmissionRuntime {

    WorkerAdmissionResult reserveWorkerCapacity(String workerId, String taskId);

    boolean confirmWorkerReservation(String workerId, String taskId);

    void releaseWorkerReservation(String workerId, String taskId);

    void recordWorkClaimed(String workerId, String taskId);

    void recordWorkFinal(String workerId, String taskId);

    boolean tryAcquireWorkerExclusiveLease(String workerId);

    void releaseWorkerExclusiveLease(String workerId);

    boolean hasWorkerExclusiveLease(String workerId);

    WorkerLoadSnapshot getWorkerLoad(String workerId);

    int getActiveWorkerCountForTask(String taskId);
}
