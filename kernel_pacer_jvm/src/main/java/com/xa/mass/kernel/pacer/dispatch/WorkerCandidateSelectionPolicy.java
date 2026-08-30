package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.CandidateWorkerCache.CandidateWorkerEntry;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class WorkerCandidateSelectionPolicy {

    static final int MAX_DIRECT_UNIQUE_WORKERS_PER_ROUND = 100;

    private final WorkerScoreCore workerScores;
    private final CandidateWorkerCache candidateCache;
    private final WorkerCandidateMatcher matcher;
    private final int workerScanLimit;
    private final Long hotEligibilityFloorMillis;

    WorkerCandidateSelectionPolicy(
            WorkerScoreCore workerScores,
            CandidateWorkerCache candidateCache,
            WorkerCandidateMatcher matcher,
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
        this.matcher = Objects.requireNonNull(matcher, "matcher");
        if (workerScanLimit < 1) {
            throw new IllegalArgumentException(
                    "worker scan limit must be positive"
            );
        }
        this.workerScanLimit = workerScanLimit;
        this.hotEligibilityFloorMillis = hotEligibilityFloorMillis;
    }

    Map<String, List<AcquiredWorkerCandidate>> acquireWorkerCandidates(
            WorkerCandidateAcquisitionStrategy strategy,
            String workerGroupId,
            Map<String, WorkerCandidateRequest> requests,
            long leaseUntilMillis
    ) {
        Objects.requireNonNull(strategy, "strategy");
        Map<String, WorkerCandidateRequest> validated = validate(requests);
        if (validated.isEmpty()) {
            return empty(validated);
        }
        WorkerCandidateMatcher.MatchPlan matchPlan = prepareMatchPlan(
                workerGroupId,
                validated
        );
        return switch (strategy) {
            case PRECOMPUTED -> acquirePrecomputed(
                    workerGroupId,
                    validated,
                    matchPlan,
                    leaseUntilMillis
            );
            case DIRECT -> acquireDirect(
                    workerGroupId,
                    validated,
                    matchPlan,
                    leaseUntilMillis
            );
        };
    }

    Map<String, List<AcquiredWorkerCandidate>> acquireHotPoolCandidates(
            String workerGroupId,
            Map<String, WorkerCandidateRequest> requests,
            long leaseUntilMillis
    ) {
        Map<String, WorkerCandidateRequest> validated = validate(requests);
        if (validated.isEmpty()) {
            return empty(validated);
        }
        WorkerCandidateMatcher.MatchPlan matchPlan = prepareMatchPlan(
                workerGroupId,
                validated
        );
        if (!matchPlan.hasValidCandidates()) {
            return empty(validated);
        }
        Map<String, Long> observed = workerScores
                .acquireHotAcquireCandidates(
                        workerGroupId,
                        hotEligibilityFloorMillis,
                        workerScanLimit
                );
        if (observed.isEmpty()) {
            return empty(validated);
        }
        Map<String, List<WorkerDescriptor>> matched =
                matcher.matchSharedWorkerPool(
                        workerGroupId,
                        List.copyOf(observed.keySet()),
                        matchPlan
                );
        return selectLeaseAndRematch(
                workerGroupId,
                observed,
                matched,
                matchPlan,
                validated,
                leaseUntilMillis,
                false,
                workerScanLimit
        );
    }

    private Map<String, List<AcquiredWorkerCandidate>> acquirePrecomputed(
            String workerGroupId,
            Map<String, WorkerCandidateRequest> requests,
            WorkerCandidateMatcher.MatchPlan matchPlan,
            long leaseUntilMillis
    ) {
        LinkedHashMap<String, Long> observed =
                new LinkedHashMap<>();
        LinkedHashMap<String, List<String>> candidateWorkerIds =
                new LinkedHashMap<>();
        for (Map.Entry<String, WorkerCandidateRequest> request
                : ordered(requests)) {
            if (!matchPlan.isValid(request.getKey())) {
                continue;
            }
            Map<String, Long> cached = consumePrecomputed(
                    request.getKey(),
                    workerGroupId,
                    request.getValue().requestedCount()
            );
            candidateWorkerIds.put(
                    request.getKey(),
                    List.copyOf(cached.keySet())
            );
            cached.forEach(observed::putIfAbsent);
        }
        if (observed.isEmpty()) {
            return empty(requests);
        }
        Map<String, List<String>> boundedCandidateWorkerIds =
                intersectWithObserved(
                        candidateWorkerIds,
                        observed.keySet()
                );
        Map<String, List<WorkerDescriptor>> matched =
                matcher.matchCandidateScopedWorkerIds(
                        workerGroupId,
                        boundedCandidateWorkerIds,
                        matchPlan
                );
        return selectLeaseAndRematch(
                workerGroupId,
                observed,
                matched,
                matchPlan,
                requests,
                leaseUntilMillis,
                true,
                Integer.MAX_VALUE
        );
    }

    private Map<String, List<AcquiredWorkerCandidate>> acquireDirect(
            String workerGroupId,
            Map<String, WorkerCandidateRequest> requests,
            WorkerCandidateMatcher.MatchPlan matchPlan,
            long leaseUntilMillis
    ) {
        Set<String> unrestricted = matcher.unrestrictedCandidateIds(
                matchPlan
        );
        Map<String, List<String>> explicitByCandidate =
                matcher.explicitWorkerIdsByCandidate(
                        matchPlan,
                        MAX_DIRECT_UNIQUE_WORKERS_PER_ROUND
                );
        Map<String, Long> broad = unrestricted.isEmpty()
                ? Map.of()
                : workerScores.acquireHotAcquireCandidates(
                        workerGroupId,
                        hotEligibilityFloorMillis,
                        Math.min(
                                workerScanLimit,
                                MAX_DIRECT_UNIQUE_WORKERS_PER_ROUND
                        )
                );
        LinkedHashSet<String> explicitIds = new LinkedHashSet<>();
        LinkedHashMap<String, List<String>> candidateWorkerIds =
                new LinkedHashMap<>();
        for (Map.Entry<String, WorkerCandidateRequest> request
                : ordered(requests)) {
            List<String> workerIds = unrestricted.contains(request.getKey())
                    ? List.copyOf(broad.keySet())
                    : explicitByCandidate.getOrDefault(
                            request.getKey(),
                            List.of()
                    );
            candidateWorkerIds.put(request.getKey(), workerIds);
            if (explicitByCandidate.containsKey(request.getKey())) {
                explicitIds.addAll(workerIds);
            }
        }
        List<String> boundedExplicitIds = List.copyOf(explicitIds).subList(
                0,
                Math.min(
                        explicitIds.size(),
                        MAX_DIRECT_UNIQUE_WORKERS_PER_ROUND
                )
        );
        Map<String, Long> explicit =
                boundedExplicitIds.isEmpty()
                        ? Map.of()
                        : workerScores.observeDueHotScores(
                                workerGroupId,
                                boundedExplicitIds,
                                hotEligibilityFloorMillis
                        );
        LinkedHashMap<String, Long> observed =
                new LinkedHashMap<>(broad);
        explicit.forEach(observed::putIfAbsent);
        if (observed.isEmpty()) {
            return empty(requests);
        }
        Map<String, List<String>> boundedCandidateWorkerIds =
                intersectWithObserved(
                        candidateWorkerIds,
                        observed.keySet()
                );
        Map<String, List<WorkerDescriptor>> matched =
                matcher.matchCandidateScopedWorkerIds(
                        workerGroupId,
                        boundedCandidateWorkerIds,
                        matchPlan
                );
        return selectLeaseAndRematch(
                workerGroupId,
                observed,
                matched,
                matchPlan,
                requests,
                leaseUntilMillis,
                false,
                MAX_DIRECT_UNIQUE_WORKERS_PER_ROUND
        );
    }

    private Map<String, List<AcquiredWorkerCandidate>>
            selectLeaseAndRematch(
                    String workerGroupId,
                    Map<String, Long> observed,
                    Map<String, List<WorkerDescriptor>> matched,
                    WorkerCandidateMatcher.MatchPlan matchPlan,
                    Map<String, WorkerCandidateRequest> requests,
                    long leaseUntilMillis,
                    boolean renewal,
                    int maximumUniqueWorkers
            ) {
        if (maximumUniqueWorkers < 1) {
            throw new IllegalArgumentException(
                    "maximumUniqueWorkers must be positive"
            );
        }
        Map<String, List<String>> selected = selectUniqueWorkerIds(
                matched,
                requests,
                maximumUniqueWorkers
        );
        LinkedHashMap<String, Long> selectedScores =
                new LinkedHashMap<>();
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
        Map<String, WorkerScoreTransitionResult> transitions = renewal
                ? workerScores.renewActiveHotScoreLeases(
                        workerGroupId,
                        selectedScores,
                        leaseUntilMillis
                )
                : workerScores.acquireObservedHotScoreLeases(
                        workerGroupId,
                        selectedScores,
                        leaseUntilMillis
                );
        LinkedHashMap<String, Long> leased = new LinkedHashMap<>();
        selectedScores.keySet().forEach(workerId -> {
            WorkerScoreTransitionResult result = transitions.get(workerId);
            if (result != null
                    && result.score() != null
                    && (result.status()
                            == WorkerScoreTransitionStatus.TRANSITIONED
                    || renewal && result.status()
                            == WorkerScoreTransitionStatus.NOOP)) {
                leased.put(workerId, result.score());
            }
        });
        if (leased.isEmpty()) {
            return empty(requests);
        }
        Map<String, List<String>> leasedWorkerIdsByCandidate =
                retainLeasedWorkerIds(selected, leased.keySet());
        Map<String, List<WorkerDescriptor>> rematched =
                matcher.matchCandidateScopedWorkerIds(
                        workerGroupId,
                        leasedWorkerIdsByCandidate,
                        matchPlan
                );
        return acquiredCandidates(
                workerGroupId,
                leased,
                rematched,
                requests
        );
    }

    private Map<String, Long> consumePrecomputed(
            String candidateId,
            String workerGroupId,
            int limit
    ) {
        List<CandidateWorkerEntry> cached =
                candidateCache.consumeCandidateWorkers(candidateId, limit);
        LinkedHashMap<String, Long> observed = new LinkedHashMap<>();
        for (CandidateWorkerEntry entry : cached) {
            if (workerGroupId.equals(entry.workerGroupId())) {
                observed.putIfAbsent(
                        entry.workerId(),
                        entry.workerLeaseScore()
                );
            }
        }
        return Collections.unmodifiableMap(observed);
    }

    private static Map<String, List<String>> intersectWithObserved(
            Map<String, List<String>> candidateWorkerIds,
            Set<String> observedWorkerIds
    ) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        candidateWorkerIds.forEach((candidateId, workerIds) -> {
            LinkedHashSet<String> bounded = new LinkedHashSet<>();
            for (String workerId : workerIds) {
                if (observedWorkerIds.contains(workerId)) {
                    bounded.add(workerId);
                }
            }
            result.put(candidateId, List.copyOf(bounded));
        });
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, List<String>> selectUniqueWorkerIds(
            Map<String, List<WorkerDescriptor>> matched,
            Map<String, WorkerCandidateRequest> requests,
            int maximumUniqueWorkers
    ) {
        LinkedHashMap<String, List<String>> selected = new LinkedHashMap<>();
        requests.keySet().forEach(candidateId -> selected.put(
                candidateId,
                List.of()
        ));
        LinkedHashSet<String> usedWorkerIds = new LinkedHashSet<>();
        for (Map.Entry<String, WorkerCandidateRequest> request
                : ordered(requests)) {
            List<String> matches = new ArrayList<>();
            for (WorkerDescriptor descriptor : matched.getOrDefault(
                    request.getKey(),
                    List.of()
            )) {
                String workerId = descriptor.workerId();
                if (usedWorkerIds.contains(workerId)) {
                    continue;
                }
                if (usedWorkerIds.size() >= maximumUniqueWorkers) {
                    break;
                }
                usedWorkerIds.add(workerId);
                matches.add(workerId);
                if (matches.size() >= request.getValue().requestedCount()) {
                    break;
                }
            }
            selected.put(request.getKey(), List.copyOf(matches));
        }
        return Collections.unmodifiableMap(selected);
    }

    private static Map<String, List<String>> retainLeasedWorkerIds(
            Map<String, List<String>> selectedWorkerIds,
            Set<String> leasedWorkerIds
    ) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        selectedWorkerIds.forEach((candidateId, workerIds) -> {
            List<String> retained = workerIds.stream()
                    .filter(leasedWorkerIds::contains)
                    .toList();
            result.put(candidateId, retained);
        });
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, List<AcquiredWorkerCandidate>>
            acquiredCandidates(
                    String workerGroupId,
                    Map<String, Long> leasedScores,
                    Map<String, List<WorkerDescriptor>> rematched,
                    Map<String, WorkerCandidateRequest> requests
            ) {
        LinkedHashMap<String, List<AcquiredWorkerCandidate>> result =
                new LinkedHashMap<>();
        requests.keySet().forEach(candidateId -> {
            List<AcquiredWorkerCandidate> candidates = rematched.getOrDefault(
                    candidateId,
                    List.of()
            ).stream().map(descriptor -> new AcquiredWorkerCandidate(
                    descriptor.workerId(),
                    workerGroupId,
                    descriptor.endpointManagerId(),
                    Objects.requireNonNull(
                            leasedScores.get(descriptor.workerId()),
                            "workerLeaseScore"
                    )
            )).toList();
            result.put(candidateId, candidates);
        });
        return Collections.unmodifiableMap(result);
    }

    private WorkerCandidateMatcher.MatchPlan prepareMatchPlan(
            String workerGroupId,
            Map<String, WorkerCandidateRequest> requests
    ) {
        LinkedHashMap<String, Map<String, Object>> rules =
                new LinkedHashMap<>();
        requests.forEach((candidateId, request) -> rules.put(
                candidateId,
                request.allocationRule()
        ));
        return matcher.prepare(workerGroupId, rules);
    }

    private static Map<String, WorkerCandidateRequest> validate(
            Map<String, WorkerCandidateRequest> source
    ) {
        if (source == null) {
            throw new IllegalArgumentException(
                    "candidateRequests must be present"
            );
        }
        LinkedHashMap<String, WorkerCandidateRequest> result =
                new LinkedHashMap<>();
        source.forEach((candidateId, request) -> result.put(
                candidateId,
                Objects.requireNonNull(request, "request")
        ));
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
