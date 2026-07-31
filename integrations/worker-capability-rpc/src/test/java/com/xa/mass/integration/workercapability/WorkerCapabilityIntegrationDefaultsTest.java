package com.xa.mass.integration.workercapability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class WorkerCapabilityIntegrationDefaultsTest {

    @Test
    void scenarioCoversSixEventsAcrossTwentyWorkers() {
        assertEquals(
                List.of(
                        "phonenumber.e164",
                        "phonenumber.country",
                        "phonenumber.original-carrier"
                ),
                WorkerCapabilityIntegrationDefaults.PHONE_EVENTS
                        .stream()
                        .map(WorkerCapabilityIntegrationDefaults
                                .EventContract::eventCode)
                        .toList()
        );
        assertEquals(
                List.of(
                        "string.md5",
                        "string.sha1",
                        "string.base64.encode"
                ),
                WorkerCapabilityIntegrationDefaults.STRING_EVENTS
                        .stream()
                        .map(WorkerCapabilityIntegrationDefaults
                                .EventContract::eventCode)
                        .toList()
        );
        assertEquals(
                List.of(
                        "scenario-phone-number-worker-001",
                        "scenario-phone-number-worker-002",
                        "scenario-phone-number-worker-003",
                        "scenario-phone-number-worker-004",
                        "scenario-phone-number-worker-005",
                        "scenario-phone-number-worker-006",
                        "scenario-phone-number-worker-007",
                        "scenario-phone-number-worker-008",
                        "scenario-phone-number-worker-009",
                        "scenario-phone-number-worker-010"
                ),
                IntStream.rangeClosed(1, 10)
                        .mapToObj(index ->
                                WorkerCapabilityIntegrationDefaults
                                        .workerId(
                                                WorkerCapabilityIntegrationDefaults
                                                        .PHONE_WORKER_ID_PREFIX,
                                                index
                                        )
                        )
                        .toList()
        );
        assertTrue(IntStream.rangeClosed(1, 10)
                .mapToObj(index ->
                        WorkerCapabilityIntegrationDefaults.workerId(
                                WorkerCapabilityIntegrationDefaults
                                        .STRING_WORKER_ID_PREFIX,
                                index
                        )
                ).allMatch(workerId ->
                        workerId.startsWith(
                                "scenario-string-utils-worker-"
                        )
                ));
    }
}
