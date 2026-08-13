package com.xa.mass.server.workerassembly;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import java.util.List;
import java.util.Objects;

final class ServerWorkerGroupInitializer {

    private final WorkerResourceCatalog workerCatalog;
    private final List<WorkerGroupDescriptor> descriptors;
    private boolean initialized;

    ServerWorkerGroupInitializer(
            ServerWorkerAssemblyManifest manifest,
            WorkerResourceCatalog workerCatalog
    ) {
        this.workerCatalog = Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
        this.descriptors = manifest.workerGroups();
    }

    synchronized void initialize() {
        if (initialized) {
            return;
        }
        for (WorkerGroupDescriptor descriptor : descriptors) {
            WorkerRuntimeResult result = workerCatalog.upsertWorkerGroup(
                    descriptor
            );
            if (result == null
                    || (result.status() != WorkerRuntimeStatus.OK
                    && result.status() != WorkerRuntimeStatus.NOOP)) {
                String status = result == null
                        ? "missing"
                        : result.status().wireValue();
                String reason = result == null || result.reason() == null
                        ? ""
                        : ": " + result.reason();
                throw new IllegalStateException(
                        "WorkerGroup "
                                + descriptor.workerGroupId()
                                + " initialization returned "
                                + status
                                + reason
                );
            }
        }
        initialized = true;
    }
}
