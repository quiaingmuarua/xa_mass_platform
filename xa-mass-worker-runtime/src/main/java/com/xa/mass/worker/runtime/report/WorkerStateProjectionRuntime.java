package com.xa.mass.worker.runtime.report;

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
