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
     * Returns worker ids with current delivery reachability when transport
     * evidence is available; otherwise falls back to worker runtime availability.
     */
    List<String> listReachableWorkerIds();

    /**
     * Returns whether the worker currently has delivery reachability.
     *
     * <p>Transport-backed runtimes resolve this through the selected-worker
     * route-owner view. This is not a worker lifecycle or scheduling truth.</p>
     */
    boolean isWorkerReachable(String workerId);
}
