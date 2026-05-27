package com.xa.mass.engine.worker;

import com.xa.mass.runtime.worker.WorkerCapabilityReport;
import com.xa.mass.runtime.worker.WorkerCapabilityReportResult;

/**
 * Package-local owner for worker-originated capability report projection.
 */
final class WorkerReportOwner {

    private final WorkerCapabilityAuthority capabilityAuthority;
    private final WorkerResourceOwner resourceOwner;
    private final WorkerGroupOwner groupOwner;

    WorkerReportOwner(WorkerCapabilityAuthority capabilityAuthority,
                      WorkerResourceOwner resourceOwner,
                      WorkerGroupOwner groupOwner) {
        this.capabilityAuthority = capabilityAuthority != null ? capabilityAuthority : new WorkerCapabilityAuthority();
        this.resourceOwner = resourceOwner;
        this.groupOwner = groupOwner;
    }

    WorkerCapabilityReportApplication applyWorkerCapabilityReport(WorkerCapabilityReport report) {
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

    WorkerRegistrySnapshot composeWorkerRegistrySnapshot() {
        return capabilityAuthority.composeSnapshot(resourceOwner.getAllWorkers(), groupOwner.workerGroups());
    }
}
