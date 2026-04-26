package com.xa.mass.sdk;

import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;

import java.util.List;

/**
 * Query/read surface for worker inspection.
 */
public interface WorkerQueryOperations {

    Worker getWorker(String workerId);

    List<Worker> getAllWorkers();

    List<WorkerContext> getAllWorkerContexts();

    List<WorkerContext> getWorkerContexts(String workerId);

    WorkerContext getWorkerContextById(String workerContextId);

    boolean isWorkerLocked(String workerId);

    boolean isWorkerOnline(String workerId);
}
