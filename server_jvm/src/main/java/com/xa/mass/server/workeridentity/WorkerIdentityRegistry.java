package com.xa.mass.server.workeridentity;

interface WorkerIdentityRegistry {

    String register(String workerGroupId, String clientWorkerKey);

    boolean matches(
            String workerGroupId,
            String clientWorkerKey,
            String workerId
    );
}
