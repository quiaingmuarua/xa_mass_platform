package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.pacer.dispatch.WorkerCandidateMechanism.LeaseMode;
import com.xa.mass.kernel.pacer.dispatch.WorkerCandidateMechanism.WorkerCandidateObservation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WorkerCandidateSelectionPolicy {

    static final int MAX_DIRECT_UNIQUE_WORKERS_PER_ROUND = 100;
    private static final int MAX_DIRECT_EXPLICIT_WORKER_IDS = 100;

    private final WorkerCandidateMechanism mechanism;
    private final WorkerCandidateMatcher matcher;
    private final int workerScanLimit;
    private final Long hotEligibilityFloorMillis;

    WorkerCandidateSelectionPolicy(
            WorkerCandidateMechanism mechanism,
            WorkerCandidateMatcher matcher,
            int workerScanLimit,
            Long hotEligibilityFloorMillis
    ) {
        this.mechanism = java.util.Objects.requireNonNull(
                mechanism,
                "mechanism"
        );
        this.matcher = java.util.Objects.requireNonNull(matcher, "matcher");
        if (workerScanLimit < 1) {
            throw new IllegalArgumentException(
                    "worker scan limit must be positive"
            );
        }
        this.workerScanLimit = workerScanLimit;
        this.hotEligibilityFloorMillis = hotEligibilityFloorMillis;
    }

    Map<String, List<WorkerCandidateObservation>> acquireWorkerCandidates(
            WorkerCandidateAcquisitionStrategy strategy,
            String workerGroupId,
            Map<String, WorkerCandidateRequest> requests,
            long leaseUntilMillis
    ) {
        return switch (java.util.Objects.requireNonNull(strategy, "strategy")) {
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

    Map<String, List<WorkerCandidateObservation>> acquireHotPoolCandidates(
            String workerGroupId,
            Map<String, WorkerCandidateRequest> requests,
            long leaseUntilMillis
    ) {
        Map<String, WorkerCandidateRequest> validated = validate(requests);
        if (validated.isEmpty()) {
            return empty(validated);
        }
        List<WorkerCandidateObservation> hot = mechanism.observeHot(
                workerGroupId,
                hotEligibilityFloorMillis,
                workerScanLimit
        );
        LinkedHashMap<String, List<WorkerCandidateObservation>> candidates =
                new LinkedHashMap<>();
        validated.keySet().forEach(id -> candidates.put(id, hot));
        return selectLeaseAndRematch(
                workerGroupId,
                candidates,
                validated,
                leaseUntilMillis,
                LeaseMode.ACQUIRE,
                workerScanLimit
        );
    }

    private Map<String, List<WorkerCandidateObservation>> acquirePrecomputed(
            String workerGroupId,
            Map<String, WorkerCandidateRequest> requests,
            long leaseUntilMillis
    ) {
        Map<String, WorkerCandidateRequest> validated = validate(requests);
        LinkedHashMap<String, List<WorkerCandidateObservation>> candidates =
                new LinkedHashMap<>();
        for (Map.Entry<String, WorkerCandidateRequest> request
                : ordered(validated)) {
            candidates.put(
                    request.getKey(),
                    mechanism.consumePrecomputed(
                            request.getKey(),
                            workerGroupId,
                            request.getValue().requestedCount()
                    )
            );
        }
        return selectLeaseAndRematch(
                workerGroupId,
                candidates,
                validated,
                leaseUntilMillis,
                LeaseMode.RENEW,
                Integer.MAX_VALUE
        );
    }

    private Map<String, List<WorkerCandidateObservation>> acquireDirect(
            String workerGroupId,
            Map<String, WorkerCandidateRequest> requests,
            long leaseUntilMillis
    ) {
        Map<String, WorkerCandidateRequest> validated = validate(requests);
        Set<String> unrestricted = new LinkedHashSet<>();
        validated.forEach((id, request) -> {
            if (request.allocationRule().isEmpty()) {
                unrestricted.add(id);
            }
        });
        List<WorkerCandidateObservation> broad = unrestricted.isEmpty()
                ? List.of()
                : mechanism.observeHot(
                        workerGroupId,
                        hotEligibilityFloorMillis,
                        Math.min(
                                workerScanLimit,
                                MAX_DIRECT_UNIQUE_WORKERS_PER_ROUND
                        )
                );
        LinkedHashSet<String> explicitIds = new LinkedHashSet<>();
        LinkedHashMap<String, List<String>> idsByCandidate =
                new LinkedHashMap<>();
        for (Map.Entry<String, WorkerCandidateRequest> request
                : ordered(validated)) {
            List<String> ids = unrestricted.contains(request.getKey())
                    ? broad.stream()
                            .map(WorkerCandidateObservation::workerId)
                            .toList()
                    : workerIdCandidates(request.getValue().allocationRule());
            idsByCandidate.put(request.getKey(), ids);
            if (!unrestricted.contains(request.getKey())) {
                explicitIds.addAll(ids);
            }
        }
        List<WorkerCandidateObservation> explicit = explicitIds.isEmpty()
                ? List.of()
                : mechanism.observeExplicit(
                        workerGroupId,
                        List.copyOf(explicitIds).subList(
                                0,
                                Math.min(
                                        explicitIds.size(),
                                        MAX_DIRECT_UNIQUE_WORKERS_PER_ROUND
                                )
                        ),
                        hotEligibilityFloorMillis
                );
        LinkedHashMap<String, WorkerCandidateObservation> byId =
                new LinkedHashMap<>();
        broad.forEach(worker -> byId.putIfAbsent(worker.workerId(), worker));
        explicit.forEach(worker -> byId.putIfAbsent(worker.workerId(), worker));
        LinkedHashMap<String, List<WorkerCandidateObservation>> candidates =
                new LinkedHashMap<>();
        idsByCandidate.forEach((candidateId, ids) -> candidates.put(
                candidateId,
                ids.stream()
                        .map(byId::get)
                        .filter(java.util.Objects::nonNull)
                        .toList()
        ));
        return selectLeaseAndRematch(
                workerGroupId,
                candidates,
                validated,
                leaseUntilMillis,
                LeaseMode.ACQUIRE,
                MAX_DIRECT_UNIQUE_WORKERS_PER_ROUND
        );
    }

    private Map<String, List<WorkerCandidateObservation>>
            selectLeaseAndRematch(
                    String workerGroupId,
                    Map<String, List<WorkerCandidateObservation>> candidates,
                    Map<String, WorkerCandidateRequest> requests,
                    long leaseUntilMillis,
                    LeaseMode mode,
                    int maximumUniqueWorkers
            ) {
        Map<String, List<WorkerCandidateObservation>> selected =
                limitSelectedWorkers(
                        matcher.match(
                                workerGroupId,
                                candidates,
                                requests,
                                true,
                                true
                        ),
                        requests,
                        maximumUniqueWorkers
        );
        LinkedHashMap<String, WorkerCandidateObservation> unique =
                new LinkedHashMap<>();
        selected.values().forEach(workers -> workers.forEach(worker ->
                unique.putIfAbsent(worker.workerId(), worker)));
        List<WorkerCandidateObservation> leased = mechanism.leaseSelected(
                workerGroupId,
                List.copyOf(unique.values()),
                leaseUntilMillis,
                mode
        );
        Map<String, WorkerCandidateObservation> leasedById =
                new LinkedHashMap<>();
        leased.forEach(worker -> leasedById.put(worker.workerId(), worker));
        LinkedHashMap<String, List<WorkerCandidateObservation>> postLease =
                new LinkedHashMap<>();
        selected.forEach((candidateId, workers) -> postLease.put(
                candidateId,
                workers.stream()
                        .map(worker -> leasedById.get(worker.workerId()))
                        .filter(java.util.Objects::nonNull)
                        .toList()
        ));
        return matcher.match(
                workerGroupId,
                postLease,
                requests,
                true,
                true
        );
    }

    private static Map<String, List<WorkerCandidateObservation>>
            limitSelectedWorkers(
                    Map<String, List<WorkerCandidateObservation>> selected,
                    Map<String, WorkerCandidateRequest> requests,
                    int maximumUniqueWorkers
            ) {
        if (maximumUniqueWorkers < 1) {
            throw new IllegalArgumentException(
                    "maximumUniqueWorkers must be positive"
            );
        }
        LinkedHashMap<String, List<WorkerCandidateObservation>> limited =
                new LinkedHashMap<>();
        requests.keySet().forEach(id -> limited.put(id, List.of()));
        LinkedHashSet<String> admittedWorkerIds = new LinkedHashSet<>();
        for (Map.Entry<String, WorkerCandidateRequest> request
                : ordered(requests)) {
            List<WorkerCandidateObservation> admitted = new ArrayList<>();
            for (WorkerCandidateObservation worker : selected.getOrDefault(
                    request.getKey(),
                    List.of()
            )) {
                if (admittedWorkerIds.contains(worker.workerId())
                        || admittedWorkerIds.size() < maximumUniqueWorkers) {
                    admittedWorkerIds.add(worker.workerId());
                    admitted.add(worker);
                }
            }
            limited.put(request.getKey(), List.copyOf(admitted));
        }
        return Collections.unmodifiableMap(limited);
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
                values = Collections.singletonList(operator.getValue());
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
        source.forEach((id, request) -> result.put(
                id,
                java.util.Objects.requireNonNull(request, "request")
        ));
        return Collections.unmodifiableMap(result);
    }

    private static List<Map.Entry<String, WorkerCandidateRequest>> ordered(
            Map<String, WorkerCandidateRequest> requests
    ) {
        List<Map.Entry<String, WorkerCandidateRequest>> result =
                new ArrayList<>(requests.entrySet());
        result.sort(Comparator
                .comparingInt((Map.Entry<String, WorkerCandidateRequest> e)
                        -> e.getValue().priority())
                .thenComparing(Map.Entry::getKey));
        return result;
    }

    private static Map<String, List<WorkerCandidateObservation>> empty(
            Map<String, WorkerCandidateRequest> requests
    ) {
        LinkedHashMap<String, List<WorkerCandidateObservation>> result =
                new LinkedHashMap<>();
        requests.keySet().forEach(id -> result.put(id, List.of()));
        return Collections.unmodifiableMap(result);
    }
}
