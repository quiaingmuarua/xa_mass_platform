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

    boolean isWorkerOnline(String workerId);
}
