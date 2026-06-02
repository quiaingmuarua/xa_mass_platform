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
     * Returns whether the worker currently has transport reachability.
     *
     * <p>When transport presence is available this query reflects transport
     * truth; otherwise it falls back to the engine-owned worker model.</p>
     */
    boolean isWorkerOnline(String workerId);
}
