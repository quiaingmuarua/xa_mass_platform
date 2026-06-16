package com.xa.mass.worker.runtime;

import com.xa.mass.base.model.Worker;
import com.xa.mass.runtime.worker.ReserveStatus;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.runtime.worker.WorkerCandidateBucketPolicy;
import com.xa.mass.runtime.worker.WorkerMeta;
import com.xa.mass.worker.runtime.routing.WorkerCandidateBucketPolicies;
import com.xa.mass.worker.runtime.candidate.WorkerTaskSelector;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

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
    private final WorkerCandidateBucketPolicy candidateBucketPolicy;
    private final LongSupplier nowMillisSupplier;

    public WorkerCandidateIndex(WorkerRegistrySnapshot snapshot, WorkerRegistry workerRegistry) {
        this(snapshot, workerRegistry, WorkerCandidateBucketPolicies.defaultPolicy());
    }

    public WorkerCandidateIndex(WorkerRegistrySnapshot snapshot,
                                WorkerRegistry workerRegistry,
                                WorkerCandidateBucketPolicy candidateBucketPolicy) {
        this(snapshot, workerRegistry, candidateBucketPolicy, System::currentTimeMillis);
    }

    WorkerCandidateIndex(WorkerRegistrySnapshot snapshot,
                         WorkerRegistry workerRegistry,
                         WorkerCandidateBucketPolicy candidateBucketPolicy,
                         LongSupplier nowMillisSupplier) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.workerRegistry = Objects.requireNonNull(workerRegistry, "workerRegistry");
        this.candidateBucketPolicy = candidateBucketPolicy != null
                ? candidateBucketPolicy
                : WorkerCandidateBucketPolicies.defaultPolicy();
        this.nowMillisSupplier = nowMillisSupplier != null ? nowMillisSupplier : System::currentTimeMillis;
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
            return workerForWorkerId(selector, groupIds, targetWorkerId, nowMillisSupplier.getAsLong())
                    .map(List::of)
                    .orElseGet(List::of);
        }

        return workersForGroups(selector, groupIds, maxCandidateCount);
    }

    public List<Worker> workersForGroups(WorkerTaskSelector selector, List<String> groupIds, int maxCandidateCount) {
        if (groupIds == null || groupIds.isEmpty() || maxCandidateCount <= 0) {
            return List.of();
        }
        List<Worker> workers = new ArrayList<>();
        long nowMillis = nowMillisSupplier.getAsLong();
        for (CandidateSourceBucket sourceBucket : candidateSourceBuckets(selector, groupIds)) {
            int remaining = remaining(maxCandidateCount, workers.size());
            if (remaining <= 0) {
                break;
            }
            int sourceBudget = sourceBudget(remaining, sourceBucket.remainingSourceCount());
            for (CandidateSource source : acquireWorkerIds(sourceBucket, sourceBudget, nowMillis)) {
                SourceGuardResult guardResult = sourceGuard(
                        selector,
                        sourceBucket.groupId(),
                        source.candidateBucketKey(),
                        source.workerId(),
                        nowMillis
                );
                if (!guardResult.accepted()) {
                    continue;
                }
                guardResult.worker().ifPresent(workers::add);
            }
        }
        return List.copyOf(workers);
    }

    private Optional<Worker> workerForWorkerId(WorkerTaskSelector selector,
                                               List<String> selectedGroupIds,
                                               String workerId,
                                               long nowMillis) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null || selectedGroupIds == null || selectedGroupIds.isEmpty()) {
            return Optional.empty();
        }

        for (String selectedGroupId : selectedGroupIds) {
            String normalizedGroupId = normalizeNullable(selectedGroupId);
            if (normalizedGroupId == null) {
                continue;
            }
            for (String candidateBucketKey : taskCandidateBucketKeys(selector)) {
                SourceGuardResult guardResult = sourceGuard(
                        selector,
                        normalizedGroupId,
                        candidateBucketKey,
                        normalizedWorkerId,
                        nowMillis
                );
                if (guardResult.accepted()) {
                    return guardResult.worker();
                }
            }
        }

        return Optional.empty();
    }

    public SourceGuardResult sourceGuard(WorkerTaskSelector selector,
                                         String selectedGroupId,
                                         String observedCandidateBucketKey,
                                         String workerId) {
        return sourceGuard(
                selector,
                selectedGroupId,
                observedCandidateBucketKey,
                workerId,
                nowMillisSupplier.getAsLong()
        );
    }

    private SourceGuardResult sourceGuard(WorkerTaskSelector selector,
                                          String selectedGroupId,
                                          String observedCandidateBucketKey,
                                          String workerId,
                                          long nowMillis) {
        String normalizedWorkerId = normalizeNullable(workerId);
        if (normalizedWorkerId == null) {
            return SourceGuardResult.rejected(SourceGuardRejectionReason.MISSING_WORKER);
        }
        Optional<WorkerMeta> meta = workerRegistry.workerMeta(normalizedWorkerId);
        if (meta.isEmpty()) {
            return SourceGuardResult.rejected(SourceGuardRejectionReason.MISSING_SLOT);
        }
        WorkerMeta currentMeta = meta.orElseThrow();
        String normalizedGroupId = normalizeNullable(selectedGroupId);
        if (normalizedGroupId == null || !normalizedGroupId.equals(currentMeta.groupId())) {
            return SourceGuardResult.rejected(SourceGuardRejectionReason.GROUP_MISMATCH);
        }
        if (snapshot.group(currentMeta.groupId()).isEmpty()) {
            return SourceGuardResult.rejected(SourceGuardRejectionReason.MISSING_GROUP);
        }
        String candidateBucketKey = normalizeNullable(observedCandidateBucketKey);
        if (candidateBucketKey == null) {
            return SourceGuardResult.rejected(SourceGuardRejectionReason.CANDIDATE_BUCKET_MISMATCH);
        }
        Set<String> currentWorkerCandidateBucketKeys = candidateBucketPolicy.candidateBucketKeysForWorkerMeta(currentMeta);
        if (!currentWorkerCandidateBucketKeys.contains(candidateBucketKey)) {
            return SourceGuardResult.rejected(SourceGuardRejectionReason.CANDIDATE_BUCKET_MISMATCH);
        }
        ReserveStatus lifecycleStatus = workerRegistry.slotLifecycleStatus(
                normalizedGroupId,
                normalizedWorkerId,
                nowMillis
        );
        if (lifecycleStatus != ReserveStatus.ACCEPTED) {
            return SourceGuardResult.rejected(sourceGuardRejection(lifecycleStatus));
        }
        Optional<Worker> worker = snapshot.worker(normalizedWorkerId);
        if (worker.isEmpty()) {
            return SourceGuardResult.rejected(SourceGuardRejectionReason.MISSING_WORKER);
        }
        return SourceGuardResult.accepted(worker.orElseThrow());
    }

    private List<CandidateSource> acquireWorkerIds(CandidateSourceBucket sourceBucket,
                                                   int maxCandidateCount,
                                                   long nowMillis) {
        LinkedHashSet<CandidateSource> acquired = new LinkedHashSet<>();
        if (sourceBucket == null || maxCandidateCount <= 0) {
            return List.of();
        }
        for (String workerId : workerRegistry.acquireCandidates(
                sourceBucket.groupId(),
                sourceBucket.candidateBucketKey(),
                maxCandidateCount,
                nowMillis
        )) {
            acquired.add(new CandidateSource(workerId, sourceBucket.candidateBucketKey()));
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
        List<String> candidateBucketKeys = taskCandidateBucketKeys(selector).stream().toList();
        List<CandidateSourceBucket> buckets = new ArrayList<>(normalizedGroupIds.size() * candidateBucketKeys.size());
        for (String groupId : normalizedGroupIds) {
            for (String candidateBucketKey : candidateBucketKeys) {
                buckets.add(new CandidateSourceBucket(groupId, candidateBucketKey, 0));
            }
        }
        int size = buckets.size();
        List<CandidateSourceBucket> indexedBuckets = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            indexedBuckets.add(new CandidateSourceBucket(
                    buckets.get(index).groupId(),
                    buckets.get(index).candidateBucketKey(),
                    size - index
            ));
        }
        return List.copyOf(indexedBuckets);
    }

    private Set<String> taskCandidateBucketKeys(WorkerTaskSelector selector) {
        return selector == null ? Set.of(WorkerCandidateBucketPolicy.DEFAULT_CANDIDATE_BUCKET_KEY) : selector.candidateBucketKeys();
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

    private static SourceGuardRejectionReason sourceGuardRejection(ReserveStatus status) {
        if (status == null) {
            return SourceGuardRejectionReason.MISSING_SLOT;
        }
        return switch (status) {
            case MISSING_SLOT -> SourceGuardRejectionReason.MISSING_SLOT;
            case GROUP_MISMATCH -> SourceGuardRejectionReason.GROUP_MISMATCH;
            case REMOVING_SLOT -> SourceGuardRejectionReason.REMOVING_SLOT;
            case STALE_HEARTBEAT -> SourceGuardRejectionReason.STALE_HEARTBEAT;
            case DISPATCH_DISABLED -> SourceGuardRejectionReason.DISPATCH_DISABLED;
            case CAPACITY_UNAVAILABLE, ACCEPTED -> SourceGuardRejectionReason.MISSING_SLOT;
        };
    }

    private record CandidateSource(String workerId, String candidateBucketKey) {
    }

    private record CandidateSourceBucket(String groupId, String candidateBucketKey, int remainingSourceCount) {
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
        CANDIDATE_BUCKET_MISMATCH,
        REMOVING_SLOT,
        STALE_HEARTBEAT,
        DISPATCH_DISABLED
    }
}
