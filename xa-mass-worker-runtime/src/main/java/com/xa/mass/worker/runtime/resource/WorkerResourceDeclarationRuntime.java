package com.xa.mass.worker.runtime.resource;

/**
 * Worker resource declaration mutation surface.
 */
public interface WorkerResourceDeclarationRuntime {

    void addWorker(WorkerResourceRecord worker);

    boolean updateWorker(WorkerResourceRecord worker);

    boolean deleteWorker(String workerId);

    WorkerGroupRecord upsertWorkerGroup(WorkerGroupRecord group);

    boolean deleteWorkerGroup(String groupId);

    AdapterNodeRecord registerAdapterNode(AdapterNodeRecord adapterNode);

    boolean deleteAdapterNode(String adapterNodeId);
}
