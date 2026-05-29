package com.xa.mass.worker.runtime.resource;

import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;

/**
 * Worker resource declaration mutation surface.
 *
 * <p>Current methods still accept {@link WorkerResourceRecord} as the
 * compatibility resource shape. TWH-3B is responsible for moving declaration
 * writes to {@link WorkerDeclarationRecord} so runtime state no longer crosses
 * the persistence boundary.</p>
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
