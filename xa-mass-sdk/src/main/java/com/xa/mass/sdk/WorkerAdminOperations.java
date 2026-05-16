package com.xa.mass.sdk;

import java.util.List;

/**
 * Transitional worker mutation/admin surface used by embedded shells and
 * repo-local tooling.
 */
public interface WorkerAdminOperations extends WorkerRegistryOperations {

    /**
     * @deprecated Capability truth is {@code WorkerRegistration.eventBindings}.
     * Do not extend this coarse compatibility mutation surface.
     */
    @Deprecated(forRemoval = false)
    boolean updateWorkerSupportedProjects(String workerId, List<String> supportedProjects);
}
