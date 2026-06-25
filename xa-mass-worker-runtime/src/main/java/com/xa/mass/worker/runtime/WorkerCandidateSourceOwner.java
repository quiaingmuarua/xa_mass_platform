package com.xa.mass.worker.runtime;

import com.xa.mass.worker.runtime.candidate.WorkerCandidateRow;
import com.xa.mass.worker.runtime.candidate.WorkerTaskSelector;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Runtime owner for Stage-1 candidate acquisition.
 */
public final class WorkerCandidateSourceOwner {

    private final Supplier<WorkerCandidateIndex> candidateIndexSupplier;

    public WorkerCandidateSourceOwner(Supplier<WorkerCandidateIndex> candidateIndexSupplier) {
        this.candidateIndexSupplier = Objects.requireNonNull(candidateIndexSupplier, "candidateIndexSupplier");
    }

    public List<WorkerCandidateRow> findWorkerCandidates(WorkerTaskSelector selector,
                                                         int maxCandidateCount) {
        if (selector == null) {
            return List.of();
        }
        if (!selector.targetsWorker() && maxCandidateCount <= 0) {
            return List.of();
        }
        int limit = !selector.targetsWorker()
                ? Math.max(1, maxCandidateCount)
                : 1;
        WorkerCandidateIndex candidateIndex = candidateIndexSupplier.get();
        return toCandidateRows(candidateIndex.workersFor(selector, limit), limit);
    }

    private static List<WorkerCandidateRow> toCandidateRows(List<WorkerDeclarationRecord> candidates, int limit) {
        LinkedHashMap<String, WorkerCandidateRow> deduped = new LinkedHashMap<>();
        if (candidates != null) {
            for (WorkerDeclarationRecord worker : candidates) {
                if (worker != null && worker.workerId() != null) {
                    deduped.putIfAbsent(worker.workerId(), toCandidateRow(worker));
                }
                if (deduped.size() >= limit) {
                    break;
                }
            }
        }
        return List.copyOf(deduped.values());
    }

    private static WorkerCandidateRow toCandidateRow(WorkerDeclarationRecord worker) {
        return new WorkerCandidateRow(
                worker.workerId(),
                worker.agentVersion(),
                worker.workerGroupId(),
                worker.transportHint(),
                worker.attributes()
        );
    }

}
