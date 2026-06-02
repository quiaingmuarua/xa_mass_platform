package com.xa.mass.sdk;

import com.xa.mass.sdk.model.AdapterNodeRegistration;
import com.xa.mass.sdk.model.NodeGroupBindingRegistration;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.sdk.model.WorkerGroupDeclaration;

/**
 * Mainline worker registry/capability mutation surface.
 */
public interface WorkerRegistryOperations {

    void registerAdapterNode(AdapterNodeRegistration request);

    void bindNodeGroup(NodeGroupBindingRegistration request);

    void declareWorkerGroup(WorkerGroupDeclaration request);

    void registerWorker(WorkerRegistration request);
}
