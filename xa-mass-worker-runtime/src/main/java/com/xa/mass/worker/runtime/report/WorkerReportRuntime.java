package com.xa.mass.worker.runtime.report;

/**
 * Worker-originated report surface that may update runtime projections.
 */
public interface WorkerReportRuntime {

    WorkerCapabilityReportResult applyWorkerCapabilityReport(WorkerCapabilityReport report);
}
