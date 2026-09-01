package com.xa.mass.server.assembly.runtime;

import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class ServerWorkerGroupInitializer {

    private final WorkerGroupRegistrationService registrations;
    private final List<WorkerGroupDescriptor> descriptors;
    private boolean initialized;

    ServerWorkerGroupInitializer(
            ServerWorkerAssemblyManifest manifest,
            WorkerGroupRegistrationService registrations
    ) {
        this.registrations = Objects.requireNonNull(
                registrations,
                "registrations"
        );
        this.descriptors = manifest.workerGroups();
    }

    synchronized void initialize() {
        if (initialized) {
            return;
        }
        for (WorkerGroupDescriptor descriptor : descriptors) {
            registrations.register(
                    descriptor.workerGroupId(),
                    descriptor.attributes(),
                    new ArrayList<>(descriptor.eventCodes())
            );
        }
        initialized = true;
    }
}
