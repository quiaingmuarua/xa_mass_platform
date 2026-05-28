package com.xa.mass.worker.runtime;

import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.runtime.worker.WorkerMeta;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.storage.api.WorkerDeclarationRecord;
import com.xa.mass.storage.api.WorkerDeclarationStore;

import java.time.LocalDateTime;
import java.time.Instant;
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
        Worker registrationRow = normalizeWorkerRegistrationInput(worker);
        WorkerDeclarationRecord declarationRow = toDeclarationRecord(registrationRow);
        workerStorage.addWorker(declarationRow);
        synchronized (lock) {
            upsertWorkerRegistrySlot(registrationRow);
        }
        applyNodeGroupBindingDispatchGate(registrationRow);
        return toWorkerWithRuntimeState(declarationRow);
    }

    public Optional<Worker> getWorker(String workerId) {
        return workerStorage.getWorker(workerId).map(this::toWorkerWithRuntimeState);
    }

    public List<Worker> getAllWorkers() {
        return workerStorage.getAllWorkers().stream()
                .map(this::toWorkerWithRuntimeState)
                .toList();
    }

    public Optional<Worker> updateWorker(Worker worker) {
        Worker registrationRow = normalizeWorkerRegistrationInput(worker);
        WorkerDeclarationRecord declarationRow = toDeclarationRecord(registrationRow);
        boolean updated = workerStorage.updateWorker(declarationRow);
        if (updated) {
            synchronized (lock) {
                upsertWorkerRegistrySlot(registrationRow);
            }
            applyNodeGroupBindingDispatchGate(registrationRow);
            return Optional.of(toWorkerWithRuntimeState(declarationRow));
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

    private Worker normalizeWorkerRegistrationInput(Worker worker) {
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
        return worker;
    }

    private WorkerDeclarationRecord toDeclarationRecord(Worker worker) {
        return new WorkerDeclarationRecord(
                worker.getWorkerId(),
                worker.getWorkerGroupId(),
                worker.getAdapterNodeId(),
                worker.getAdapterId(),
                worker.getOnlineStrategy(),
                worker.getAgentVersion(),
                worker.getMaxConcurrentWork(),
                worker.getAttributes(),
                worker.getCreateTime(),
                worker.getUpdateTime()
        );
    }

    private Worker toWorkerWithRuntimeState(WorkerDeclarationRecord declaration) {
        Worker worker = toWorker(declaration);
        workerRegistry.slotByWorkerId(worker.getWorkerId()).ifPresent(slot -> {
            WorkerMeta meta = slot.meta();
            worker.setStatus(toWorkerStatus(meta.diagnosticStatus()));
            if (meta.lastHeartbeatMillis() > 0L) {
                worker.setLastHeartbeat(LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(meta.lastHeartbeatMillis()),
                        ZoneId.systemDefault()
                ));
            }
        });
        return worker;
    }

    private Worker toWorker(WorkerDeclarationRecord declaration) {
        Worker worker = new Worker();
        worker.setWorkerId(declaration.workerId());
        worker.setStatus(WorkerStatus.OFFLINE);
        worker.setAgentVersion(declaration.agentVersion());
        worker.setWorkerGroupId(declaration.workerGroupId());
        worker.setAdapterNodeId(declaration.adapterNodeId());
        worker.setAdapterId(declaration.adapterId());
        worker.setOnlineStrategy(declaration.onlineStrategy());
        worker.setMaxConcurrentWork(declaration.maxConcurrentWork());
        worker.setAttributes(declaration.attributes());
        worker.setCreateTime(declaration.createTime() != null ? declaration.createTime() : LocalDateTime.now());
        worker.setUpdateTime(declaration.updateTime() != null ? declaration.updateTime() : LocalDateTime.now());
        return worker;
    }

    private WorkerStatus toWorkerStatus(String statusName) {
        String normalizedStatus = normalizeNullable(statusName);
        if (normalizedStatus == null) {
            return WorkerStatus.OFFLINE;
        }
        return WorkerStatus.valueOf(normalizedStatus);
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
