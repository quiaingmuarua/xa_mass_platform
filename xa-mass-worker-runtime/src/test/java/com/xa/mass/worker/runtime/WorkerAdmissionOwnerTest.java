package com.xa.mass.worker.runtime;

import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionStatus;
import com.xa.mass.runtime.worker.WorkerMeta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerAdmissionOwnerTest {

    @Test
    void reservesConfirmsAndFinalizesWorkerCapacityThroughRegistry() {
        InMemoryWorkerRegistry registry = new InMemoryWorkerRegistry();
        registry.upsertSlot(workerMeta("worker-1"), 1, Set.of());
        WorkerAdmissionOwner owner = new WorkerAdmissionOwner(registry);

        assertTrue(owner.reserveWorkerCapacity("worker-1", "task-1").accepted());
        assertFalse(owner.reserveWorkerCapacity("worker-1", "task-2").accepted());
        assertEquals(1, owner.getWorkerLoad("worker-1").reservedCount());

        assertTrue(owner.confirmWorkerReservation("worker-1", "task-1"));
        assertEquals(0, owner.getWorkerLoad("worker-1").reservedCount());
        assertEquals(1, owner.getWorkerLoad("worker-1").activeLeaseCount());

        owner.recordWorkFinal("worker-1", "task-1");
        assertEquals(0, owner.getWorkerLoad("worker-1").activeLeaseCount());
    }

    @Test
    void resolvesExclusiveLeaseByWorkerId() {
        InMemoryWorkerRegistry registry = new InMemoryWorkerRegistry();
        registry.upsertSlot(workerMeta("worker-lease"), 1, Set.of());
        WorkerAdmissionOwner owner = new WorkerAdmissionOwner(registry);

        assertTrue(owner.tryAcquireWorkerExclusiveLease("worker-lease"));
        assertTrue(owner.hasWorkerExclusiveLease("worker-lease"));
        assertEquals(List.of("worker-lease"), owner.getExclusiveLeaseWorkerIds());

        owner.releaseWorkerExclusiveLease("worker-lease");
        assertFalse(owner.hasWorkerExclusiveLease("worker-lease"));
    }

    @Test
    void missingWorkerSlotRejectsReservation() {
        WorkerAdmissionOwner owner = new WorkerAdmissionOwner(new InMemoryWorkerRegistry());

        assertEquals(WorkerAdmissionStatus.MISSING_SLOT,
                owner.reserveWorkerCapacity("missing-worker", "task-1").status());
    }

    private static WorkerMeta workerMeta(String workerId) {
        return new WorkerMeta(
                workerId,
                "group-1",
                null,
                "test-adapter",
                "HEARTBEAT",
                Map.of(),
                "test",
                null,
                System.currentTimeMillis(),
                "ONLINE"
        );
    }
}
