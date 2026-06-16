package com.xa.mass.sdk;

import com.xa.mass.sdk.model.WorkerSnapshot;

import java.util.List;

/**
 * Mainline worker read surface.
 */
public interface WorkerInspectionOperations {

    WorkerSnapshot getWorker(String workerId);

    List<WorkerSnapshot> getAllWorkers();

    /**
     * Returns worker ids currently available according to worker runtime
     * lifecycle state.
     */
    List<String> listReachableWorkerIds();

    /**
     * Returns whether the worker is currently available according to worker
     * runtime lifecycle state. Transport endpoint leases are delivery
     * feasibility evidence and are not read by this inspection API.
     */
    boolean isWorkerReachable(String workerId);
}
