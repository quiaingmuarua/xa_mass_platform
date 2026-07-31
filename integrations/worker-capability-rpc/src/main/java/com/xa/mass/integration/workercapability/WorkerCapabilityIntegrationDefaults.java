package com.xa.mass.integration.workercapability;

import java.util.List;
import java.util.Locale;

final class WorkerCapabilityIntegrationDefaults {

    static final String SERVER_BASE_URL = "http://127.0.0.1:18082";
    static final String PHONE_WORKER_GROUP_ID =
            "scenario-phone-number-workers";
    static final String PHONE_WORKER_ID_PREFIX =
            "scenario-phone-number-worker-";
    static final String STRING_WORKER_GROUP_ID =
            "scenario-string-utils-workers";
    static final String STRING_WORKER_ID_PREFIX =
            "scenario-string-utils-worker-";
    static final List<EventContract> PHONE_EVENTS = List.of(
            new EventContract("phonenumber.e164", "e164"),
            new EventContract(
                    "phonenumber.country",
                    "countryCallingCode"
            ),
            new EventContract(
                    "phonenumber.original-carrier",
                    "originalCarrier"
            )
    );
    static final List<EventContract> STRING_EVENTS = List.of(
            new EventContract("string.md5", "md5"),
            new EventContract("string.sha1", "sha1"),
            new EventContract("string.base64.encode", "base64")
    );
    static final int WORKER_COUNT = 10;
    static final long RPC_WAIT_TIMEOUT_MILLIS = 30_000;
    static final long TASK_CLOSE_AFTER_MILLIS = 3_600_000;

    private WorkerCapabilityIntegrationDefaults() {
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

    record EventContract(
            String eventCode,
            String resultField
    ) {
    }
}
