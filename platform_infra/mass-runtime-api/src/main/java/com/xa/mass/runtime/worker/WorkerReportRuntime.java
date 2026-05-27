package com.xa.mass.runtime.worker;

/**
 * Worker-originated report surface that may update runtime projections.
 */
public interface WorkerReportRuntime {

    WorkerCapabilityReportResult applyWorkerCapabilityReport(WorkerCapabilityReport report);
}
