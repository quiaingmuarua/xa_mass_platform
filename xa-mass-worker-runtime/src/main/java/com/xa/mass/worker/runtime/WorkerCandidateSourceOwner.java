package com.xa.mass.worker.runtime;

import com.xa.mass.base.model.Worker;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateBatch;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateRow;
import com.xa.mass.runtime.worker.WorkerCandidateBucketPolicy;
import com.xa.mass.worker.runtime.candidate.WorkerTaskSelector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Runtime owner for Stage-1 candidate acquisition and warm hint state.
 */
public final class WorkerCandidateSourceOwner {

    private final Supplier<WorkerCandidateIndex> candidateIndexSupplier;
    private final TaskCandidateWarmPool taskCandidateWarmPool;

    public WorkerCandidateSourceOwner(Supplier<WorkerCandidateIndex> candidateIndexSupplier) {
        this(candidateIndexSupplier, new TaskCandidateWarmPool());
    }

    public WorkerCandidateSourceOwner(Supplier<WorkerCandidateIndex> candidateIndexSupplier,
                                      TaskCandidateWarmPool taskCandidateWarmPool) {
        this.candidateIndexSupplier = Objects.requireNonNull(candidateIndexSupplier, "candidateIndexSupplier");
        this.taskCandidateWarmPool = taskCandidateWarmPool == null ? new TaskCandidateWarmPool() : taskCandidateWarmPool;
    }

    public WorkerCandidateBatch<WorkerCandidateRow> findWorkerCandidateBatch(WorkerTaskSelector selector,
                                                                             int maxCandidateCount) {
        if (selector == null) {
            return WorkerCandidateBatch.empty();
        }
        if (!selector.targetsWorker() && maxCandidateCount <= 0) {
            return WorkerCandidateBatch.empty();
        }
        int limit = !selector.targetsWorker()
                ? Math.max(1, maxCandidateCount)
                : 1;
        WorkerCandidateIndex candidateIndex = candidateIndexSupplier.get();
        if (selector.targetsWorker()) {
            List<Worker> targetCandidates = candidateIndex.workersFor(selector, limit);
            CandidateMerge targetMerge = mergeWarmAndCold(List.of(), targetCandidates, limit);
            return new WorkerCandidateBatch<>(targetMerge.candidates(), 0, targetCandidates.size(), 0,
                    targetMerge.duplicateCandidateCount());
        }
        WarmCandidateSelection warmSelection = warmCandidatesFor(selector, candidateIndex, limit);
        List<WorkerCandidateRow> warmCandidates = warmSelection.candidates();
        List<Worker> coldCandidates = candidateIndex.workersFor(selector, limit);
        if (warmCandidates.isEmpty()) {
            CandidateMerge coldMerge = mergeWarmAndCold(List.of(), coldCandidates, limit);
            return new WorkerCandidateBatch<>(coldMerge.candidates(), 0, coldCandidates.size(),
                    warmSelection.sourceGuardRejectedCount(), coldMerge.duplicateCandidateCount());
        }
        CandidateMerge merge = mergeWarmAndCold(warmCandidates, coldCandidates, limit);
        return new WorkerCandidateBatch<>(merge.candidates(), warmCandidates.size(),
                coldCandidates.size(), warmSelection.sourceGuardRejectedCount(), merge.duplicateCandidateCount());
    }

    public void recordWarmCandidate(WorkerTaskSelector selector, WorkerCandidateRow candidate) {
        String taskId = selector == null ? null : normalizeNullable(selector.taskId());
        String workerId = candidate == null ? null : normalizeNullable(candidate.workerId());
        String groupId = candidate == null ? null : normalizeNullable(candidate.workerGroupId());
        if (taskId == null || workerId == null || groupId == null) {
            return;
        }
        if (selector.targetsWorker()) {
            return;
        }
        long nowMillis = System.currentTimeMillis();
        for (String candidateBucketKey : candidateBucketKeysForTask(selector)) {
            taskCandidateWarmPool.put(new TaskCandidateWarmPool.Entry(
                    taskId,
                    workerId,
                    groupId,
                    normalizeNullable(candidate.adapterNodeId()),
                    candidateBucketKey,
                    nowMillis
            ));
        }
    }

