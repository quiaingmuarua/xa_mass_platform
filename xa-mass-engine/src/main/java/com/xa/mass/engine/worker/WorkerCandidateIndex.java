package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.runtime.worker.WorkerSlot;
import com.xa.mass.runtime.worker.WorkerTaskSelector;
import com.xa.mass.worker.runtime.WorkerRegistrySnapshot;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Stage-1 worker candidate source index backed by WorkerRegistry when available.
 *
 * <p>This narrows worker rows by declared WorkerGroup capability only. It does
 * not evaluate rules, ranking, reachability, load, reservation, or worker-lock
 * policy.</p>
 */
public final class WorkerCandidateIndex {

    private final WorkerRegistrySnapshot snapshot;
    private final WorkerRegistry workerRegistry;

    public WorkerCandidateIndex(WorkerRegistrySnapshot snapshot, WorkerRegistry workerRegistry) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.workerRegistry = Objects.requireNonNull(workerRegistry, "workerRegistry");
    }

    public List<Worker> workersFor(Task task) {
        return workersFor(WorkerTaskSelectorFactory.fromTask(task), Integer.MAX_VALUE);
    }

    public List<Worker> workersFor(Task task, int maxCandidateCount) {
        return workersFor(WorkerTaskSelectorFactory.fromTask(task), maxCandidateCount);
    }

    public List<Worker> workersFor(WorkerTaskSelector selector) {
        return workersFor(selector, Integer.MAX_VALUE);
    }

    public List<Worker> workersFor(WorkerTaskSelector selector, int maxCandidateCount) {
        List<String> groupIds = selector == null ? List.of() : selector.workerGroupIds();
        if (groupIds.isEmpty()) {
            return List.of();
        }

        String targetWorkerId = selector.targetWorkerId();
        if (targetWorkerId != null && !targetWorkerId.isBlank()) {
            return workerForWorkerId(selector, groupIds, targetWorkerId).map(List::of).orElseGet(List::of);
        }

        return workersForGroups(selector, groupIds, maxCandidateCount);
    }

    public List<Worker> workersForGroups(Task task, List<String> groupIds, int maxCandidateCount) {
        return workersForGroups(WorkerTaskSelectorFactory.fromTask(task), groupIds, maxCandidateCount);
    }

    public List<Worker> workersForGroups(WorkerTaskSelector selector, List<String> groupIds, int maxCandidateCount) {
        if (groupIds == null || groupIds.isEmpty() || maxCandidateCount <= 0) {
            return List.of();
        }
        String adapterNodeId = selector == null ? null : selector.adapterNodeId();
        List<Worker> workers = new ArrayList<>();
        for (CandidateSourceBucket sourceBucket : candidateSourceBuckets(selector, groupIds)) {
            int remaining = remaining(maxCandidateCount, workers.size());
            if (remaining <= 0) {
                break;
            }
            int sourceBudget = sourceBudget(remaining, sourceBucket.remainingSourceCount());
            for (CandidateSource source : acquireWorkerIds(sourceBucket, adapterNodeId, sourceBudget)) {
                SourceGuardResult guardResult = sourceGuard(
                        selector,
                        sourceBucket.groupId(),
                        adapterNodeId,
                        source.routeBucketKey(),
                        source.workerId()
                );
                if (!guardResult.accepted()) {
                    continue;
                }
                guardResult.worker().ifPresent(workers::add);
            }
        }
        return List.copyOf(workers);
    }

    public Optional<Worker> workerForWorkerId(Task task, String workerId) {
        WorkerTaskSelector selector = WorkerTaskSelectorFactory.fromTask(task);
        return workerForWorkerId(selector, selector.workerGroupIds(), workerId);
    }

    private Optional<Worker> workerForWorkerId(WorkerTaskSelector selector, List<String> selectedGroupIds, String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null || selectedGroupIds == null || selectedGroupIds.isEmpty()) {
            return Optional.empty();
        }

        String adapterNodeId = selector == null ? null : selector.adapterNodeId();
        for (String selectedGroupId : selectedGroupIds) {
            String normalizedGroupId = normalizeNullable(selectedGroupId);
            if (normalizedGroupId == null) {
                continue;
            }
            for (String routeBucketKey : taskRouteBucketKeys(selector)) {
                SourceGuardResult guardResult = sourceGuard(
                        selector,
                        normalizedGroupId,
                        adapterNodeId,
                        routeBucketKey,
                        normalizedWorkerId
                );
                if (guardResult.accepted()) {
                    return guardResult.worker();
                }
            }
        }

        return Optional.empty();
    }

    public SourceGuardResult sourceGuard(Task task,
                                         String selectedGroupId,
                                         String observedAdapterNodeId,
                                         String observedRouteBucketKey,
                                         String workerId) {
        return sourceGuard(WorkerTaskSelectorFactory.fromTask(task), selectedGroupId, observedAdapterNodeId,
                observedRouteBucketKey, workerId);
    }

    public SourceGuardResult sourceGuard(WorkerTaskSelector selector,
                                         String selectedGroupId,
                                         String observedAdapterNodeId,
                                         String observedRouteBucketKey,
                                         String workerId) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return SourceGuardResult.rejected(SourceGuardRejectionReason.MISSING_WORKER);
        }
        Optional<WorkerSlot> slot = workerRegistry.slotByWorkerId(normalizedWorkerId);
        if (slot.isEmpty()) {
            return SourceGuardResult.rejected(SourceGuardRejectionReason.MISSING_SLOT);
        }
        WorkerSlot currentSlot = slot.orElseThrow();
        String normalizedGroupId = normalizeNullable(selectedGroupId);
        if (normalizedGroupId == null || !normalizedGroupId.equals(currentSlot.groupId())) {
            return SourceGuardResult.rejected(SourceGuardRejectionReason.GROUP_MISMATCH);
        }
        if (snapshot.group(currentSlot.groupId()).isEmpty()) {
            return SourceGuardResult.rejected(SourceGuardRejectionReason.MISSING_GROUP);
        }
        String normalizedAdapterNodeId = normalizeNullable(observedAdapterNodeId);
        if (normalizedAdapterNodeId != null && !normalizedAdapterNodeId.equals(currentSlot.adapterNodeId())) {
            return SourceGuardResult.rejected(SourceGuardRejectionReason.ADAPTER_NODE_MISMATCH);
        }
        String routeBucketKey = normalizeNullable(observedRouteBucketKey);
        if (routeBucketKey == null) {
            return SourceGuardResult.rejected(SourceGuardRejectionReason.ROUTE_MISMATCH);
        }
        Set<String> currentWorkerRouteKeys = WorkerRoutingPolicy.defaultPolicy()
                .routeBucketKeysForWorkerMeta(currentSlot.meta());
        if (!currentWorkerRouteKeys.contains(routeBucketKey)) {
            return SourceGuardResult.rejected(SourceGuardRejectionReason.ROUTE_MISMATCH);
        }
        Optional<Worker> worker = snapshot.worker(normalizedWorkerId);
        if (worker.isEmpty()) {
            return SourceGuardResult.rejected(SourceGuardRejectionReason.MISSING_WORKER);
        }
        return SourceGuardResult.accepted(worker.orElseThrow());
    }

    private List<CandidateSource> acquireWorkerIds(CandidateSourceBucket sourceBucket,
                                                   String adapterNodeId,
                                                   int maxCandidateCount) {
        LinkedHashSet<CandidateSource> acquired = new LinkedHashSet<>();
        if (sourceBucket == null || maxCandidateCount <= 0) {
            return List.of();
        }
        for (String workerId : workerRegistry.acquireCandidates(
                sourceBucket.groupId(),
                adapterNodeId,
                sourceBucket.routeBucketKey(),
                maxCandidateCount
        )) {
            acquired.add(new CandidateSource(workerId, sourceBucket.routeBucketKey()));
        }
        return List.copyOf(acquired);
    }

    private List<CandidateSourceBucket> candidateSourceBuckets(WorkerTaskSelector selector, List<String> groupIds) {
        List<String> normalizedGroupIds = groupIds.stream()
                .map(WorkerCandidateIndex::normalizeNullable)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (normalizedGroupIds.isEmpty()) {
            return List.of();
        }
        List<String> routeBucketKeys = taskRouteBucketKeys(selector).stream().toList();
        List<CandidateSourceBucket> buckets = new ArrayList<>(normalizedGroupIds.size() * routeBucketKeys.size());
        for (String groupId : normalizedGroupIds) {
            for (String routeBucketKey : routeBucketKeys) {
                buckets.add(new CandidateSourceBucket(groupId, routeBucketKey, 0));
            }
        }
        int size = buckets.size();
        List<CandidateSourceBucket> indexedBuckets = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            indexedBuckets.add(new CandidateSourceBucket(
                    buckets.get(index).groupId(),
                    buckets.get(index).routeBucketKey(),
                    size - index
            ));
        }
        return List.copyOf(indexedBuckets);
    }

    private Set<String> taskRouteBucketKeys(WorkerTaskSelector selector) {
        return selector == null ? Set.of(WorkerRoutingPolicy.DEFAULT_ROUTE_BUCKET_KEY) : selector.routeBucketKeys();
    }

    private static int sourceBudget(int remainingCandidateBudget, int remainingSourceCount) {
        if (remainingCandidateBudget == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        int sourceCount = Math.max(1, remainingSourceCount);
        return Math.max(1, (remainingCandidateBudget + sourceCount - 1) / sourceCount);
    }

    private static int remaining(int maxCandidateCount, int currentSize) {
        if (maxCandidateCount == Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, maxCandidateCount - currentSize);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record CandidateSource(String workerId, String routeBucketKey) {
    }

    private record CandidateSourceBucket(String groupId, String routeBucketKey, int remainingSourceCount) {
    }

    public record SourceGuardResult(boolean accepted,
                                    SourceGuardRejectionReason rejectionReason,
                                    Optional<Worker> worker) {
        private static SourceGuardResult accepted(Worker worker) {
            return new SourceGuardResult(true, null, Optional.of(worker));
        }

        private static SourceGuardResult rejected(SourceGuardRejectionReason reason) {
            return new SourceGuardResult(false, Objects.requireNonNull(reason, "reason"), Optional.empty());
        }
    }

    public enum SourceGuardRejectionReason {
        MISSING_SLOT,
        MISSING_WORKER,
        MISSING_GROUP,
        GROUP_MISMATCH,
        ADAPTER_NODE_MISMATCH,
        ROUTE_MISMATCH
    }
}
