package com.xa.mass.integration.workercapability.cli;

public final class WorkerCapabilityIntegrationDefaults {

    public static final String SERVER_BASE_URL =
            "http://127.0.0.1:18082";
    public static final int CONCURRENCY = 10;
    public static final long REQUEST_TIMEOUT_MILLIS = 120_000;

    private WorkerCapabilityIntegrationDefaults() {
    }
}
