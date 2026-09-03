package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.CandidateWorkerCache.CandidateWorkerEntry;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Kernel-owned Worker observation, hold and descriptor selection. */
final class WorkerCandidateSelectionPolicy {

    private static final int MAX_UNIQUE_WORKERS_PER_ROUND = 100;

    private final WorkerScoreCore workerScores;
    private final CandidateWorkerCache candidateCache;
    private final WorkerResourceCatalog workerCatalog;
    private final Long hotEligibilityFloorMillis;

    WorkerCandidateSelectionPolicy(
            WorkerScoreCore workerScores,
            CandidateWorkerCache candidateCache,
            WorkerResourceCatalog workerCatalog,
            Long hotEligibilityFloorMillis
    ) {
        this.workerScores = Objects.requireNonNull(
                workerScores,
                "workerScores"
        );
        this.candidateCache = Objects.requireNonNull(
                candidateCache,
                "candidateCache"
        );
        this.workerCatalog = Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
        this.hotEligibilityFloorMillis = hotEligibilityFloorMillis;
    }

    Map<String, Long> observeDueCandidates(
            String workerGroupId,
            int limit
    ) {
        if (limit < 1 || limit > MAX_UNIQUE_WORKERS_PER_ROUND) {
            throw new IllegalArgumentException(
                    "candidate limit must be in 1.."
                            + MAX_UNIQUE_WORKERS_PER_ROUND
            );
        }
        return workerScores.observeDueHotScoreCandidates(
                workerGroupId,
                hotEligibilityFloorMillis,
                limit
        );
    }

    Map<String, Long> holdObservedCandidates(
            String workerGroupId,
            Map<String, Long> observedScores,
            long holdUntilMillis
    ) {
        Objects.requireNonNull(observedScores, "observedScores");
        if (observedScores.isEmpty()) {
            return Map.of();
        }
        Map<String, WorkerScoreTransitionResult> transitions =
                workerScores.acquireObservedHotScoreLeases(
                        workerGroupId,
                        observedScores,
                        holdUntilMillis
                );
        LinkedHashMap<String, Long> held = new LinkedHashMap<>();
        observedScores.keySet().forEach(workerId -> {
            WorkerScoreTransitionResult result = transitions.get(workerId);
            if (result != null
                    && result.status()
                            == WorkerScoreTransitionStatus.TRANSITIONED
                    && result.score() != null) {
                held.put(workerId, result.score());
            }
        });
        return Collections.unmodifiableMap(held);
    }

