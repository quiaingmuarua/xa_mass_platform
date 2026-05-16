package com.xa.mass.sdk;

import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerContextSnapshot;

import java.util.List;

/**
 * Transitional compatibility surface for legacy WorkerContext callers.
 */
public interface WorkerContextCompatibilityOperations {

    void registerWorkerContext(WorkerContextRegistration request);

    List<WorkerContextSnapshot> getAllWorkerContexts();

    List<WorkerContextSnapshot> getWorkerContexts(String workerId);

    WorkerContextSnapshot getWorkerContextById(String workerContextId);
}
