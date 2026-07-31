package com.xa.mass.integration.phonenumber;

import java.util.Locale;

final class PhoneNumberIntegrationDefaults {

    static final String SERVER_BASE_URL = "http://127.0.0.1:18082";
    static final String WORKER_GROUP_ID =
            "phonenumber-workers";
    static final String WORKER_ID_PREFIX =
            "phonenumber-worker-";
    static final String EVENT_CODE =
            "phonenumber.lookup";
    static final int WORKER_COUNT = 10;
    static final long RPC_WAIT_TIMEOUT_MILLIS = 30_000;
    static final long TASK_CLOSE_AFTER_MILLIS = 3_600_000;

    private PhoneNumberIntegrationDefaults() {
    }

    static String workerId(String prefix, int oneBasedIndex) {
        if (oneBasedIndex <= 0) {
            throw new IllegalArgumentException(
                    "oneBasedIndex must be positive"
            );
        }
        return prefix + String.format(
                Locale.ROOT,
                "%03d",
                oneBasedIndex
        );
    }
}
