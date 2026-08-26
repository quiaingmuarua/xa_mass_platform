package com.xa.mass.kernel.pacer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorkerServiceabilityAssemblyConfigTest {

    @Test
    void absentSectionDisablesBothApplicationsAndDoesNotMintAHotFloor() {
        var config = WorkerServiceabilityAssemblyConfig
                .fromKernelConfigJson("{}", () -> 12_345L);

        assertFalse(config.enabled());
        assertEquals(0L, config.hotEligibilityFloorMillis());
        assertEquals(100L, config.result().intervalMillis());
        assertEquals(1_000L, config.dispatch().intervalMillis());
    }

    @Test
    void parsesTheWholeSectionAndMintsOneAlignedFloor() {
        var config = WorkerServiceabilityAssemblyConfig
                .fromKernelConfigJson("""
                        {
                          "workerServiceability": {
                            "dispatchIntervalMillis": 17,
                            "resultIntervalMillis": 19,
                            "taskScanLimit": 23,
                            "recoveryRetryIntervalMillis": 31,
                            "probeSweepRestartDelayMillis": 37,
                            "maxRecoveryAttempts": 4,
                            "hotScanLimit": 41,
                            "recoveryScanLimit": 42,
                            "resultReportLimit": 7,
                            "evidenceMaxAgeMillis": 9000,
                            "probeExcludedEndpointManagerIds": ["polling"]
                          }
                        }
                        """, () -> 12_345L);

        assertTrue(config.enabled());
        assertEquals(12_300L, config.hotEligibilityFloorMillis());
        assertEquals(19L, config.result().intervalMillis());
        assertEquals(7, config.result().result().resultReportLimit());
        assertEquals(9_000L, config.result().result().evidenceMaxAgeMillis());
        assertEquals(4, config.result().result().maxRecoveryAttempts());
        assertEquals(17L, config.dispatch().intervalMillis());
        assertEquals(23, config.dispatch().dispatch().taskScanLimit());
        assertEquals(31L, config.dispatch().dispatch()
                .recoveryRetryIntervalMillis());
        assertEquals(37L, config.dispatch().dispatch()
                .probeSweepRestartDelayMillis());
        assertEquals(41, config.dispatch().dispatch().hotScanLimit());
        assertEquals(42, config.dispatch().dispatch().recoveryScanLimit());
        assertEquals(
                List.of("polling"),
                config.dispatch().dispatch()
                        .probeExcludedEndpointManagerIds()
        );
    }

    @Test
    void rejectsUnknownOrInvalidServiceabilityFields() {
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkerServiceabilityAssemblyConfig
                        .fromKernelConfigJson("""
                                {"workerServiceability":{"unknown":1}}
                                """, () -> 1_000L)
        );
        for (String value : new String[]{"0", "-1", "1.5", "true"}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> WorkerServiceabilityAssemblyConfig
                            .fromKernelConfigJson(
                                    "{\"workerServiceability\":"
                                            + "{\"dispatchIntervalMillis\":"
                                            + value + "}}",
                                    () -> 1_000L
                            )
            );
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> WorkerServiceabilityAssemblyConfig
                        .fromKernelConfigJson("""
                                {"workerServiceability":{
                                  "hotScanLimit":80,
                                  "recoveryScanLimit":21
                                }}
                                """, () -> 1_000L)
        );
    }
}
