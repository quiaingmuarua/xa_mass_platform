package com.xa.mass.sdk;

import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;

import java.util.List;

public interface WorkerOperations {

    void addWorker(Worker worker);

    void addWorkerContext(WorkerContext workerContext);

    Worker getWorker(String workerId);

    boolean updateWorker(Worker worker);

    List<Worker> getAllWorkers();

    List<WorkerContext> getAllWorkerContexts();

    List<WorkerContext> getWorkerContexts(String workerId);

    WorkerContext getWorkerContextById(String workerContextId);

    boolean isWorkerLocked(String workerId);

    boolean isWorkerOnline(String workerId);
}
