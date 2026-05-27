package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Worker;
import com.xa.mass.runtime.worker.WorkerCandidateBatch;
import com.xa.mass.runtime.worker.WorkerCandidateRow;
import com.xa.mass.runtime.worker.WorkerTaskSelector;
import com.xa.mass.worker.runtime.TaskCandidateWarmPool;

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

    WorkerCandidateBatch<WorkerCandidateRow> findWorkerCandidateBatch(WorkerTaskSelector selector,
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
            return new WorkerCandidateBatch<>(toCandidateRows(targetCandidates), 0, targetCandidates.size(), 0);
        }
        WarmCandidateSelection warmSelection = warmCandidatesFor(selector, candidateIndex, limit);
        List<WorkerCandidateRow> warmCandidates = warmSelection.candidates();
        List<Worker> coldCandidates = candidateIndex.workersFor(selector, limit);
        if (warmCandidates.isEmpty()) {
            return new WorkerCandidateBatch<>(toCandidateRows(coldCandidates), 0, coldCandidates.size(),
                    warmSelection.sourceGuardRejectedCount());
        }
        LinkedHashMap<String, WorkerCandidateRow> deduped = new LinkedHashMap<>();
        for (WorkerCandidateRow candidate : warmCandidates) {
            if (candidate != null && candidate.workerId() != null) {
                deduped.put(candidate.workerId(), candidate);
            }
            if (deduped.size() >= limit) {
                return new WorkerCandidateBatch<>(List.copyOf(deduped.values()), warmCandidates.size(),
                        coldCandidates.size(), warmSelection.sourceGuardRejectedCount());
            }
        }
        for (Worker worker : coldCandidates) {
            if (worker != null && worker.getWorkerId() != null) {
                deduped.putIfAbsent(worker.getWorkerId(), toCandidateRow(worker));
            }
            if (deduped.size() >= limit) {
                break;
            }
        }
        return new WorkerCandidateBatch<>(List.copyOf(deduped.values()), warmCandidates.size(),
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

    void recordWarmCandidate(WorkerTaskSelector selector, WorkerCandidateRow candidate) {
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
        for (String routeBucketKey : routeBucketKeysForTask(selector)) {
            taskCandidateWarmPool.put(new TaskCandidateWarmPool.Entry(
                    taskId,
                    workerId,
                    groupId,
                    normalizeNullable(candidate.adapterNodeId()),
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
                    entry.observedRouteBucketKey(),
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

    private static List<WorkerCandidateRow> toCandidateRows(List<Worker> workers) {
        if (workers == null || workers.isEmpty()) {
            return List.of();
        }
        List<WorkerCandidateRow> rows = new ArrayList<>(workers.size());
        for (Worker worker : workers) {
            rows.add(toCandidateRow(worker));
        }
        return List.copyOf(rows);
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

    private Set<String> routeBucketKeysForTask(WorkerTaskSelector selector) {
        return selector == null ? Set.of(WorkerRoutingPolicy.DEFAULT_ROUTE_BUCKET_KEY) : selector.routeBucketKeys();
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
}
