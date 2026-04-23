package com.xa.mass.sdk;

import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerRegistration;

import java.util.List;

public interface WorkerOperations {

    /**
     * Register worker identity and capabilities through the SDK contract.
     * The registered worker starts OFFLINE until its transport connects.
     */
    void registerWorker(WorkerRegistration request);

    /**
     * Register an allocatable worker context through the SDK contract.
     * The registered context starts IDLE.
     */
    void registerWorkerContext(WorkerContextRegistration request);

    /**
     * @deprecated Prefer {@link #registerWorker(WorkerRegistration)} for SDK callers.
     */
    @Deprecated(forRemoval = false)
    void addWorker(Worker worker);

    /**
     * @deprecated Prefer {@link #registerWorkerContext(WorkerContextRegistration)} for SDK callers.
     */
    @Deprecated(forRemoval = false)
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
