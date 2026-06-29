package com.xa.mass.sdk.architecture;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineStarterSurfaceInventoryGuardTest {

    @Test
    void approvedStarterSurfacesAreRecordedInInventory() {
        String inventory = EngineCallerSurfaceGuardSupport.read(
                "roadmap/ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md");

        for (String requiredSurface : List.of(
                "Lifecycle and engine availability",
                "Task shell and item operations",
                "Result read operations",
                "Worker topology and declaration operations",
                "Worker control, state, command, reachability operations",
                "Transport handoff ports",
                "Task stage and rule operations",
                "Task work notification residue")) {
            assertTrue(inventory.contains(requiredSurface),
                    "Missing approved starter surface inventory row: " + requiredSurface);
        }
    }

    @Test
    void forbiddenBackdoorsAreNotApprovedStarterSurfaces() {
        String inventory = EngineCallerSurfaceGuardSupport.read(
                "roadmap/ENGINE_CALLER_SURFACE_PRE_TROM_INVENTORY.md");
        String approvedSurfaceSection = inventory.substring(inventory.indexOf("## Approved Starter Surfaces"),
                inventory.indexOf("## Server Routes"));

        assertFalse(approvedSurfaceSection.contains("getEngine()"));
        assertFalse(approvedSurfaceSection.contains("getConfig()"));
        assertFalse(approvedSurfaceSection.contains("TaskCommandService"));
        assertFalse(approvedSurfaceSection.contains("TaskQueryService"));
        assertFalse(approvedSurfaceSection.contains("TaskEventService"));
    }
}
