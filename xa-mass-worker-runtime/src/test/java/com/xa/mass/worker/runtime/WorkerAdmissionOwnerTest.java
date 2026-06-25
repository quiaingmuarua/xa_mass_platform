package com.xa.mass.worker.runtime;

import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
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
    void readsLoadSnapshotFromRegistrySlot() {
        InMemoryWorkerRegistry registry = new InMemoryWorkerRegistry();
        registry.upsertSlot(workerMeta("worker-load"), 3, Set.of());
        WorkerAdmissionOwner owner = new WorkerAdmissionOwner(registry);

        assertEquals(3, owner.getWorkerLoad("worker-load").declaredCapacity());
        assertEquals(0, owner.getWorkerLoad("worker-load").activeLeaseCount());
        assertEquals(0, owner.getWorkerLoad("worker-load").reservedCount());
    }

    private static WorkerMeta workerMeta(String workerId) {
        return new WorkerMeta(
                workerId,
                "group-1",
                "HEARTBEAT",
                Map.of(),
                "test",
                null,
                System.currentTimeMillis(),
                "ONLINE"
        );
    }
}
