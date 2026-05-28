package com.xa.mass.worker.runtime;

import com.xa.mass.worker.runtime.report.WorkerCapabilityReport;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReportResult;

/**
 * Runtime owner for worker-originated capability report projection.
 */
public final class WorkerReportOwner {

    private final WorkerCapabilityAuthority capabilityAuthority;
    private final WorkerResourceOwner resourceOwner;
    private final WorkerGroupOwner groupOwner;

    public WorkerReportOwner(WorkerCapabilityAuthority capabilityAuthority,
                             WorkerResourceOwner resourceOwner,
                             WorkerGroupOwner groupOwner) {
        this.capabilityAuthority = capabilityAuthority != null ? capabilityAuthority : new WorkerCapabilityAuthority();
        this.resourceOwner = resourceOwner;
        this.groupOwner = groupOwner;
    }

    public WorkerCapabilityReportApplication applyWorkerCapabilityReport(WorkerCapabilityReport report) {
        WorkerCapabilityReportResult result = capabilityAuthority.applyReport(
                report,
                resourceOwner.getAllWorkers(),
                groupOwner.workerGroups()
        );
        WorkerRegistrySnapshot snapshot = null;
        if (result.snapshotChanged()) {
            snapshot = composeWorkerRegistrySnapshot();
            resourceOwner.syncWorkerRegistrySlots(snapshot.workers());
        }
        return new WorkerCapabilityReportApplication(result, snapshot);
    }

    public WorkerRegistrySnapshot composeWorkerRegistrySnapshot() {
        return capabilityAuthority.composeSnapshot(resourceOwner.getAllWorkers(), groupOwner.workerGroups());
    }
}
