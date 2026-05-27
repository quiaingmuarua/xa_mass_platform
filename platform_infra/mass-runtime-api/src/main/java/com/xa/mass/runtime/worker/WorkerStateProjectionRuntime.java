package com.xa.mass.runtime.worker;

import java.util.List;
import java.util.Optional;

/**
 * Runtime contract for bounded worker state report projection.
 */
public interface WorkerStateProjectionRuntime {

    WorkerStateProjectionResult applyReport(WorkerStateReport report);

    Optional<WorkerStateProjection> projection(String workerId);

    List<WorkerStateProjection> projections();
}
