package com.xa.mass.engine.worker;

import org.junit.jupiter.api.Test;

import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.NODE_GROUP_BINDING;
import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.WORKER_STATE;
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

        assertTrue(owner.disableForDraining(
                "worker-1",
                WORKER_STATE,
                "maintenance"
        ));
        assertFalse(owner.isDispatchEnabled("worker-1"));
        assertEquals(WorkerDispatchAvailabilityOwner.DispatchAvailability.DRAINING_DISABLED,
                owner.availabilityOf("worker-1"));

        assertFalse(owner.disableForDraining(
                "worker-1",
                WORKER_STATE,
                "duplicate"
        ));

        assertTrue(owner.clearSource(
                "worker-1",
                WORKER_STATE,
                "ready"
        ));
        assertTrue(owner.isDispatchEnabled("worker-1"));
        assertEquals(WorkerDispatchAvailabilityOwner.DispatchAvailability.ENABLED,
                owner.availabilityOf("worker-1"));

        assertFalse(owner.clearSource(
                "worker-1",
                WORKER_STATE,
                "duplicate"
        ));
    }

    @Test
    void sourceScopedGateClearDoesNotEnableWorkerBlockedByAnotherSource() {
        WorkerDispatchAvailabilityOwner owner = new WorkerDispatchAvailabilityOwner();

        assertTrue(owner.disableForDraining(
                "worker-1",
                WORKER_STATE,
                "state draining"
        ));
        assertTrue(owner.disableForDraining(
                "worker-1",
                NODE_GROUP_BINDING,
                "node draining"
        ));
        assertFalse(owner.isDispatchEnabled("worker-1"));

        assertTrue(owner.clearSource(
                "worker-1",
                NODE_GROUP_BINDING,
                "node available"
        ));

        assertFalse(owner.isDispatchEnabled("worker-1"));
        assertEquals(WorkerDispatchAvailabilityOwner.DispatchAvailability.DRAINING_DISABLED,
                owner.availabilityOf("worker-1"));

        assertTrue(owner.clearSource(
                "worker-1",
                WORKER_STATE,
                "worker available"
        ));
        assertTrue(owner.isDispatchEnabled("worker-1"));
    }
}