    List<HeldWorkerCandidate> consumeCachedCandidates(
            String workerGroupId,
            String candidateId,
            int limit
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        requireNonBlank(candidateId, "candidateId");
        if (limit < 1 || limit > MAX_UNIQUE_WORKERS_PER_ROUND) {
            throw new IllegalArgumentException(
                    "candidate limit must be in 1.."
                            + MAX_UNIQUE_WORKERS_PER_ROUND
            );
        }
        List<CandidateWorkerEntry> cached =
                candidateCache.consumeCandidateWorkers(candidateId, limit);
        if (cached.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<String, Long> heldScores = new LinkedHashMap<>();
        for (CandidateWorkerEntry entry : cached) {
            if (entry != null) {
                heldScores.putIfAbsent(
                        entry.workerId(),
                        entry.heldWorkerLeaseScore()
                );
            }
        }
        return describe(
                workerGroupId,
                List.copyOf(heldScores.keySet()),
                heldScores
        );
    }

    Map<String, HeldWorkerCandidate> acquireOnDemandCandidates(
            String workerGroupId,
            Map<String, List<String>> targetWorkerIdsByMessageId,
            Set<String> excludedWorkerIds,
            long leaseUntilMillis
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        Map<String, List<String>> targets = validateTargets(
                targetWorkerIdsByMessageId
        );
        Objects.requireNonNull(excludedWorkerIds, "excludedWorkerIds");

        LinkedHashMap<String, String> selectedByMessageId =
                new LinkedHashMap<>();
        LinkedHashMap<String, Long> selectedScores = new LinkedHashMap<>();
        LinkedHashSet<String> unavailableWorkerIds = new LinkedHashSet<>(
                excludedWorkerIds
        );

        for (Map.Entry<String, List<String>> item : targets.entrySet()) {
            if (item.getValue().isEmpty()) {
                continue;
            }
            List<String> availableTargets = item.getValue().stream()
                    .filter(workerId ->
                            !unavailableWorkerIds.contains(workerId))
                    .toList();
            if (availableTargets.isEmpty()) {
                continue;
            }
            Map<String, Long> observed = workerScores.observeDueHotScores(
                    workerGroupId,
                    availableTargets,
                    hotEligibilityFloorMillis
            );
            for (String workerId : availableTargets) {
                Long score = observed.get(workerId);
                if (score != null && unavailableWorkerIds.add(workerId)) {
                    selectedByMessageId.put(item.getKey(), workerId);
                    selectedScores.put(workerId, score);
                    break;
                }
            }
        }

        LinkedHashMap<String, Long> held = new LinkedHashMap<>(
                holdObservedCandidates(
                        workerGroupId,
                        selectedScores,
                        leaseUntilMillis
                )
        );
        selectedByMessageId.entrySet().removeIf(entry ->
                !held.containsKey(entry.getValue()));
        unavailableWorkerIds.addAll(held.keySet());

        List<String> anyMessageIds = targets.entrySet().stream()
                .filter(entry -> entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .toList();
        if (!anyMessageIds.isEmpty()) {
            int limit = Math.min(
                    MAX_UNIQUE_WORKERS_PER_ROUND,
                    anyMessageIds.size() + Math.min(
                            unavailableWorkerIds.size(),
                            MAX_UNIQUE_WORKERS_PER_ROUND
                    )
            );
            Map<String, Long> observed = observeDueCandidates(
                    workerGroupId,
                    limit
            );
            LinkedHashMap<String, Long> anyScores = new LinkedHashMap<>();
            for (Map.Entry<String, Long> entry : observed.entrySet()) {
                if (!unavailableWorkerIds.contains(entry.getKey())) {
                    anyScores.put(entry.getKey(), entry.getValue());
                    if (anyScores.size() == anyMessageIds.size()) {
                        break;
                    }
                }
            }
            Map<String, Long> anyHeld = holdObservedCandidates(
                    workerGroupId,
                    anyScores,
                    leaseUntilMillis
            );
            int messageIndex = 0;
            for (String workerId : anyScores.keySet()) {
                if (anyHeld.containsKey(workerId)) {
                    selectedByMessageId.put(
                            anyMessageIds.get(messageIndex++),
                            workerId
                    );
                    held.put(workerId, anyHeld.get(workerId));
                }
            }
        }

        if (selectedByMessageId.isEmpty()) {
            return Map.of();
        }
        Map<String, HeldWorkerCandidate> described = describeById(
                workerGroupId,
                held
        );
        LinkedHashMap<String, HeldWorkerCandidate> result =
                new LinkedHashMap<>();
        selectedByMessageId.forEach((messageId, workerId) -> {
            HeldWorkerCandidate candidate = described.get(workerId);
            if (candidate != null) {
                result.put(messageId, candidate);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private List<HeldWorkerCandidate> describe(
            String workerGroupId,
            List<String> workerIds,
            Map<String, Long> heldScores
    ) {
        Map<String, HeldWorkerCandidate> described = describeById(
                workerGroupId,
                heldScores
        );
        List<HeldWorkerCandidate> result = new ArrayList<>();
        workerIds.forEach(workerId -> {
            HeldWorkerCandidate candidate = described.get(workerId);
            if (candidate != null) {
                result.add(candidate);
            }
        });
        return List.copyOf(result);
    }

    private Map<String, HeldWorkerCandidate> describeById(
            String workerGroupId,
            Map<String, Long> heldScores
    ) {
        if (heldScores.isEmpty()) {
            return Map.of();
        }
        Map<String, WorkerDescriptor> descriptors =
                workerCatalog.getWorkerDescriptors(
                        workerGroupId,
                        List.copyOf(heldScores.keySet())
                );
        LinkedHashMap<String, HeldWorkerCandidate> result =
                new LinkedHashMap<>();
        heldScores.forEach((workerId, heldScore) -> {
            WorkerDescriptor descriptor = descriptors.get(workerId);
            if (descriptor != null
                    && workerGroupId.equals(descriptor.workerGroupId())) {
                result.put(workerId, new HeldWorkerCandidate(
                        descriptor.workerId(),
                        descriptor.workerGroupId(),
                        descriptor.endpointManagerId(),
                        heldScore
                ));
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, List<String>> validateTargets(
            Map<String, List<String>> source
    ) {
        Objects.requireNonNull(source, "targetWorkerIdsByMessageId");
        if (source.isEmpty()
                || source.size() > MAX_UNIQUE_WORKERS_PER_ROUND) {
            throw new IllegalArgumentException(
                    "Item targets must contain 1.."
                            + MAX_UNIQUE_WORKERS_PER_ROUND + " entries"
            );
        }
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((messageId, workerIds) -> {
            requireNonBlank(messageId, "messageId");
            Objects.requireNonNull(workerIds, "targetWorkerIds");
            if (workerIds.size() > MAX_UNIQUE_WORKERS_PER_ROUND) {
                throw new IllegalArgumentException(
                        "targetWorkerIds must contain at most "
                                + MAX_UNIQUE_WORKERS_PER_ROUND + " workers"
                );
            }
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (String workerId : workerIds) {
                requireNonBlank(workerId, "target workerId");
                if (!unique.add(workerId)) {
                    throw new IllegalArgumentException(
                            "targetWorkerIds must not contain duplicates"
                    );
                }
            }
            result.put(messageId, List.copyOf(unique));
        });
        return Collections.unmodifiableMap(result);
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }
}
