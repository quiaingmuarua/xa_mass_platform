package com.xa.mass.worker.runtime.resource;

import java.util.List;
import java.util.Optional;

/**
 * Worker-runtime owned persistence port for declaration-only worker rows.
 *
 * <p>This is not runtime scheduling truth. Worker runtime occupancy,
 * exclusivity, reachability, and dispatch gates are owned by the registry and
 * admission owners. Durable worker history belongs in trace/audit projections,
 * not this declaration contract.</p>
 */
public interface WorkerDeclarationStore {

    void addWorker(WorkerDeclarationRecord worker);

    Optional<WorkerDeclarationRecord> getWorker(String workerId);

    boolean updateWorker(WorkerDeclarationRecord worker);

    boolean deleteWorker(String workerId);

    List<WorkerDeclarationRecord> getWorkersByGroupId(String workerGroupId);

    List<WorkerDeclarationRecord> getAllWorkers();
}
