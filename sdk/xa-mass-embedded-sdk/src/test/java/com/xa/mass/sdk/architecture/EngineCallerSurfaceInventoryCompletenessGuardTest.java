package com.xa.mass.sdk.architecture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineCallerSurfaceInventoryCompletenessGuardTest {

    @Test
    void inventoryHasNoSlicePlaceholders() {
        String inventory = EngineCallerSurfaceGuardSupport.read(
                "roadmap/ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md");

        assertFalse(inventory.contains("_TBD"),
                "ECSP inventory must not keep slice placeholder rows after ECSP-0A/0B");
    }

    @Test
    void inventoryRecordsNoServerRouteChangeForThisSlice() {
        String inventory = EngineCallerSurfaceGuardSupport.read(
                "roadmap/ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md");

        assertTrue(inventory.contains("N/A for ECSP-1/2"));
        assertTrue(inventory.contains("no server route touched"));
    }
}
