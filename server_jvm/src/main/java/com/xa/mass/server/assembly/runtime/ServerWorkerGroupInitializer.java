package com.xa.mass.server.assembly.runtime;

import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerGroupDescriptor;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class ServerWorkerGroupInitializer {

    private static final System.Logger LOG =
            System.getLogger(ServerWorkerGroupInitializer.class.getName());

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
            try {
                registrations.register(
                        descriptor.workerGroupId(),
                        descriptor.attributes(),
                        new ArrayList<>(descriptor.eventCodes())
                );
            } catch (ServerException error) {
                if (error.errorCode() == ServerErrorCode.WORKER_GROUP_REGISTRATION_CONFLICT) {
                    // Startup seed is ensure-if-absent: keep Runtime truth when already modified in Redis.
                    LOG.log(
                            System.Logger.Level.INFO,
                            "Startup seed skipped for existing WorkerGroup ''{0}''; retaining Runtime descriptor",
                            descriptor.workerGroupId()
                    );
                    continue;
                }
                throw error;
            }
        }
        initialized = true;
    }
}
