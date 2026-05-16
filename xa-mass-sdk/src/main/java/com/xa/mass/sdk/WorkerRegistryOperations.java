package com.xa.mass.sdk;

import com.xa.mass.sdk.model.WorkerRegistration;

/**
 * Mainline worker registry/capability mutation surface.
 */
public interface WorkerRegistryOperations {

    void registerWorker(WorkerRegistration request);
}
