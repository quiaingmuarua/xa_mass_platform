package com.xa.mass.worker.runtime;

import com.xa.mass.runtime.worker.WorkerMeta;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.runtime.worker.slot.NoopWorkerScoreBandSlotRuntime;
import com.xa.mass.runtime.worker.slot.WorkerScoreBand;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlot;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlotMetadata;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlotRuntime;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationStore;

import java.util.List;
import java.util.Optional;

/**
 * Worker runtime owner for worker registration rows and registry slot projection.
 */
public final class WorkerResourceOwner {

    private final Object lock = new Object();
    private final WorkerDeclarationStore workerStorage;
    private final WorkerRegistry workerRegistry;
    private final WorkerScoreBandSlotRuntime scoreBandSlotRuntime;
    private final WorkerGroupOwner groupOwner;

    public WorkerResourceOwner(WorkerDeclarationStore workerStorage,
                               WorkerRegistry workerRegistry,
                               WorkerGroupOwner groupOwner) {
        this(workerStorage, workerRegistry, NoopWorkerScoreBandSlotRuntime.INSTANCE, groupOwner);
    }

    public WorkerResourceOwner(WorkerDeclarationStore workerStorage,
                               WorkerRegistry workerRegistry,
                               WorkerScoreBandSlotRuntime scoreBandSlotRuntime,
                               WorkerGroupOwner groupOwner) {
        this.workerStorage = workerStorage;
        this.workerRegistry = workerRegistry;
        this.scoreBandSlotRuntime = scoreBandSlotRuntime != null
                ? scoreBandSlotRuntime
                : NoopWorkerScoreBandSlotRuntime.INSTANCE;
        this.groupOwner = groupOwner;
    }

    public WorkerDeclarationRecord addWorker(WorkerDeclarationRecord worker) {
        WorkerDeclarationRecord declarationRow = normalizeWorkerRegistrationInput(worker);
        workerStorage.addWorker(declarationRow);
        synchronized (lock) {
            upsertWorkerRegistrySlot(declarationRow);
            upsertScoreBandSlot(declarationRow, null, "worker registered");
        }
        return declarationRow;
    }

    public Optional<WorkerDeclarationRecord> getWorker(String workerId) {
        return workerStorage.getWorker(workerId);
    }

    public List<WorkerDeclarationRecord> getAllWorkers() {
        return workerStorage.getAllWorkers();
    }

    public Optional<WorkerDeclarationRecord> updateWorker(WorkerDeclarationRecord worker) {
        WorkerDeclarationRecord declarationRow = normalizeWorkerRegistrationInput(worker);
        WorkerDeclarationRecord previous = workerStorage.getWorker(declarationRow.workerId()).orElse(null);
        boolean updated = workerStorage.updateWorker(declarationRow);
        if (updated) {
            synchronized (lock) {
                upsertWorkerRegistrySlot(declarationRow);
                upsertScoreBandSlot(declarationRow, previous, "worker updated");
            }
            return Optional.of(declarationRow);
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
        WorkerDeclarationRecord existing = getWorker(workerId).orElse(null);
        boolean deleted = workerStorage.deleteWorker(workerId);
        if (deleted) {
            synchronized (lock) {
                markWorkerRemoving(existing, "worker deleted");
                removeScoreBandSlot(existing, "worker deleted");
            }
        }
        return deleted;
    }

    public void syncWorkerRegistrySlots(Iterable<WorkerDeclarationRecord> workers) {
        if (workers == null) {
            return;
        }
        synchronized (lock) {
            for (WorkerDeclarationRecord worker : workers) {
                upsertWorkerRegistrySlot(worker);
                upsertScoreBandSlot(worker, null, "worker registry sync");
            }
        }
    }

    private void upsertWorkerRegistrySlot(WorkerDeclarationRecord worker) {
        String workerId = worker == null ? null : normalizeNullable(worker.workerId());
        WorkerMeta existingMeta = workerId == null ? null : workerRegistry.workerMeta(workerId).orElse(null);
        WorkerMeta meta = workerMeta(worker, existingMeta);
        if (meta == null) {
            return;
        }
        workerRegistry.upsertSlot(meta, worker.maxConcurrentWork(), groupOwner.eventBindingCeilingFor(meta.groupId()));
    }

    private void markWorkerRemoving(WorkerDeclarationRecord worker, String reason) {
        WorkerMeta meta = workerMeta(worker);
        if (meta == null) {
            return;
        }
        workerRegistry.markWorkerRemoving(meta.workerId(), reason);
    }

    private void upsertScoreBandSlot(WorkerDeclarationRecord worker,
                                     WorkerDeclarationRecord previous,
                                     String reason) {
        WorkerScoreBandSlotMetadata metadata = scoreBandMetadata(worker);
        if (metadata == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (previous != null
                && previous.workerGroupId() != null
                && !previous.workerGroupId().equals(metadata.homeBucketId())) {
            scoreBandSlotRuntime.remove(previous.workerGroupId(), metadata.workerId(), "worker home bucket changed", now);
        }
        long score = scoreBandSlotRuntime.slot(metadata.homeBucketId(), metadata.workerId())
                .map(WorkerScoreBandSlot::score)
                .orElse(WorkerScoreBand.eligibleScore(now));
        scoreBandSlotRuntime.upsert(metadata, score, reason, now);
    }

    private void removeScoreBandSlot(WorkerDeclarationRecord worker, String reason) {
        WorkerScoreBandSlotMetadata metadata = scoreBandMetadata(worker);
        if (metadata == null) {
            return;
        }
        scoreBandSlotRuntime.remove(metadata.homeBucketId(), metadata.workerId(), reason, System.currentTimeMillis());
    }

    private WorkerScoreBandSlotMetadata scoreBandMetadata(WorkerDeclarationRecord worker) {
        if (worker == null || normalizeNullable(worker.workerId()) == null || normalizeNullable(worker.workerGroupId()) == null) {
            return null;
        }
        return WorkerScoreBandSlotMetadata.worker(
                worker.workerGroupId(),
                worker.workerId(),
                worker.transportHint(),
                worker.attributes(),
                worker.maxConcurrentWork()
        );
    }

    private WorkerMeta workerMeta(WorkerDeclarationRecord worker) {
        return workerMeta(worker, null);
    }

    private WorkerMeta workerMeta(WorkerDeclarationRecord worker, WorkerMeta existingMeta) {
        String workerId = worker == null ? null : normalizeNullable(worker.workerId());
        String groupId = worker == null ? null : normalizeNullable(worker.workerGroupId());
        if (workerId == null || groupId == null) {
            return null;
        }
        long lastHeartbeatMillis = existingMeta == null ? 0L : existingMeta.lastHeartbeatMillis();
        String diagnosticStatus = existingMeta == null ? null : existingMeta.diagnosticStatus();
        return new WorkerMeta(
                workerId,
                groupId,
                worker.transportHint(),
                worker.attributes(),
                worker.agentVersion(),
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

    private WorkerDeclarationRecord normalizeWorkerRegistrationInput(WorkerDeclarationRecord worker) {
        if (worker == null) {
            throw new IllegalArgumentException("worker must not be null");
        }
        if (worker.workerId() == null) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        return worker;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
