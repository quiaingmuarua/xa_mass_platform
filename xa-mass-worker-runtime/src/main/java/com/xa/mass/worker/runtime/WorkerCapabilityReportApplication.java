package com.xa.mass.worker.runtime;

import com.xa.mass.worker.runtime.report.WorkerCapabilityReportResult;

public record WorkerCapabilityReportApplication(
        WorkerCapabilityReportResult result,
        WorkerRegistrySnapshot snapshot) {
}
