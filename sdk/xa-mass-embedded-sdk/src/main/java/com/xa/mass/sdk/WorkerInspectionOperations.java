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
     * Returns worker ids that currently have transport reachability.
     *
     * <p>List/read models should prefer this snapshot over calling
     * {@link #isWorkerReachable(String)} once per row.</p>
     */
    List<String> listReachableWorkerIds();

    /**
     * Returns whether the worker currently has transport reachability.
     *
     * <p>When transport presence is available this query reflects transport
     * truth; otherwise it falls back to the engine-owned worker model.</p>
     */
    boolean isWorkerReachable(String workerId);
}
