package com.xa.mass.sdk;

import com.xa.mass.sdk.model.WorkerContextSnapshot;
import com.xa.mass.sdk.model.WorkerSnapshot;

import java.util.List;

/**
 * Query/read surface for worker inspection.
 */
public interface WorkerQueryOperations {

    WorkerSnapshot getWorker(String workerId);

    List<WorkerSnapshot> getAllWorkers();

    List<WorkerContextSnapshot> getAllWorkerContexts();

    List<WorkerContextSnapshot> getWorkerContexts(String workerId);

    WorkerContextSnapshot getWorkerContextById(String workerContextId);

    boolean isWorkerLocked(String workerId);

    /**
     * Returns whether the worker currently has transport reachability.
     *
     * <p>When transport presence is available this query reflects transport
     * truth; otherwise it falls back to the engine-owned worker model.</p>
     */
    boolean isWorkerOnline(String workerId);
}
