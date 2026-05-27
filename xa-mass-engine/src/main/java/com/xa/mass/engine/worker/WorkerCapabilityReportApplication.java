package com.xa.mass.engine.worker;

import com.xa.mass.runtime.worker.WorkerCapabilityReportResult;

record WorkerCapabilityReportApplication(
        WorkerCapabilityReportResult result,
        WorkerRegistrySnapshot snapshot) {
}
