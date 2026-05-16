package com.xa.mass.sdk;

import com.xa.mass.sdk.model.WorkerContextRegistration;

import java.util.List;

/**
 * Transitional worker mutation/admin surface used by embedded shells and
 * repo-local tooling.
 */
public interface WorkerAdminOperations extends WorkerRegistryOperations {

    /**
     * @deprecated WorkerContext no longer belongs to the SDK worker mainline.
     * Use {@link WorkerContextCompatibilityOperations} for the transitional
     * compatibility path only.
     */
    @Deprecated(forRemoval = false)
    void registerWorkerContext(WorkerContextRegistration request);

    /**
     * @deprecated Capability truth is {@code WorkerRegistration.eventBindings}.
     * Do not extend this coarse compatibility mutation surface.
     */
    @Deprecated(forRemoval = false)
    boolean updateWorkerSupportedProjects(String workerId, List<String> supportedProjects);
}
