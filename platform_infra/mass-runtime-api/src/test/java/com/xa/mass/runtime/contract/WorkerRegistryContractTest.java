package com.xa.mass.runtime.contract;

import com.xa.mass.runtime.worker.EventKey;
import com.xa.mass.runtime.worker.ReserveStatus;
import com.xa.mass.runtime.worker.WorkerCandidateSamplingPolicy;
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

    protected abstract WorkerRegistry createRegistry(WorkerCandidateSamplingPolicy samplingPolicy);

    protected WorkerRegistry createRegistry() {
        return createRegistry((context, workerIds, maxCandidateCount) ->
                workerIds.stream().limit(Math.max(0, maxCandidateCount)).toList());
    }

    @Test
    void duplicateCandidateSamplingIsAllowedBeforeStageTwoValidation() {
        WorkerRegistry registry = createRegistry((context, workerIds, maxCandidateCount) -> List.of("worker-1", "worker-1"));
        registry.upsertSlot(meta("worker-1", "group-a"), 2, Set.of(eventKey()));

        assertEquals(List.of("worker-1", "worker-1"),
                registry.acquireCandidates("group-a", "default", 10));
    }

    @Test
    void acquireCandidatesReturnsBoundedSourceBatch() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));
        registry.upsertSlot(meta("worker-2", "group-a"), 1, Set.of(eventKey()));
        registry.upsertSlot(meta("worker-3", "group-a"), 1, Set.of(eventKey()));

        List<String> candidates = registry.acquireCandidates("group-a", "default", 2);

        assertTrue(candidates.size() <= 2);
        assertTrue(Set.of("worker-1", "worker-2", "worker-3").containsAll(candidates));
    }

    @Test
    void schedulingAcquireCandidatesFiltersSlotLifecycleByDeadlineAndGate() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(metaAt("worker-stale", "group-a", 1_000), 1, Set.of(eventKey()));
        registry.upsertSlot(metaAt("worker-disabled", "group-a", 2_000), 1, Set.of(eventKey()));
        registry.upsertSlot(metaAt("worker-removing", "group-a", 2_000), 1, Set.of(eventKey()));
        registry.upsertSlot(metaAt("worker-fresh", "group-a", 2_000), 1, Set.of(eventKey()));
        registry.disableDispatch("group-a", "worker-disabled", WORKER_STATE);
        registry.markSlotRemoving("group-a", "worker-removing", "test");

        assertEquals(List.of("worker-fresh"),
                registry.acquireCandidates("group-a", "default", 10, 31_001));
    }

    @Test
    void tryReserveDoesNotDeduplicateByTaskId() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 2, Set.of(eventKey()));

        assertTrue(registry.tryReserve("group-a", "worker-1", "task-1", 1, 1000).accepted());
        assertTrue(registry.tryReserve("group-a", "worker-1", "task-1", 1, 1000).accepted());
        assertEquals(2, registry.slot("group-a", "worker-1").orElseThrow().reservedCount());
    }

    @Test
    void tryReserveAcceptsWhileCapacityRemainsAndRejectsAtCapacity() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 2, Set.of(eventKey()));

        assertTrue(registry.tryReserve("group-a", "worker-1", "task-1", 1, 1000).accepted());
        assertTrue(registry.tryReserve("group-a", "worker-1", "task-2", 1, 1000).accepted());
        assertEquals(ReserveStatus.CAPACITY_UNAVAILABLE,
                registry.tryReserve("group-a", "worker-1", "task-3", 1, 1000).status());
        assertEquals(2, registry.slot("group-a", "worker-1").orElseThrow().reservedCount());
    }

    @Test
    void workerIdSemanticAdmissionMethodsDoNotRequireCallerToKnowGroupStorage() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 2, Set.of(eventKey()));

        assertEquals("group-a", registry.workerMeta("worker-1").orElseThrow().groupId());
        assertTrue(registry.tryReserve("worker-1", "task-1", 1, 1000).accepted());
        assertEquals(1, registry.workerAdmissionSnapshot("worker-1").orElseThrow().reservedCount());
        assertTrue(registry.confirmReservation("worker-1", "task-1", 1));
        assertEquals(1, registry.workerAdmissionSnapshot("worker-1").orElseThrow().activeLeaseCount());

        registry.recordWorkFinal("worker-1", "task-1", 1);
        assertEquals(0, registry.workerAdmissionSnapshot("worker-1").orElseThrow().activeLeaseCount());
        assertEquals(0, registry.activeWorkerCountForTask("task-1"));
    }

    @Test
    void workerIdSemanticDispatchGateMethodsHideSlotGroupLookup() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));

        assertTrue(registry.isDispatchEnabled("worker-1"));
        assertTrue(registry.disableDispatch("worker-1", WORKER_COMMAND));
        assertFalse(registry.isDispatchEnabled("worker-1"));
        assertEquals(ReserveStatus.DISPATCH_DISABLED,
                registry.tryReserve("worker-1", "task-1", 1, 1000).status());
        assertTrue(registry.clearDispatchDisable("worker-1", WORKER_COMMAND));
        assertTrue(registry.isDispatchEnabled("worker-1"));
    }

    @Test
    void slotLifecycleStatusCoversRegistryOwnedEligibilityButNotCapacity() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));

        assertEquals(ReserveStatus.ACCEPTED,
                registry.slotLifecycleStatus("group-a", "worker-1", 1000));
        assertTrue(registry.tryReserve("group-a", "worker-1", "task-1", 1, 1000).accepted());
        assertEquals(ReserveStatus.ACCEPTED,
                registry.slotLifecycleStatus("group-a", "worker-1", 1000));
        assertEquals(ReserveStatus.CAPACITY_UNAVAILABLE,
                registry.tryReserve("group-a", "worker-1", "task-2", 1, 1000).status());
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
    void slotMembershipQueriesComeFromRegistryTruth() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-a", "group-a"), 1, Set.of(eventKey()));
        registry.upsertSlot(meta("worker-b", "group-a"), 1, Set.of(eventKey()));
        registry.upsertSlot(meta("worker-c", "group-b"), 1, Set.of(eventKey()));

        assertEquals(Set.of("worker-a", "worker-b"), registry.workerIdsByGroupId("group-a"));
        assertEquals(Set.of("worker-a", "worker-b"), registry.workerIdsByAdapterNodeGroup("node-a", "group-a"));
        assertEquals(Set.of(), registry.workerIdsByAdapterNodeGroup("node-b", "group-a"));
    }

    @Test
    void bulkGroupRemovalIsSemanticAndDoesNotRequireCallerToListWorkers() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-a", "group-a"), 1, Set.of(eventKey()));
        registry.upsertSlot(meta("worker-b", "group-a"), 1, Set.of(eventKey()));
        registry.upsertSlot(meta("worker-c", "group-b"), 1, Set.of(eventKey()));

        assertEquals(2, registry.markWorkersRemovingByGroup("group-a", "group deleted"));
        assertEquals(ReserveStatus.REMOVING_SLOT,
                registry.tryReserve("worker-a", "task-a", 1, 1000).status());
        assertEquals(ReserveStatus.REMOVING_SLOT,
                registry.tryReserve("worker-b", "task-b", 1, 1000).status());
        assertTrue(registry.tryReserve("worker-c", "task-c", 1, 1000).accepted());
    }

    @Test
    void bulkNodeGroupDispatchGateIsSemanticAndDoesNotRequireCallerToListWorkers() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-a", "group-a"), 1, Set.of(eventKey()));
        registry.upsertSlot(meta("worker-b", "group-a"), 1, Set.of(eventKey()));
        registry.upsertSlot(meta("worker-c", "group-b"), 1, Set.of(eventKey()));

        assertEquals(2, registry.disableDispatchForAdapterNodeGroup("node-a", "group-a", WORKER_STATE));
        assertFalse(registry.isDispatchEnabled("worker-a"));
        assertFalse(registry.isDispatchEnabled("worker-b"));
        assertTrue(registry.isDispatchEnabled("worker-c"));
        assertEquals(0, registry.disableDispatchForAdapterNodeGroup("node-b", "group-a", WORKER_STATE));

        assertEquals(2, registry.clearDispatchDisableForAdapterNodeGroup("node-a", "group-a", WORKER_STATE));
        assertTrue(registry.isDispatchEnabled("worker-a"));
        assertTrue(registry.isDispatchEnabled("worker-b"));
    }

    @Test
    void removingSlotRejectsNewReserveAndConfirmButKeepsCountersVisible() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));

        assertTrue(registry.tryReserve("group-a", "worker-1", "task-1", 1, 1000).accepted());
        assertTrue(registry.markSlotRemoving("group-a", "worker-1", "test"));

        assertEquals(ReserveStatus.REMOVING_SLOT,
                registry.tryReserve("group-a", "worker-1", "task-2", 1, 1000).status());
        assertFalse(registry.confirmReservation("group-a", "worker-1", "task-1", 1));
        assertEquals(1, registry.slot("group-a", "worker-1").orElseThrow().reservedCount());

        registry.releaseReservation("group-a", "worker-1", "task-1", 1);
        assertEquals(0, registry.slot("group-a", "worker-1").orElseThrow().reservedCount());
    }

    @Test
    void confirmAndFinalMaintainTaskVisibilityIndexes() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));

        assertTrue(registry.tryReserve("group-a", "worker-1", "task-1", 1, 1000).accepted());
        assertTrue(registry.confirmReservation("group-a", "worker-1", "task-1", 1));

        assertEquals(Set.of("worker-1"), registry.activeWorkerIdsByTask("task-1"));
        assertEquals(1, registry.activeWorkerCountForTask("task-1"));
        assertEquals(1, registry.activeLeaseCountByTaskWorker("task-1", "worker-1"));

        registry.recordWorkFinal("group-a", "worker-1", "task-1", 1);
        assertEquals(0, registry.activeWorkerCountForTask("task-1"));
        assertEquals(0, registry.activeLeaseCountByTaskWorker("task-1", "worker-1"));
    }

    @Test
    void recordWorkClaimedMaintainsTaskVisibilityWithoutReservation() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 2, Set.of(eventKey()));

        registry.recordWorkClaimed("group-a", "worker-1", "task-1", 1);

        assertEquals(Set.of("worker-1"), registry.activeWorkerIdsByTask("task-1"));
        assertEquals(1, registry.activeWorkerCountForTask("task-1"));
        assertEquals(1, registry.activeLeaseCountByTaskWorker("task-1", "worker-1"));

        registry.recordWorkFinal("group-a", "worker-1", "task-1", 1);
        assertEquals(0, registry.activeWorkerCountForTask("task-1"));
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

    @Test
    void clearingOneDispatchGateSourceDoesNotClearAnother() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));

        assertTrue(registry.disableDispatch("group-a", "worker-1", WORKER_COMMAND));
        assertTrue(registry.disableDispatch("group-a", "worker-1", WORKER_STATE));
        assertEquals(ReserveStatus.DISPATCH_DISABLED,
                registry.tryReserve("group-a", "worker-1", "task-1", 1, 1000).status());
        assertTrue(registry.clearDispatchDisable("group-a", "worker-1", WORKER_COMMAND));
        assertEquals(ReserveStatus.DISPATCH_DISABLED,
                registry.tryReserve("group-a", "worker-1", "task-1", 1, 1000).status());
        assertTrue(registry.clearDispatchDisable("group-a", "worker-1", WORKER_STATE));
        assertTrue(registry.tryReserve("group-a", "worker-1", "task-1", 1, 1000).accepted());
    }

    @Test
    void staleHeartbeatRejectsReserveBeforeAndAfterCleanup() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(metaAt("worker-stale", "group-a", 1_000), 1, Set.of(eventKey()));

        assertEquals(List.of("worker-stale"), registry.acquireCandidates("group-a", "default", 10));
        assertEquals(ReserveStatus.STALE_HEARTBEAT,
                registry.tryReserve("group-a", "worker-stale", "task-1", 1, 31_001).status());

        assertEquals(1, registry.cleanupExpiredHeartbeats(31_001, 10).removed());
        assertTrue(registry.acquireCandidates("group-a", "default", 10).isEmpty());
        assertEquals(ReserveStatus.REMOVING_SLOT,
                registry.tryReserve("group-a", "worker-stale", "task-2", 1, 31_001).status());
    }

    protected WorkerMeta meta(String workerId, String groupId) {
        return metaAt(workerId, groupId, 1000);
    }

    protected WorkerMeta metaAt(String workerId, String groupId, long lastHeartbeatMillis) {
        return new WorkerMeta(
                workerId,
                groupId,
                "node-a",
                "polling",
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
