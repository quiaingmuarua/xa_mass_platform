package com.xa.mass.engine.worker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkerDispatchAvailabilityOwnerTest {

    @Test
    void defaultsToEnabledAndSupportsIdempotentDrainLifecycle() {
        WorkerDispatchAvailabilityOwner owner = new WorkerDispatchAvailabilityOwner();

        assertEquals(WorkerDispatchAvailabilityOwner.DispatchAvailability.ENABLED,
                owner.availabilityOf("worker-1"));
        assertTrue(owner.isDispatchEnabled("worker-1"));

        assertTrue(owner.disableForDraining("worker-1", "maintenance"));
        assertFalse(owner.isDispatchEnabled("worker-1"));
        assertEquals(WorkerDispatchAvailabilityOwner.DispatchAvailability.DRAINING_DISABLED,
                owner.availabilityOf("worker-1"));

        assertFalse(owner.disableForDraining("worker-1", "duplicate"));

        assertTrue(owner.enable("worker-1", "ready"));
        assertTrue(owner.isDispatchEnabled("worker-1"));
        assertEquals(WorkerDispatchAvailabilityOwner.DispatchAvailability.ENABLED,
                owner.availabilityOf("worker-1"));

        assertFalse(owner.enable("worker-1", "duplicate"));
    }
}
