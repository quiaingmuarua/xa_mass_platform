package com.xa.mass.engine.worker;

import com.xa.mass.runtime.worker.WorkerCapabilityReportResult;

/**
 * Worker-originated report surface that may update runtime projections.
 */
public interface WorkerReportRuntime {

    WorkerCapabilityReportResult applyWorkerCapabilityReport(WorkerCapabilityReport report);
}
