package com.xa.mass.sdk;

import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerRegistration;

import java.util.List;

/**
 * Worker mutation/admin surface used by embedded shells and repo-local tooling.
 */
public interface WorkerAdminOperations {

    void registerWorker(WorkerRegistration request);

    void registerWorkerContext(WorkerContextRegistration request);

    boolean updateWorkerSupportedProjects(String workerId, List<String> supportedProjects);
}
