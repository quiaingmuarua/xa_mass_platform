package com.xa.mass.storage.api;

import java.util.List;
import java.util.Optional;

/**
 * Control-plane worker row abstraction.
 *
 * <p>This is not runtime scheduling truth. Worker runtime occupancy,
 * exclusivity, reachability, and dispatch gates are owned by the engine
 * runtime registry. Durable worker history belongs in trace/audit
 * projections, not this storage contract.
 */
public interface WorkerDeclarationStore {

    void addWorker(WorkerDeclarationRecord worker);

    Optional<WorkerDeclarationRecord> getWorker(String workerId);

    boolean updateWorker(WorkerDeclarationRecord worker);

    boolean deleteWorker(String workerId);

    List<WorkerDeclarationRecord> getWorkersByGroupId(String workerGroupId);

    List<WorkerDeclarationRecord> getAllWorkers();
}