    public int warmCandidateCount(String taskId) {
        return taskCandidateWarmPool.sizeForTask(taskId);
    }

    private WarmCandidateSelection warmCandidatesFor(WorkerTaskSelector selector,
                                                     WorkerCandidateIndex candidateIndex,
                                                     int limit) {
        String taskId = selector == null ? null : normalizeNullable(selector.taskId());
        if (taskId == null || limit <= 0) {
            return WarmCandidateSelection.empty();
        }
        List<WorkerCandidateRow> workers = new ArrayList<>();
        int rejected = 0;
        for (TaskCandidateWarmPool.Entry entry : taskCandidateWarmPool.sample(
                taskId,
                System.currentTimeMillis(),
                limit
        )) {
            WorkerCandidateIndex.SourceGuardResult guardResult = candidateIndex.sourceGuard(
                    selector,
                    entry.observedGroupId(),
                    entry.observedAdapterNodeId(),
                    entry.observedCandidateBucketKey(),
                    entry.workerId()
            );
            if (guardResult.accepted()) {
                guardResult.worker().map(WorkerCandidateSourceOwner::toCandidateRow).ifPresent(workers::add);
            } else {
                rejected++;
                taskCandidateWarmPool.remove(entry);
            }
        }
        return new WarmCandidateSelection(List.copyOf(workers), rejected);
    }

    private static CandidateMerge mergeWarmAndCold(List<WorkerCandidateRow> warmCandidates,
                                                   List<Worker> coldCandidates,
                                                   int limit) {
        LinkedHashMap<String, WorkerCandidateRow> deduped = new LinkedHashMap<>();
        int duplicateCount = 0;
        if (warmCandidates != null) {
            for (WorkerCandidateRow candidate : warmCandidates) {
                if (candidate != null && candidate.workerId() != null) {
                    WorkerCandidateRow previous = deduped.putIfAbsent(candidate.workerId(), candidate);
                    if (previous != null) {
                        duplicateCount++;
                    }
                }
                if (deduped.size() >= limit) {
                    return new CandidateMerge(List.copyOf(deduped.values()), duplicateCount);
                }
            }
        }
        if (coldCandidates != null) {
            for (Worker worker : coldCandidates) {
                if (worker != null && worker.getWorkerId() != null) {
                    WorkerCandidateRow previous = deduped.putIfAbsent(worker.getWorkerId(), toCandidateRow(worker));
                    if (previous != null) {
                        duplicateCount++;
                    }
                }
                if (deduped.size() >= limit) {
                    break;
                }
            }
        }
        return new CandidateMerge(List.copyOf(deduped.values()), duplicateCount);
    }

    private static WorkerCandidateRow toCandidateRow(Worker worker) {
        return new WorkerCandidateRow(
                worker.getWorkerId(),
                worker.getStatus() == null ? null : worker.getStatus().name(),
                worker.getAgentVersion(),
                worker.getLastHeartbeat(),
                worker.getSupportedProjects(),
                worker.getSupportedEventCodes(),
                worker.getWorkerGroupId(),
                worker.getAdapterNodeId(),
                worker.getAdapterId(),
                worker.getOnlineStrategy(),
                worker.getMaxConcurrentWork(),
                worker.getAttributes(),
                worker.getCreateTime(),
                worker.getUpdateTime(),
                worker.isAvailable()
        );
    }

    private Set<String> candidateBucketKeysForTask(WorkerTaskSelector selector) {
        return selector == null ? Set.of(WorkerCandidateBucketPolicy.DEFAULT_CANDIDATE_BUCKET_KEY) : selector.candidateBucketKeys();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record WarmCandidateSelection(List<WorkerCandidateRow> candidates, int sourceGuardRejectedCount) {
        private WarmCandidateSelection {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            sourceGuardRejectedCount = Math.max(0, sourceGuardRejectedCount);
        }

        static WarmCandidateSelection empty() {
            return new WarmCandidateSelection(List.of(), 0);
        }
    }

    private record CandidateMerge(List<WorkerCandidateRow> candidates, int duplicateCandidateCount) {
        private CandidateMerge {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            duplicateCandidateCount = Math.max(0, duplicateCandidateCount);
        }
    }
}
