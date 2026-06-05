package com.xa.mass.worker.runtime.resource;

import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;

/**
 * Worker resource declaration mutation surface.
 *
 * <p>Current methods still accept {@link WorkerResourceRecord} as the
 * compatibility resource shape. Implementations must project writes to
 * {@link WorkerDeclarationRecord} before persistence so runtime state no
 * longer crosses the declaration boundary.</p>
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
