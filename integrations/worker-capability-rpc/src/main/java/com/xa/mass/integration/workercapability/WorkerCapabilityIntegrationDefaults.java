package com.xa.mass.integration.workercapability;

final class WorkerCapabilityIntegrationDefaults {

    static final String SERVER_BASE_URL = "http://127.0.0.1:18082";
    static final long RPC_WAIT_TIMEOUT_MILLIS = 30_000;
    static final long TASK_CLOSE_AFTER_MILLIS = 3_600_000;

    private WorkerCapabilityIntegrationDefaults() {
    }
}
