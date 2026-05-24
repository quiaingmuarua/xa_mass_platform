package com.xa.mass.engine.worker;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.xa.mass.engine.worker.WorkerDispatchAvailabilityOwner.DispatchAvailabilitySource.WORKER_COMMAND;
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
    void tryReserveDoesNotDeduplicateByTaskId() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 2, Set.of(eventKey()));

        assertTrue(registry.tryReserve("group-a", "worker-1", "task-1", 1, 1000).accepted());
        assertTrue(registry.tryReserve("group-a", "worker-1", "task-1", 1, 1000).accepted());
        assertEquals(2, registry.slot("group-a", "worker-1").orElseThrow().reservedCount());
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
    void clearingOneDispatchGateSourceDoesNotClearAnother() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));

        assertTrue(registry.disableDispatch("group-a", "worker-1", WORKER_COMMAND));
        assertEquals(ReserveStatus.DISPATCH_DISABLED,
                registry.tryReserve("group-a", "worker-1", "task-1", 1, 1000).status());
        assertTrue(registry.clearDispatchDisable("group-a", "worker-1", WORKER_COMMAND));
        assertTrue(registry.tryReserve("group-a", "worker-1", "task-1", 1, 1000).accepted());
    }

    protected WorkerMeta meta(String workerId, String groupId) {
        return new WorkerMeta(
                workerId,
                groupId,
                "node-a",
                "polling",
                "polling",
                Map.of("region", "us"),
                "agent-1",
                "runtime-1",
                1000,
                "AVAILABLE"
        );
    }

    protected EventKey eventKey() {
        return new EventKey("project-a", "event-a");
    }
}
