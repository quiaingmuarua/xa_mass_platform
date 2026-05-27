package com.xa.mass.runtime.worker;

import java.util.List;

/**
 * Runtime admission and occupancy surface for matched workers.
 */
public interface WorkerAdmissionRuntime {

    ReserveResult reserveWorkerCapacity(String workerId, String taskId);

    boolean tryReserveWorkerCapacity(String workerId, String taskId);

    boolean confirmWorkerReservation(String workerId, String taskId);

    void releaseWorkerReservation(String workerId, String taskId);

    void recordWorkClaimed(String workerId, String taskId);

    void recordWorkFinal(String workerId, String taskId);

    boolean tryAcquireWorkerExclusiveLease(String workerId);

    void releaseWorkerExclusiveLease(String workerId);

    boolean hasWorkerExclusiveLease(String workerId);

    List<String> getExclusiveLeaseWorkerIds();

    WorkerLoadSnapshot getWorkerLoad(String workerId);

    int getActiveWorkerCountForTask(String taskId);
}
