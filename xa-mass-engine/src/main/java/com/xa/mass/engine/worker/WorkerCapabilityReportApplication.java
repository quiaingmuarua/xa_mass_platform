package com.xa.mass.engine.worker;

import com.xa.mass.runtime.worker.WorkerCapabilityReportResult;
import com.xa.mass.worker.runtime.WorkerRegistrySnapshot;

record WorkerCapabilityReportApplication(
        WorkerCapabilityReportResult result,
        WorkerRegistrySnapshot snapshot) {
}
