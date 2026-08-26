package com.xa.mass.kernel.assignment;

import com.xa.mass.kernel.assignment.CandidateWorkerCache.CandidateWorkerEntry;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WorkerCandidateAcquirer {

    public static final int MAX_DIRECT_UNIQUE_WORKERS_PER_ROUND = 100;
    private static final int MAX_DIRECT_EXPLICIT_WORKER_IDS = 100;

    private final CandidateWorkerCache candidateCache;
    private final WorkerScoreCore workerScore;
    private final WorkerCandidateMatcher matcher;
    private final int workerScanLimit;
    private final Long hotEligibilityFloorMillis;

    public WorkerCandidateAcquirer(
            CandidateWorkerCache candidateCache,
            WorkerScoreCore workerScore,
            WorkerCandidateMatcher matcher,
            int workerScanLimit,
            Long hotEligibilityFloorMillis
    ) {
        this.candidateCache = java.util.Objects.requireNonNull(
                candidateCache,
                "candidateCache"
        );
        this.workerScore = java.util.Objects.requireNonNull(
                workerScore,
                "workerScore"
        );
        this.matcher = java.util.Objects.requireNonNull(matcher, "matcher");
        if (workerScanLimit <= 0) {
            throw new IllegalArgumentException(
                    "worker scan limit must be positive"
            );
        }
        if (hotEligibilityFloorMillis != null
                && (hotEligibilityFloorMillis < WorkerScoreCore.MIN_TIME_MILLIS
                || hotEligibilityFloorMillis > WorkerScoreCore.MAX_TIME_MILLIS
                || hotEligibilityFloorMillis % WorkerScoreCore.SLOT_MILLIS
                != 0)) {
            throw new IllegalArgumentException(
                    "HOT eligibility floor must be score-slot-aligned"
            );
        }
        this.workerScanLimit = workerScanLimit;
        this.hotEligibilityFloorMillis = hotEligibilityFloorMillis;
    }

    public Map<String, List<CandidateWorkerEntry>> acquireWorkerCandidates(
            WorkerCandidateAcquisitionStrategy strategy,
            String workerGroupId,
            Map<String, WorkerCandidateRequest> candidateRequests,
            long leaseUntilMillis
    ) {
        java.util.Objects.requireNonNull(strategy, "strategy");
        requireNonBlank(workerGroupId, "workerGroupId");
        Map<String, WorkerCandidateRequest> requests = validateRequests(
                candidateRequests
        );
        return switch (strategy) {
            case PRECOMPUTED -> acquirePrecomputed(
                    workerGroupId,
                    requests,
                    leaseUntilMillis
            );
            case DIRECT -> acquireDirect(
                    workerGroupId,
                    requests,
                    leaseUntilMillis
            );
        };
    }

    public Map<String, List<CandidateWorkerEntry>> acquireHotPoolCandidates(
            String workerGroupId,
            Map<String, WorkerCandidateRequest> candidateRequests,
            long leaseUntilMillis
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        Map<String, WorkerCandidateRequest> requests = validateRequests(
                candidateRequests
        );
        if (requests.isEmpty()) {
            return empty(requests);
        }
        Map<String, Long> observed = workerScore.acquireHotAcquireCandidates(
                workerGroupId,
                hotEligibilityFloorMillis,
                workerScanLimit
        );
        return observed.isEmpty()
                ? empty(requests)
                : leaseAndMatch(
                        workerGroupId,
                        requests,
                        observed,
                        leaseUntilMillis
                );
    }

    private Map<String, List<CandidateWorkerEntry>> acquirePrecomputed(
            String workerGroupId,
            Map<String, WorkerCandidateRequest> requests,
            long leaseUntilMillis
    ) {
        if (requests.isEmpty()) {
            return empty(requests);
        }
        LinkedHashMap<String, Long> observed = new LinkedHashMap<>();
        for (Map.Entry<String, WorkerCandidateRequest> request
                : ordered(requests)) {
            List<CandidateWorkerEntry> cached =
                    candidateCache.consumeCandidateWorkers(
                            request.getKey(),
                            request.getValue().requestedCount()
                    );
            for (CandidateWorkerEntry entry : cached) {
                if (workerGroupId.equals(entry.workerGroupId())) {
                    observed.putIfAbsent(
                            entry.workerId(),
                            entry.workerLeaseScore()
                    );
                }
            }
        }
        if (observed.isEmpty()) {
            return empty(requests);
        }
        Map<String, WorkerScoreTransitionResult> renewed =
                workerScore.renewActiveHotScoreLeases(
                        workerGroupId,
                        observed,
                        leaseUntilMillis
                );
        Map<String, Long> renewedScores = successfulScores(renewed, true);
        return renewedScores.isEmpty()
                ? empty(requests)
                : matcher.matchWorkerCandidates(
                        workerGroupId,
                        renewedScores,
                        requests
                );
    }

    private Map<String, List<CandidateWorkerEntry>> acquireDirect(
            String workerGroupId,
            Map<String, WorkerCandidateRequest> requests,
            long leaseUntilMillis
    ) {
        if (requests.isEmpty()) {
            return empty(requests);
        }
        List<Map.Entry<String, WorkerCandidateRequest>> ordered = ordered(
                requests
        );
        Set<String> unrestricted = new LinkedHashSet<>();
        ordered.forEach(entry -> {
            if (entry.getValue().allocationRule().isEmpty()) {
                unrestricted.add(entry.getKey());
            }
        });
        Map<String, Long> broadScores = unrestricted.isEmpty()
                ? Map.of()
                : workerScore.acquireHotAcquireCandidates(
                        workerGroupId,
                        hotEligibilityFloorMillis,
                        Math.min(
                                workerScanLimit,
                                MAX_DIRECT_UNIQUE_WORKERS_PER_ROUND
                        )
                );

        LinkedHashMap<String, List<String>> candidateWorkerIds =
                new LinkedHashMap<>();
        LinkedHashSet<String> admitted = new LinkedHashSet<>();
        for (Map.Entry<String, WorkerCandidateRequest> entry : ordered) {
            List<String> requestedIds = unrestricted.contains(entry.getKey())
                    ? List.copyOf(broadScores.keySet())
                    : workerIdCandidates(entry.getValue().allocationRule());
            List<String> admittedForCandidate = new ArrayList<>();
            for (String workerId : requestedIds) {
                if (admitted.contains(workerId)) {
                    admittedForCandidate.add(workerId);
                    continue;
                }
                if (admitted.size()
                        >= MAX_DIRECT_UNIQUE_WORKERS_PER_ROUND) {
                    continue;
                }
                admitted.add(workerId);
                admittedForCandidate.add(workerId);
            }
            candidateWorkerIds.put(
                    entry.getKey(),
                    List.copyOf(admittedForCandidate)
            );
        }

        LinkedHashMap<String, List<String>> explicitIds = new LinkedHashMap<>();
        LinkedHashMap<String, WorkerCandidateRequest> explicitRequests =
                new LinkedHashMap<>();
        requests.forEach((candidateId, request) -> {
            if (!unrestricted.contains(candidateId)) {
                explicitIds.put(candidateId, candidateWorkerIds.get(
                        candidateId
                ));
                explicitRequests.put(candidateId, request);
            }
        });
        Map<String, List<String>> filtered = explicitIds.isEmpty()
                ? Map.of()
                : matcher.filterCandidateWorkerIds(
                        workerGroupId,
                        explicitIds,
                        explicitRequests
                );
        LinkedHashMap<String, List<String>> matchedIds = new LinkedHashMap<>();
        requests.keySet().forEach(candidateId -> matchedIds.put(
                candidateId,
                unrestricted.contains(candidateId)
                        ? candidateWorkerIds.get(candidateId)
                        : filtered.getOrDefault(candidateId, List.of())
        ));

        LinkedHashSet<String> pointIds = new LinkedHashSet<>();
        for (Map.Entry<String, WorkerCandidateRequest> entry : ordered) {
            for (String workerId : matchedIds.getOrDefault(
                    entry.getKey(),
                    List.of()
            )) {
                if (!broadScores.containsKey(workerId)) {
                    pointIds.add(workerId);
                }
            }
        }
        Map<String, Long> pointScores = pointIds.isEmpty()
                ? Map.of()
                : workerScore.observeDueHotScores(
                        workerGroupId,
                        List.copyOf(pointIds),
                        hotEligibilityFloorMillis
                );
        LinkedHashMap<String, Long> observed = new LinkedHashMap<>();
        observed.putAll(broadScores);
        observed.putAll(pointScores);
        if (observed.isEmpty()) {
            return empty(requests);
        }

        LinkedHashMap<String, List<String>> selected = new LinkedHashMap<>();
        LinkedHashSet<String> reserved = new LinkedHashSet<>();
        for (Map.Entry<String, WorkerCandidateRequest> entry : ordered) {
            List<String> ids = new ArrayList<>();
            for (String workerId : matchedIds.getOrDefault(
                    entry.getKey(),
                    List.of()
            )) {
                if (observed.containsKey(workerId)
                        && reserved.add(workerId)) {
                    ids.add(workerId);
                    if (ids.size() >= entry.getValue().requestedCount()) {
                        break;
                    }
                }
            }
            selected.put(entry.getKey(), List.copyOf(ids));
        }
        LinkedHashMap<String, Long> selectedScores = new LinkedHashMap<>();
        selected.values().forEach(ids -> ids.forEach(workerId ->
                selectedScores.put(workerId, observed.get(workerId))));
        if (selectedScores.isEmpty()) {
            return empty(requests);
        }
        Map<String, Long> leased = successfulScores(
                workerScore.acquireObservedHotScoreLeases(
                        workerGroupId,
                        selectedScores,
                        leaseUntilMillis
                ),
                false
        );
        return leased.isEmpty()
                ? empty(requests)
                : matcher.matchExplicitWorkerCandidates(
                        workerGroupId,
                        leased,
                        selected,
                        requests
                );
    }

    private Map<String, List<CandidateWorkerEntry>> leaseAndMatch(
            String workerGroupId,
            Map<String, WorkerCandidateRequest> requests,
            Map<String, Long> observed,
            long leaseUntilMillis
    ) {
        Map<String, Long> leased = successfulScores(
                workerScore.acquireObservedHotScoreLeases(
                        workerGroupId,
                        observed,
                        leaseUntilMillis
                ),
                false
        );
        return leased.isEmpty()
                ? empty(requests)
                : matcher.matchWorkerCandidates(
                        workerGroupId,
                        leased,
                        requests
                );
    }

    private static List<String> workerIdCandidates(
            Map<String, Object> allocationRule
    ) {
        try {
            Map<String, Map<String, Object>> compiled =
                    ConstraintEvaluator.compileMatchRules(allocationRule);
            Map<String, Object> workerId = compiled.get("workerId");
            if (workerId == null || workerId.size() != 1) {
                return List.of();
            }
            Map.Entry<String, Object> operator = workerId.entrySet()
                    .iterator().next();
            List<?> values;
            if ("$eq".equals(operator.getKey())
                    || "$equal".equals(operator.getKey())) {
                values = List.of(operator.getValue());
            } else if ("$in".equals(operator.getKey())
                    && operator.getValue() instanceof List<?> list
                    && !list.isEmpty()
                    && list.size() <= MAX_DIRECT_EXPLICIT_WORKER_IDS) {
                values = list;
            } else {
                return List.of();
            }
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (Object value : values) {
                if (!(value instanceof String id) || id.isEmpty()) {
                    return List.of();
                }
                ids.add(id);
            }
            return List.copyOf(ids);
        } catch (IllegalArgumentException error) {
            return List.of();
        }
    }

    private static Map<String, Long> successfulScores(
            Map<String, WorkerScoreTransitionResult> results,
            boolean allowNoop
    ) {
        LinkedHashMap<String, Long> accepted = new LinkedHashMap<>();
        results.forEach((workerId, result) -> {
            if (result.score() != null
                    && (result.status()
                    == WorkerScoreTransitionStatus.TRANSITIONED
                    || allowNoop
                    && result.status() == WorkerScoreTransitionStatus.NOOP)) {
                accepted.put(workerId, result.score());
            }
        });
        return accepted;
    }

    private static Map<String, WorkerCandidateRequest> validateRequests(
            Map<String, WorkerCandidateRequest> source
    ) {
        if (source == null) {
            throw new IllegalArgumentException(
                    "candidateRequests must be present"
            );
        }
        LinkedHashMap<String, WorkerCandidateRequest> requests =
                new LinkedHashMap<>();
        source.forEach((candidateId, request) -> {
            requireNonBlank(candidateId, "candidateId");
            requests.put(
                    candidateId,
                    java.util.Objects.requireNonNull(request, "request")
            );
        });
        return Collections.unmodifiableMap(requests);
    }

    private static List<Map.Entry<String, WorkerCandidateRequest>> ordered(
            Map<String, WorkerCandidateRequest> requests
    ) {
        List<Map.Entry<String, WorkerCandidateRequest>> ordered =
                new ArrayList<>(requests.entrySet());
        ordered.sort(Comparator
                .comparingInt((Map.Entry<String, WorkerCandidateRequest> entry)
                        -> entry.getValue().priority())
                .thenComparing(Map.Entry::getKey));
        return ordered;
    }

    private static Map<String, List<CandidateWorkerEntry>> empty(
            Map<String, WorkerCandidateRequest> requests
    ) {
        LinkedHashMap<String, List<CandidateWorkerEntry>> empty =
                new LinkedHashMap<>();
        requests.keySet().forEach(id -> empty.put(id, List.of()));
        return Collections.unmodifiableMap(empty);
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }
}
