package com.xa.mass.worker.runtime.admission;

import com.xa.mass.worker.runtime.evidence.WorkerLoadSnapshot;

import java.util.List;

/**
 * Runtime admission and occupancy surface for matched workers.
 *
 * <p>Scheduling callers must carry WorkerGroup evidence from candidate source
 * through reserve, confirm/release, claim, and final accounting. Worker-id-only
 * reverse lookup belongs below this contract, not on the engine-facing
 * admission surface.</p>
 */
public interface WorkerAdmissionRuntime {

    WorkerAdmissionResult reserveWorkerCapacity(WorkerAdmissionTarget target);

    boolean confirmWorkerReservation(WorkerAdmissionTarget target);

    void releaseWorkerReservation(WorkerAdmissionTarget target);

    void recordWorkClaimed(WorkerAdmissionTarget target);

    void recordWorkFinal(WorkerAdmissionTarget target);

    boolean tryAcquireWorkerExclusiveLease(String workerId);

    void releaseWorkerExclusiveLease(String workerId);

    boolean hasWorkerExclusiveLease(String workerId);

    List<String> getExclusiveLeaseWorkerIds();

    WorkerLoadSnapshot getWorkerLoad(String workerId);

    int getActiveWorkerCountForTask(String taskId);
}
