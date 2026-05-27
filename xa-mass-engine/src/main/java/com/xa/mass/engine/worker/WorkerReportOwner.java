package com.xa.mass.engine.worker;

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

    WorkerCapabilityReportResult applyWorkerCapabilityReport(WorkerCapabilityReport report) {
        WorkerCapabilityReportResult result = capabilityAuthority.applyReport(
                report,
                resourceOwner.getAllWorkers(),
                groupOwner.workerGroups()
        );
        if (result.snapshotChanged() && result.snapshot() != null) {
            resourceOwner.syncWorkerRegistrySlots(result.snapshot().workers());
        }
        return result;
    }

    WorkerRegistrySnapshot composeWorkerRegistrySnapshot() {
        return capabilityAuthority.composeSnapshot(resourceOwner.getAllWorkers(), groupOwner.workerGroups());
    }
}
