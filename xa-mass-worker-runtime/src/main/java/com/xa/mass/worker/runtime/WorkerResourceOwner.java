package com.xa.mass.worker.runtime;

import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.runtime.worker.WorkerMeta;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.storage.api.WorkerDeclarationStore;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Worker runtime owner for worker registration rows and registry slot projection.
 */
public final class WorkerResourceOwner {

    private final Object lock = new Object();
    private final WorkerDeclarationStore workerStorage;
    private final WorkerRegistry workerRegistry;
    private final WorkerGroupOwner groupOwner;
    private final WorkerRelationshipOwner relationshipOwner;

    public WorkerResourceOwner(WorkerDeclarationStore workerStorage,
                               WorkerRegistry workerRegistry,
                               WorkerGroupOwner groupOwner,
                               WorkerRelationshipOwner relationshipOwner) {
        this.workerStorage = workerStorage;
        this.workerRegistry = workerRegistry;
        this.groupOwner = groupOwner;
        this.relationshipOwner = relationshipOwner;
    }

    public Worker addWorker(Worker worker) {
        Worker registrationRow = normalizeWorkerRegistrationRow(worker);
        workerStorage.addWorker(registrationRow);
        synchronized (lock) {
            upsertWorkerRegistrySlot(registrationRow);
        }
        applyNodeGroupBindingDispatchGate(registrationRow);
        return registrationRow;
    }

    public Optional<Worker> getWorker(String workerId) {
        return workerStorage.getWorker(workerId);
    }

    public List<Worker> getAllWorkers() {
        return workerStorage.getAllWorkers();
    }

    public Optional<Worker> updateWorker(Worker worker) {
        Worker registrationRow = normalizeWorkerRegistrationRow(worker);
        boolean updated = workerStorage.updateWorker(registrationRow);
        if (updated) {
            synchronized (lock) {
                upsertWorkerRegistrySlot(registrationRow);
            }
            applyNodeGroupBindingDispatchGate(registrationRow);
            return Optional.of(registrationRow);
        }
        return Optional.empty();
    }

    public boolean deleteWorker(String workerId) {
        Worker existing = getWorker(workerId).orElse(null);
        boolean deleted = workerStorage.deleteWorker(workerId);
        if (deleted) {
            synchronized (lock) {
                markWorkerRegistrySlotRemoving(existing, "worker deleted");
            }
        }
        return deleted;
    }

    public void syncWorkerRegistrySlots(Iterable<Worker> workers) {
        if (workers == null) {
            return;
        }
        synchronized (lock) {
            for (Worker worker : workers) {
                upsertWorkerRegistrySlot(worker);
            }
        }
    }

    private void upsertWorkerRegistrySlot(Worker worker) {
        WorkerMeta meta = workerMeta(worker);
        if (meta == null) {
            return;
        }
        workerRegistry.upsertSlot(meta, worker.getMaxConcurrentWork(), groupOwner.eventBindingCeilingFor(meta.groupId()));
    }

    private void markWorkerRegistrySlotRemoving(Worker worker, String reason) {
        WorkerMeta meta = workerMeta(worker);
        if (meta == null) {
            return;
        }
        workerRegistry.markSlotRemoving(meta.groupId(), meta.workerId(), reason);
    }

    private WorkerMeta workerMeta(Worker worker) {
        String workerId = worker == null ? null : normalizeNullable(worker.getWorkerId());
        String groupId = worker == null ? null : normalizeNullable(worker.getWorkerGroupId());
        if (workerId == null || groupId == null) {
            return null;
        }
        long lastHeartbeatMillis = worker.getLastHeartbeat() == null
                ? 0L
                : worker.getLastHeartbeat().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new WorkerMeta(
                workerId,
                groupId,
                normalizeNullable(worker.getAdapterNodeId()),
                normalizeNullable(worker.getAdapterId()),
                normalizeNullable(worker.getOnlineStrategy()),
                worker.getAttributes(),
                normalizeNullable(worker.getAgentVersion()),
                null,
                lastHeartbeatMillis,
                worker.getStatus() == null ? null : worker.getStatus().name()
        );
    }

    private Worker normalizeWorkerRegistrationRow(Worker worker) {
        if (worker == null) {
            throw new IllegalArgumentException("worker must not be null");
        }
        String workerId = normalizeNullable(worker.getWorkerId());
        if (workerId == null) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        worker.setWorkerId(workerId);
        String groupId = normalizeNullable(worker.getWorkerGroupId());
        if (groupId != null) {
            worker.setWorkerGroupId(groupId);
        }
        String adapterNodeId = normalizeNullable(worker.getAdapterNodeId());
        if (adapterNodeId != null) {
            relationshipOwner.validateExplicitWorkerNodeGroupMembership(adapterNodeId, groupId);
            worker.setAdapterNodeId(adapterNodeId);
        }
        if (worker.getStatus() == WorkerStatus.ONLINE && worker.getLastHeartbeat() == null) {
            worker.setLastHeartbeat(LocalDateTime.now());
        }
        return worker;
    }

    private void applyNodeGroupBindingDispatchGate(Worker worker) {
        if (worker != null) {
            relationshipOwner.applyNodeGroupBindingDispatchGate(worker);
        }
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
