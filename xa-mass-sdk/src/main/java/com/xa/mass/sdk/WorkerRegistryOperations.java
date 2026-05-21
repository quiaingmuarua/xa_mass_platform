package com.xa.mass.sdk;

import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.sdk.model.WorkerGroupDeclaration;

/**
 * Mainline worker registry/capability mutation surface.
 */
public interface WorkerRegistryOperations {

    void declareWorkerGroup(WorkerGroupDeclaration request);

    void registerWorker(WorkerRegistration request);
}
