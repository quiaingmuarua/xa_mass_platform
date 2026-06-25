package com.xa.mass.runtime.contract;

import com.xa.mass.runtime.worker.EventKey;
import com.xa.mass.runtime.worker.ReserveStatus;
import com.xa.mass.runtime.worker.WorkerDispatchBlockRecord;
import com.xa.mass.runtime.worker.WorkerMeta;
import com.xa.mass.runtime.worker.WorkerRegistry;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.WORKER_COMMAND;
import static com.xa.mass.runtime.worker.DispatchAvailabilitySource.WORKER_STATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared WorkerRegistry contract for memory and Redis implementations.
 */
public abstract class WorkerRegistryContractTest {

    protected abstract WorkerRegistry createRegistry();

    @Test
    void slotMetadataAndGroupMembershipComeFromRegistryTruth() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-a", "group-a"), 1, Set.of(eventKey()));
        registry.upsertSlot(meta("worker-b", "group-a"), 2, Set.of(eventKey()));
        registry.upsertSlot(meta("worker-c", "group-b"), 1, Set.of(eventKey()));

        assertEquals("group-a", registry.workerMeta("worker-a").orElseThrow().groupId());
        assertEquals(2, registry.workerAdmissionSnapshot("worker-b").orElseThrow().declaredCapacity());
        assertEquals(Set.of("worker-a", "worker-b"), registry.workerIdsByGroupId("group-a"));
    }

    @Test
    void movingWorkerBetweenGroupsUpdatesLookupTruth() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));
        registry.upsertSlot(meta("worker-1", "group-b"), 1, Set.of(eventKey()));

        assertTrue(registry.workerIdsByGroupId("group-a").isEmpty());
        assertEquals(Set.of("worker-1"), registry.workerIdsByGroupId("group-b"));
        assertEquals("group-b", registry.slotByWorkerId("worker-1").orElseThrow().groupId());
    }

    @Test
    void slotLifecycleStatusRejectsStaleDisabledRemovingAndGroupMismatch() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(metaAt("worker-stale", "group-a", 1_000), 1, Set.of(eventKey()));
        registry.upsertSlot(meta("worker-disabled", "group-a"), 1, Set.of(eventKey()));
        registry.upsertSlot(meta("worker-removing", "group-a"), 1, Set.of(eventKey()));
        registry.disableDispatch("group-a", "worker-disabled", WORKER_STATE);
        registry.markSlotRemoving("group-a", "worker-removing", "test");

        assertEquals(ReserveStatus.STALE_HEARTBEAT,
                registry.slotLifecycleStatus("group-a", "worker-stale", 31_001));
        assertEquals(ReserveStatus.DISPATCH_DISABLED,
                registry.slotLifecycleStatus("group-a", "worker-disabled", 1000));
        assertEquals(ReserveStatus.REMOVING_SLOT,
                registry.slotLifecycleStatus("group-a", "worker-removing", 1000));
        assertEquals(ReserveStatus.GROUP_MISMATCH,
                registry.slotLifecycleStatus("other-group", "worker-disabled", 1000));
        assertEquals(ReserveStatus.MISSING_SLOT,
                registry.slotLifecycleStatus("group-a", "missing-worker", 1000));
    }

    @Test
    void workerIdSemanticDispatchGateMethodsHideSlotGroupLookup() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));

        assertTrue(registry.isDispatchEnabled("worker-1"));
        assertTrue(registry.disableDispatch("worker-1", WORKER_COMMAND));
        assertFalse(registry.isDispatchEnabled("worker-1"));
        assertEquals(ReserveStatus.DISPATCH_DISABLED,
                registry.slotLifecycleStatus("group-a", "worker-1", 1000));
        assertTrue(registry.clearDispatchDisable("worker-1", WORKER_COMMAND));
        assertTrue(registry.isDispatchEnabled("worker-1"));
    }

    @Test
    void clearingOneDispatchGateSourceDoesNotClearAnother() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));

        assertTrue(registry.disableDispatch("group-a", "worker-1", WORKER_COMMAND));
        assertTrue(registry.disableDispatch("group-a", "worker-1", WORKER_STATE));
        assertEquals(ReserveStatus.DISPATCH_DISABLED,
                registry.slotLifecycleStatus("group-a", "worker-1", 1000));
        assertTrue(registry.clearDispatchDisable("group-a", "worker-1", WORKER_COMMAND));
        assertEquals(ReserveStatus.DISPATCH_DISABLED,
                registry.slotLifecycleStatus("group-a", "worker-1", 1000));
        assertTrue(registry.clearDispatchDisable("group-a", "worker-1", WORKER_STATE));
        assertEquals(ReserveStatus.ACCEPTED,
                registry.slotLifecycleStatus("group-a", "worker-1", 1000));
    }

    @Test
    void blockDispatchStoresSourceScopedMetadataWhenImplementationSupportsIt() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));
        WorkerDispatchBlockRecord older = new WorkerDispatchBlockRecord(WORKER_STATE, "old", 100, 500);
        WorkerDispatchBlockRecord newer = new WorkerDispatchBlockRecord(WORKER_STATE, "new", 200, 600);

        assertTrue(registry.blockDispatch("group-a", "worker-1", newer));
        assertFalse(registry.blockDispatch("group-a", "worker-1", older));
        assertEquals(newer, registry.dispatchBlockRecord("group-a", "worker-1", WORKER_STATE).orElseThrow());
    }

    @Test
    void bulkGroupRemovalIsSemanticAndDoesNotRequireCallerToListWorkers() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-a", "group-a"), 1, Set.of(eventKey()));
        registry.upsertSlot(meta("worker-b", "group-a"), 1, Set.of(eventKey()));
        registry.upsertSlot(meta("worker-c", "group-b"), 1, Set.of(eventKey()));

        assertEquals(2, registry.markWorkersRemovingByGroup("group-a", "group deleted"));
        assertEquals(ReserveStatus.REMOVING_SLOT,
                registry.slotLifecycleStatus("group-a", "worker-a", 1000));
        assertEquals(ReserveStatus.REMOVING_SLOT,
                registry.slotLifecycleStatus("group-a", "worker-b", 1000));
        assertEquals(ReserveStatus.ACCEPTED,
                registry.slotLifecycleStatus("group-b", "worker-c", 1000));
    }

    @Test
    void cleanupExpiredHeartbeatsMarksSlotsRemoving() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(metaAt("worker-stale", "group-a", 1_000), 1, Set.of(eventKey()));

        assertEquals(ReserveStatus.STALE_HEARTBEAT,
                registry.slotLifecycleStatus("group-a", "worker-stale", 31_001));
        assertEquals(1, registry.cleanupExpiredHeartbeats(31_001, 10).removed());
        assertEquals(ReserveStatus.REMOVING_SLOT,
                registry.slotLifecycleStatus("group-a", "worker-stale", 31_001));
    }

    @Test
    void cleanupRemovedSlotsDeletesRemovingEmptySlots() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));
        assertTrue(registry.markSlotRemoving("group-a", "worker-1", "test"));

        assertEquals(1, registry.cleanupRemovedSlots("group-a", 10).removed());
        assertTrue(registry.slot("group-a", "worker-1").isEmpty());
        assertTrue(registry.slotByWorkerId("worker-1").isEmpty());
    }

    @Test
    void exclusiveLeaseIsOwnedByRegistrySlot() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));

        assertFalse(registry.hasExclusiveLease("worker-1"));
        assertTrue(registry.tryAcquireExclusiveLease("group-a", "worker-1"));
        assertFalse(registry.tryAcquireExclusiveLease("group-a", "worker-1"));
        assertTrue(registry.hasExclusiveLease("worker-1"));
        assertEquals(List.of("worker-1"), registry.exclusiveLeaseWorkerIds());

        registry.releaseExclusiveLease("group-a", "worker-1");
        assertFalse(registry.hasExclusiveLease("worker-1"));
        assertTrue(registry.exclusiveLeaseWorkerIds().isEmpty());
        assertTrue(registry.tryAcquireExclusiveLease("group-a", "worker-1"));
    }

    @Test
    void removingSlotRejectsExclusiveLeaseAcquire() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));
        assertTrue(registry.markSlotRemoving("group-a", "worker-1", "test"));

        assertFalse(registry.tryAcquireExclusiveLease("group-a", "worker-1"));
        assertFalse(registry.hasExclusiveLease("worker-1"));
    }

    protected WorkerMeta meta(String workerId, String groupId) {
        return metaAt(workerId, groupId, 1000);
    }

    protected WorkerMeta metaAt(String workerId, String groupId, long lastHeartbeatMillis) {
        return new WorkerMeta(
                workerId,
                groupId,
                "polling",
                Map.of("region", "us"),
                "agent-1",
                "runtime-1",
                lastHeartbeatMillis,
                "AVAILABLE"
        );
    }

    protected EventKey eventKey() {
        return new EventKey("project-a", "event-a");
    }
}
