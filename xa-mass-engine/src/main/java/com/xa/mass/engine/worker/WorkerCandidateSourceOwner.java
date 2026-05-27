package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Worker;
import com.xa.mass.runtime.worker.WorkerTaskSelector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Package-local owner for Stage-1 candidate acquisition and warm hint state.
 */
final class WorkerCandidateSourceOwner {

    private final Supplier<WorkerCandidateIndex> candidateIndexSupplier;
    private final TaskCandidateWarmPool taskCandidateWarmPool;

    WorkerCandidateSourceOwner(Supplier<WorkerCandidateIndex> candidateIndexSupplier) {
        this(candidateIndexSupplier, new TaskCandidateWarmPool());
    }

    WorkerCandidateSourceOwner(Supplier<WorkerCandidateIndex> candidateIndexSupplier,
                               TaskCandidateWarmPool taskCandidateWarmPool) {
        this.candidateIndexSupplier = Objects.requireNonNull(candidateIndexSupplier, "candidateIndexSupplier");
        this.taskCandidateWarmPool = taskCandidateWarmPool == null ? new TaskCandidateWarmPool() : taskCandidateWarmPool;
    }

    List<Worker> findWorkerCandidates(WorkerTaskSelector selector, int maxCandidateCount) {
        return findWorkerCandidateBatch(selector, maxCandidateCount).candidates();
    }

    WorkerCandidateBatch findWorkerCandidateBatch(WorkerTaskSelector selector, int maxCandidateCount) {
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
            return new WorkerCandidateBatch(targetCandidates, 0, targetCandidates.size(), 0);
        }
        WarmCandidateSelection warmSelection = warmCandidatesFor(selector, candidateIndex, limit);
        List<Worker> warmCandidates = warmSelection.candidates();
        List<Worker> coldCandidates = candidateIndex.workersFor(selector, limit);
        if (warmCandidates.isEmpty()) {
            return new WorkerCandidateBatch(coldCandidates, 0, coldCandidates.size(),
                    warmSelection.sourceGuardRejectedCount());
        }
        LinkedHashMap<String, Worker> deduped = new LinkedHashMap<>();
        for (Worker worker : warmCandidates) {
            if (worker != null && worker.getWorkerId() != null) {
                deduped.put(worker.getWorkerId(), worker);
            }
            if (deduped.size() >= limit) {
                return new WorkerCandidateBatch(List.copyOf(deduped.values()), warmCandidates.size(),
                        coldCandidates.size(), warmSelection.sourceGuardRejectedCount());
            }
        }
        for (Worker worker : coldCandidates) {
            if (worker != null && worker.getWorkerId() != null) {
                deduped.putIfAbsent(worker.getWorkerId(), worker);
            }
            if (deduped.size() >= limit) {
                break;
            }
        }
        return new WorkerCandidateBatch(List.copyOf(deduped.values()), warmCandidates.size(),
                coldCandidates.size(), warmSelection.sourceGuardRejectedCount());
    }

    void recordWarmCandidate(WorkerTaskSelector selector, Worker worker) {
        String taskId = selector == null ? null : normalizeNullable(selector.taskId());
        String workerId = worker == null ? null : normalizeNullable(worker.getWorkerId());
        String groupId = worker == null ? null : normalizeNullable(worker.getWorkerGroupId());
        if (taskId == null || workerId == null || groupId == null) {
            return;
        }
        if (selector.targetsWorker()) {
            return;
        }
        long nowMillis = System.currentTimeMillis();
        for (String routeBucketKey : routeBucketKeysForTask(selector)) {
            taskCandidateWarmPool.put(new TaskCandidateWarmPool.Entry(
                    taskId,
                    workerId,
                    groupId,
                    normalizeNullable(worker.getAdapterNodeId()),
                    routeBucketKey,
                    nowMillis
            ));
        }
    }

    int warmCandidateCount(String taskId) {
        return taskCandidateWarmPool.sizeForTask(taskId);
    }

    private WarmCandidateSelection warmCandidatesFor(WorkerTaskSelector selector,
                                                     WorkerCandidateIndex candidateIndex,
                                                     int limit) {
        String taskId = selector == null ? null : normalizeNullable(selector.taskId());
        if (taskId == null || limit <= 0) {
            return WarmCandidateSelection.empty();
        }
        List<Worker> workers = new ArrayList<>();
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
                    entry.observedRouteBucketKey(),
                    entry.workerId()
            );
            if (guardResult.accepted()) {
                guardResult.worker().ifPresent(workers::add);
            } else {
                rejected++;
                taskCandidateWarmPool.remove(entry);
            }
        }
        return new WarmCandidateSelection(List.copyOf(workers), rejected);
    }

    private Set<String> routeBucketKeysForTask(WorkerTaskSelector selector) {
        return selector == null ? Set.of(WorkerRoutingPolicy.DEFAULT_ROUTE_BUCKET_KEY) : selector.routeBucketKeys();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record WarmCandidateSelection(List<Worker> candidates, int sourceGuardRejectedCount) {
        private WarmCandidateSelection {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            sourceGuardRejectedCount = Math.max(0, sourceGuardRejectedCount);
        }

        static WarmCandidateSelection empty() {
            return new WarmCandidateSelection(List.of(), 0);
        }
    }
}
