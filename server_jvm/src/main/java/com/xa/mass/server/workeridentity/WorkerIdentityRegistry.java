package com.xa.mass.server.workeridentity;

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
