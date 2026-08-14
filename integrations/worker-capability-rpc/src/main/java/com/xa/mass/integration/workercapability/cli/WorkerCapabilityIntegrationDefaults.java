package com.xa.mass.integration.workercapability.cli;

public final class WorkerCapabilityIntegrationDefaults {

    public static final String SERVER_BASE_URL =
            "http://127.0.0.1:18082";
    public static final long LOAD_INTERVAL_MILLIS = 100;
    public static final int MAXIMUM_LOAD_ROUNDS = 300;
    public static final long REQUEST_TIMEOUT_MILLIS = 120_000;

    private WorkerCapabilityIntegrationDefaults() {
    }
}
