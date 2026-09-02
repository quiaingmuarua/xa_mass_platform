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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Kernel selection over identity-only matching evidence. */
final class WorkerCandidateSelectionPolicy {

    static final int MAX_UNIQUE_WORKERS_PER_ROUND = 100;

    private final WorkerScoreCore workerScores;
    private final CandidateWorkerCache candidateCache;
    private final WorkerResourceCatalog workerCatalog;
    private final int workerScanLimit;
    private final Long hotEligibilityFloorMillis;

    WorkerCandidateSelectionPolicy(
            WorkerScoreCore workerScores,
            CandidateWorkerCache candidateCache,
            WorkerResourceCatalog workerCatalog,
            int workerScanLimit,
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
        if (workerScanLimit < 1
                || workerScanLimit > MAX_UNIQUE_WORKERS_PER_ROUND) {
            throw new IllegalArgumentException(
                    "workerScanLimit must be in 1.."
                            + MAX_UNIQUE_WORKERS_PER_ROUND
            );
        }
        this.workerScanLimit = workerScanLimit;
        this.hotEligibilityFloorMillis = hotEligibilityFloorMillis;
    }

    Map<String, Long> observeDueCandidates(String workerGroupId) {
        return workerScores.observeDueHotScoreCandidates(
                workerGroupId,
                hotEligibilityFloorMillis,
                workerScanLimit
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

    Map<String, List<AcquiredWorkerCandidate>> renewCachedCandidates(
            String workerGroupId,
            Map<String, WorkerCandidateRequest> requests,
            long leaseUntilMillis
    ) {
        Map<String, WorkerCandidateRequest> validated = validate(requests);
        LinkedHashMap<String, List<String>> candidateIds =
                new LinkedHashMap<>();
        LinkedHashMap<String, Long> observed = new LinkedHashMap<>();
        for (Map.Entry<String, WorkerCandidateRequest> request
                : ordered(validated)) {
            List<CandidateWorkerEntry> cached =
                    candidateCache.consumeCandidateWorkers(
                            request.getKey(),
                            request.getValue().requestedCount()
                    );
            List<String> ids = new ArrayList<>();
            for (CandidateWorkerEntry entry : cached) {
                if (workerGroupId.equals(entry.workerGroupId())) {
                    ids.add(entry.workerId());
                    observed.putIfAbsent(
                            entry.workerId(),
                            entry.workerLeaseScore()
                    );
                }
            }
            candidateIds.put(request.getKey(), List.copyOf(ids));
        }
        return leaseSelected(
                workerGroupId,
                validated,
                candidateIds,
                observed,
                leaseUntilMillis,
                MAX_UNIQUE_WORKERS_PER_ROUND
        );
    }

    Map<String, List<AcquiredWorkerCandidate>> selectHeldCandidates(
            String workerGroupId,
            Map<String, WorkerCandidateRequest> requests,
            Map<String, List<String>> matchedWorkerIds,
            Map<String, Long> holdUntilByCandidateId,
            int maximumUniqueWorkers
    ) {
        Map<String, WorkerCandidateRequest> validated = validate(requests);
        Objects.requireNonNull(matchedWorkerIds, "matchedWorkerIds");
        Objects.requireNonNull(
                holdUntilByCandidateId,
                "holdUntilByCandidateId"
        );
        LinkedHashMap<Long, LinkedHashSet<String>> idsByHoldUntil =
                new LinkedHashMap<>();
        validated.keySet().forEach(candidateId -> {
            Long holdUntil = holdUntilByCandidateId.get(candidateId);
            if (holdUntil == null) {
                return;
            }
            idsByHoldUntil.computeIfAbsent(
                    holdUntil,
                    ignored -> new LinkedHashSet<>()
            ).addAll(matchedWorkerIds.getOrDefault(candidateId, List.of()));
        });
        LinkedHashMap<String, Long> active = new LinkedHashMap<>();
        idsByHoldUntil.forEach((holdUntil, workerIds) -> {
            if (!workerIds.isEmpty()) {
                workerScores.observeActiveHotScoreLeases(
                        workerGroupId,
                        List.copyOf(workerIds),
                        holdUntil
                ).forEach(active::putIfAbsent);
            }
        });
        LinkedHashMap<String, List<String>> activeMatches =
                new LinkedHashMap<>();
        validated.keySet().forEach(candidateId -> activeMatches.put(
                candidateId,
                matchedWorkerIds.getOrDefault(candidateId, List.of()).stream()
                        .filter(active::containsKey)
                        .toList()
        ));
        Map<String, List<String>> selected = selectUniqueWorkerIds(
                activeMatches,
                validated,
                maximumUniqueWorkers
        );
        return describeSelected(
                workerGroupId,
                validated,
                selected,
                active
        );
    }

    private Map<String, List<AcquiredWorkerCandidate>> leaseSelected(
            String workerGroupId,
            Map<String, WorkerCandidateRequest> requests,
            Map<String, List<String>> candidateIds,
            Map<String, Long> observed,
            long leaseUntilMillis,
            int maximumUniqueWorkers
    ) {
        Map<String, List<String>> selected = selectUniqueWorkerIds(
                candidateIds,
                requests,
                maximumUniqueWorkers
        );
        LinkedHashMap<String, Long> selectedScores = new LinkedHashMap<>();
        for (Map.Entry<String, WorkerCandidateRequest> request
                : ordered(requests)) {
            for (String workerId : selected.getOrDefault(
                    request.getKey(),
                    List.of()
            )) {
                Long score = observed.get(workerId);
                if (score != null) {
                    selectedScores.putIfAbsent(workerId, score);
                }
            }
        }
        if (selectedScores.isEmpty()) {
            return empty(requests);
        }
        Map<String, WorkerScoreTransitionResult> transitions =
                workerScores.renewActiveHotScoreLeases(
                        workerGroupId,
                        selectedScores,
                        leaseUntilMillis
                );
        LinkedHashMap<String, Long> leased = new LinkedHashMap<>();
        selectedScores.forEach((workerId, ignored) -> {
            WorkerScoreTransitionResult result = transitions.get(workerId);
            if (result != null
                    && result.score() != null
                    && (result.status()
                            == WorkerScoreTransitionStatus.TRANSITIONED
                    || result.status()
                            == WorkerScoreTransitionStatus.NOOP)) {
                leased.put(workerId, result.score());
            }
        });
        if (leased.isEmpty()) {
            return empty(requests);
        }
        return describeSelected(workerGroupId, requests, selected, leased);
    }

    private Map<String, List<AcquiredWorkerCandidate>> describeSelected(
            String workerGroupId,
            Map<String, WorkerCandidateRequest> requests,
            Map<String, List<String>> selected,
            Map<String, Long> leased
    ) {
        if (leased.isEmpty()) {
            return empty(requests);
        }
        Map<String, WorkerDescriptor> descriptors =
                workerCatalog.getWorkerDescriptors(
                        workerGroupId,
                        List.copyOf(leased.keySet())
                );
        LinkedHashMap<String, List<AcquiredWorkerCandidate>> result =
                new LinkedHashMap<>();
        requests.keySet().forEach(candidateId -> {
            List<AcquiredWorkerCandidate> candidates = selected.getOrDefault(
                    candidateId,
                    List.of()
            ).stream().filter(leased::containsKey).map(descriptors::get)
                    .filter(Objects::nonNull)
                    .map(descriptor -> new AcquiredWorkerCandidate(
                            descriptor.workerId(),
                            descriptor.workerGroupId(),
                            descriptor.endpointManagerId(),
                            Objects.requireNonNull(
                                    leased.get(descriptor.workerId()),
                                    "workerLeaseScore"
                            )
                    )).toList();
            result.put(candidateId, candidates);
        });
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, List<String>> selectUniqueWorkerIds(
            Map<String, List<String>> candidates,
            Map<String, WorkerCandidateRequest> requests,
            int maximumUniqueWorkers
    ) {
        Objects.requireNonNull(candidates, "matchedWorkerIds");
        if (maximumUniqueWorkers < 1
                || maximumUniqueWorkers > MAX_UNIQUE_WORKERS_PER_ROUND) {
            throw new IllegalArgumentException(
                    "maximumUniqueWorkers must be in 1.."
                            + MAX_UNIQUE_WORKERS_PER_ROUND
            );
        }
        LinkedHashSet<String> used = new LinkedHashSet<>();
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        requests.keySet().forEach(candidateId -> result.put(
                candidateId,
                List.of()
        ));
        for (Map.Entry<String, WorkerCandidateRequest> request
                : ordered(requests)) {
            List<String> selected = new ArrayList<>();
            for (String workerId : candidates.getOrDefault(
                    request.getKey(),
                    List.of()
            )) {
                if (workerId == null || workerId.isBlank()
                        || used.contains(workerId)) {
                    continue;
                }
                if (used.size() >= maximumUniqueWorkers) {
                    break;
                }
                used.add(workerId);
                selected.add(workerId);
                if (selected.size()
                        >= request.getValue().requestedCount()) {
                    break;
                }
            }
            result.put(request.getKey(), List.copyOf(selected));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, WorkerCandidateRequest> validate(
            Map<String, WorkerCandidateRequest> source
    ) {
        Objects.requireNonNull(source, "candidateRequests");
        LinkedHashMap<String, WorkerCandidateRequest> result =
                new LinkedHashMap<>();
        source.forEach((candidateId, request) -> {
            if (candidateId == null || candidateId.isBlank()) {
                throw new IllegalArgumentException(
                        "candidateId must be non-blank"
                );
            }
            result.put(
                    candidateId,
                    Objects.requireNonNull(request, "request")
            );
        });
        return Collections.unmodifiableMap(result);
    }

    private static List<Map.Entry<String, WorkerCandidateRequest>> ordered(
            Map<String, WorkerCandidateRequest> requests
    ) {
        List<Map.Entry<String, WorkerCandidateRequest>> result =
                new ArrayList<>(requests.entrySet());
        result.sort(Comparator
                .comparingInt((Map.Entry<String, WorkerCandidateRequest> entry)
                        -> entry.getValue().priority())
                .thenComparing(Map.Entry::getKey));
        return result;
    }

    private static Map<String, List<AcquiredWorkerCandidate>> empty(
            Map<String, WorkerCandidateRequest> requests
    ) {
        LinkedHashMap<String, List<AcquiredWorkerCandidate>> result =
                new LinkedHashMap<>();
        requests.keySet().forEach(candidateId -> result.put(
                candidateId,
                List.of()
        ));
        return Collections.unmodifiableMap(result);
    }
}
