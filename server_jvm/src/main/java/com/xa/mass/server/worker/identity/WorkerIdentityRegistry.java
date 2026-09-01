package com.xa.mass.server.worker.identity;

interface WorkerIdentityRegistry {

    String register(
            String workerGroupId,
            String registrationKey
    );

    boolean matches(
            String workerGroupId,
            String registrationKey,
            String workerId
    );
}
