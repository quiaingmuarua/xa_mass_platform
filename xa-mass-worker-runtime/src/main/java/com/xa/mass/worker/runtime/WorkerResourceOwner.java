package com.xa.mass.worker.runtime;

import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.runtime.worker.WorkerMeta;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationStore;

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

    public WorkerResourceOwner(WorkerDeclarationStore workerStorage,
                               WorkerRegistry workerRegistry,
                               WorkerGroupOwner groupOwner) {
        this.workerStorage = workerStorage;
        this.workerRegistry = workerRegistry;
        this.groupOwner = groupOwner;
    }

    public Worker addWorker(Worker worker) {
        Worker registrationRow = normalizeWorkerRegistrationInput(worker);
        WorkerDeclarationRecord declarationRow = toDeclarationRecord(registrationRow);
        workerStorage.addWorker(declarationRow);
        synchronized (lock) {
            upsertWorkerRegistrySlot(registrationRow);
        }
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
            return Optional.of(toWorkerWithRuntimeState(declarationRow));
        }
        return Optional.empty();
    }

    public boolean refreshWorkerHeartbeat(String workerId, long observedAtMillis) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return false;
        }
        Optional<WorkerDeclarationRecord> declaration = workerStorage.getWorker(normalizedWorkerId);
        if (declaration.isEmpty()) {
            return false;
        }
        long heartbeatMillis = observedAtMillis > 0L ? observedAtMillis : System.currentTimeMillis();
        synchronized (lock) {
            String diagnosticStatus = workerRegistry.workerMeta(normalizedWorkerId)
                    .map(WorkerMeta::diagnosticStatus)
                    .orElse(null);
            WorkerMeta meta = workerMeta(declaration.get(), heartbeatMillis, diagnosticStatus);
            if (meta == null) {
                return false;
            }
            workerRegistry.upsertSlot(
                    meta,
                    declaration.get().maxConcurrentWork(),
                    groupOwner.eventBindingCeilingFor(meta.groupId())
            );
            return true;
        }
    }

    public boolean deleteWorker(String workerId) {
        Worker existing = getWorker(workerId).orElse(null);
        boolean deleted = workerStorage.deleteWorker(workerId);
        if (deleted) {
            synchronized (lock) {
                markWorkerRemoving(existing, "worker deleted");
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
        String workerId = worker == null ? null : normalizeNullable(worker.getWorkerId());
        WorkerMeta existingMeta = workerId == null ? null : workerRegistry.workerMeta(workerId).orElse(null);
        WorkerMeta meta = workerMeta(worker, existingMeta);
        if (meta == null) {
            return;
        }
        workerRegistry.upsertSlot(meta, worker.getMaxConcurrentWork(), groupOwner.eventBindingCeilingFor(meta.groupId()));
    }

    private void markWorkerRemoving(Worker worker, String reason) {
        WorkerMeta meta = workerMeta(worker);
        if (meta == null) {
            return;
        }
        workerRegistry.markWorkerRemoving(meta.workerId(), reason);
    }

    private WorkerMeta workerMeta(Worker worker) {
        return workerMeta(worker, null);
    }

    private WorkerMeta workerMeta(Worker worker, WorkerMeta existingMeta) {
        String workerId = worker == null ? null : normalizeNullable(worker.getWorkerId());
        String groupId = worker == null ? null : normalizeNullable(worker.getWorkerGroupId());
        if (workerId == null || groupId == null) {
            return null;
        }
        long lastHeartbeatMillis = worker.getLastHeartbeat() == null
                ? existingMeta == null ? 0L : existingMeta.lastHeartbeatMillis()
                : worker.getLastHeartbeat().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        String diagnosticStatus = existingMeta == null
                ? worker.getStatus() == null ? null : worker.getStatus().name()
                : existingMeta.diagnosticStatus();
        return new WorkerMeta(
                workerId,
                groupId,
                normalizeNullable(worker.getOnlineStrategy()),
                worker.getAttributes(),
                normalizeNullable(worker.getAgentVersion()),
                null,
                lastHeartbeatMillis,
                diagnosticStatus
        );
    }

    private WorkerMeta workerMeta(WorkerDeclarationRecord declaration,
                                  long lastHeartbeatMillis,
                                  String diagnosticStatus) {
        if (declaration == null || declaration.workerId() == null || declaration.workerGroupId() == null) {
            return null;
        }
        return new WorkerMeta(
                declaration.workerId(),
                declaration.workerGroupId(),
                declaration.transportHint(),
                declaration.attributes(),
                declaration.agentVersion(),
                null,
                lastHeartbeatMillis,
                diagnosticStatus
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
        return worker;
    }

    private WorkerDeclarationRecord toDeclarationRecord(Worker worker) {
        return new WorkerDeclarationRecord(
                worker.getWorkerId(),
                worker.getWorkerGroupId(),
                worker.getOnlineStrategy(),
                worker.getAgentVersion(),
                worker.getMaxConcurrentWork(),
                worker.getAttributes()
        );
    }

    private Worker toWorkerWithRuntimeState(WorkerDeclarationRecord declaration) {
        Worker worker = toWorker(declaration);
        workerRegistry.workerMeta(worker.getWorkerId()).ifPresent(meta -> {
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
        worker.setOnlineStrategy(declaration.transportHint());
        worker.setMaxConcurrentWork(declaration.maxConcurrentWork());
        worker.setAttributes(declaration.attributes());
        worker.setCreateTime(LocalDateTime.now());
        worker.setUpdateTime(LocalDateTime.now());
        return worker;
    }

    private WorkerStatus toWorkerStatus(String statusName) {
        String normalizedStatus = normalizeNullable(statusName);
        if (normalizedStatus == null) {
            return WorkerStatus.OFFLINE;
        }
        return WorkerStatus.valueOf(normalizedStatus);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
