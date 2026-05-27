package com.xa.mass.worker.runtime;

import com.xa.mass.runtime.worker.WorkerCapabilityReportResult;

public record WorkerCapabilityReportApplication(
        WorkerCapabilityReportResult result,
        WorkerRegistrySnapshot snapshot) {
}
