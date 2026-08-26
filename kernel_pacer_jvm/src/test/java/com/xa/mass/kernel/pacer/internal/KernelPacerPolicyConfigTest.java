package com.xa.mass.kernel.pacer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KernelPacerPolicyConfigTest {

    @Test
    void emptyDocumentBuildsFiniteDefaultsWithoutServiceability() {
        KernelPacerPolicyConfig config = KernelPacerPolicyConfig.fromJson(
                "{}",
                () -> 12_345
        );

        assertEquals(100, config.resultRouting().intervalMillis());
        assertFalse(config.workerServiceability().enabled());
        assertEquals(
                100,
                config.assignmentDispatch()
                        .workerAllocationIntervalMillis()
        );
        assertEquals(
                100,
                config.assignmentDispatch().runningActivation()
                        .runningTaskSoftLimit()
        );
    }

    @Test
    void parsesAllProductionSectionsAndGeneratesOneAlignedFloor() {
        KernelPacerPolicyConfig config = KernelPacerPolicyConfig.fromJson(
                """
                {
                  "resultRouting":{"intervalMillis":11},
                  "workerServiceability":{
                    "dispatchIntervalMillis":12,
                    "resultIntervalMillis":13,
                    "maxRecoveryAttempts":4,
                    "probeExcludedEndpointManagerIds":[]
                  },
                  "assignmentDispatch":{
                    "workerAllocationIntervalMillis":14,
                    "runningActivationIntervalMillis":15,
                    "taskDispatchIntervalMillis":16
                  },
                  "systemPolicy":{"runningTaskSoftLimit":17}
                }
                """,
                () -> 12_399
        );

        assertEquals(11, config.resultRouting().intervalMillis());
        assertTrue(config.workerServiceability().enabled());
        assertEquals(
                12_300,
                config.workerServiceability().hotEligibilityFloorMillis()
        );
        assertEquals(
                4,
                config.workerServiceability().result().result()
                        .maxRecoveryAttempts()
        );
        assertEquals(
                4,
                config.workerServiceability().dispatch().dispatch()
                        .maxRecoveryAttempts()
        );
        assertEquals(
                14,
                config.assignmentDispatch()
                        .workerAllocationIntervalMillis()
        );
        assertEquals(
                17,
                config.assignmentDispatch().runningActivation()
                        .runningTaskSoftLimit()
        );
    }

    @Test
    void rejectsUnknownRootAndSectionFields() {
        assertThrows(
                IllegalArgumentException.class,
                () -> KernelPacerPolicyConfig.fromJson(
                        "{\"redis\":{}}"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> KernelPacerPolicyConfig.fromJson(
                        "{\"assignmentDispatch\":{\"batchLimit\":1}}"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> KernelPacerPolicyConfig.fromJson(
                        "{\"systemPolicy\":{\"fairness\":\"weighted\"}}"
                )
        );
    }

    @Test
    void rejectsInvalidTypesAndBounds() {
        for (String json : new String[]{
                "{\"resultRouting\":{\"intervalMillis\":true}}",
                "{\"assignmentDispatch\":{\"taskDispatchIntervalMillis\":0}}",
                "{\"systemPolicy\":{\"runningTaskSoftLimit\":0}}",
                "{\"workerServiceability\":{\"hotScanLimit\":80,\"recoveryScanLimit\":21}}"
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> KernelPacerPolicyConfig.fromJson(json)
            );
        }
    }
}
