package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.pacer.dispatch.ConstraintEvaluator.Condition;
import com.xa.mass.kernel.pacer.dispatch.ConstraintEvaluator.Operator;
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

final class WorkerCandidateMatcher {

    private static final System.Logger LOGGER = System.getLogger(
            WorkerCandidateMatcher.class.getName()
    );

    private final WorkerResourceCatalog workerCatalog;
    private final ConstraintEvaluator constraintEvaluator;

    WorkerCandidateMatcher(WorkerResourceCatalog workerCatalog) {
        this(workerCatalog, new ConstraintEvaluator());
    }

    WorkerCandidateMatcher(
            WorkerResourceCatalog workerCatalog,
            ConstraintEvaluator constraintEvaluator
    ) {
        this.workerCatalog = Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
        this.constraintEvaluator = Objects.requireNonNull(
                constraintEvaluator,
                "constraintEvaluator"
        );
    }

    MatchPlan prepare(
            String workerGroupId,
            Map<String, Map<String, Object>> rulesByCandidateId
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        Objects.requireNonNull(rulesByCandidateId, "rulesByCandidateId");
        LinkedHashMap<String, List<Condition>> constraints =
                new LinkedHashMap<>();
        int invalid = 0;
        for (Map.Entry<String, Map<String, Object>> entry
                : rulesByCandidateId.entrySet()) {
            requireNonBlank(entry.getKey(), "candidateId");
            try {
                constraints.put(
                        entry.getKey(),
                        constraintEvaluator.normalize(entry.getValue())
                );
            } catch (IllegalArgumentException error) {
                invalid++;
            }
        }
        if (invalid > 0) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "operation=workerCandidate.prepare rejectedRules="
                            + invalid + " workerGroupId=" + workerGroupId
            );
        }
        return new MatchPlan(
                List.copyOf(rulesByCandidateId.keySet()),
                constraints
        );
    }

    Set<String> unrestrictedCandidateIds(MatchPlan plan) {
        Objects.requireNonNull(plan, "plan");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        plan.conditionsByCandidateId.forEach((candidateId, conditions) -> {
            if (conditions.isEmpty()) {
                result.add(candidateId);
            }
        });
        return Collections.unmodifiableSet(result);
    }

    Map<String, List<String>> explicitWorkerIdsByCandidate(
            MatchPlan plan,
            int limit
    ) {
        Objects.requireNonNull(plan, "plan");
        if (limit < 1) {
            throw new IllegalArgumentException(
                    "candidate WorkerId limit must be positive"
            );
        }
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        candidate:
        for (Map.Entry<String, List<Condition>> candidate
                : plan.conditionsByCandidateId.entrySet()) {
            List<Condition> identityConditions = candidate.getValue().stream()
                    .filter(condition -> "workerId".equals(
                            condition.propertyName()
                    ))
                    .toList();
            if (identityConditions.size() != 1) {
                continue;
            }
            Condition identity = identityConditions.getFirst();
            if (identity.operator() != Operator.EQ
                    && identity.operator() != Operator.IN) {
                continue;
            }
            List<Object> values = identity.params();
            if (values.size() > limit) {
                continue;
            }
            LinkedHashSet<String> workerIds = new LinkedHashSet<>();
            for (Object value : values) {
                if (!(value instanceof String workerId)
                        || workerId.isEmpty()) {
                    continue candidate;
                }
                workerIds.add(workerId);
            }
            result.put(candidate.getKey(), List.copyOf(workerIds));
        }
        return Collections.unmodifiableMap(result);
    }

    Map<String, List<WorkerDescriptor>> matchSharedWorkerPool(
            String workerGroupId,
            List<String> workerIds,
            MatchPlan plan
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        Objects.requireNonNull(plan, "plan");
        List<String> boundedWorkerIds = boundedWorkerIds(workerIds);
        Map<String, WorkerDescriptor> descriptors = loadDescriptors(
                workerGroupId,
                boundedWorkerIds
        );
        LinkedHashMap<String, List<WorkerDescriptor>> matches = emptyMatches(
                plan
        );
        for (Map.Entry<String, List<Condition>> candidate
                : plan.conditionsByCandidateId.entrySet()) {
            List<WorkerDescriptor> candidateMatches = matches.get(
                    candidate.getKey()
            );
            for (String workerId : boundedWorkerIds) {
                addIfMatched(
                        workerId,
                        descriptors,
                        candidate.getValue(),
                        candidateMatches
                );
            }
        }
        return freeze(matches);
    }

    Map<String, List<WorkerDescriptor>> matchCandidateScopedWorkerIds(
            String workerGroupId,
            Map<String, List<String>> workerIdsByCandidateId,
            MatchPlan plan
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        Objects.requireNonNull(
                workerIdsByCandidateId,
                "workerIdsByCandidateId"
        );
        Objects.requireNonNull(plan, "plan");
        Map<String, WorkerDescriptor> descriptors = loadDescriptors(
                workerGroupId,
                boundedWorkerIds(plan, workerIdsByCandidateId)
        );
        LinkedHashMap<String, List<WorkerDescriptor>> matches = emptyMatches(
                plan
        );
        for (Map.Entry<String, List<Condition>> candidate
                : plan.conditionsByCandidateId.entrySet()) {
            List<WorkerDescriptor> candidateMatches = matches.get(
                    candidate.getKey()
            );
            for (String workerId : boundedWorkerIds(
                    workerIdsByCandidateId.getOrDefault(
                            candidate.getKey(),
                            List.of()
                    )
            )) {
                addIfMatched(
                        workerId,
                        descriptors,
                        candidate.getValue(),
                        candidateMatches
                );
            }
        }
        return freeze(matches);
    }

    private void addIfMatched(
            String workerId,
            Map<String, WorkerDescriptor> descriptors,
            List<Condition> conditions,
            List<WorkerDescriptor> matches
    ) {
        WorkerDescriptor descriptor = descriptors.get(workerId);
        if (descriptor != null && constraintEvaluator.matches(
                conditions,
                workerId,
                descriptor.workerProperties(),
                descriptor.platformProperties()
        )) {
            matches.add(descriptor);
        }
    }

    private Map<String, WorkerDescriptor> loadDescriptors(
            String workerGroupId,
            List<String> workerIds
    ) {
        if (workerIds.isEmpty()) {
            return Map.of();
        }
        Map<String, WorkerDescriptor> loaded =
                workerCatalog.getWorkerDescriptors(
                        workerGroupId,
                        workerIds
                );
        LinkedHashMap<String, WorkerDescriptor> descriptors =
                new LinkedHashMap<>();
        for (String workerId : workerIds) {
            WorkerDescriptor descriptor = loaded.get(workerId);
            if (descriptor != null
                    && workerId.equals(descriptor.workerId())
                    && workerGroupId.equals(descriptor.workerGroupId())) {
                descriptors.put(workerId, descriptor);
            }
        }
        return Collections.unmodifiableMap(descriptors);
    }

    private static List<String> boundedWorkerIds(List<String> workerIds) {
        Objects.requireNonNull(workerIds, "workerIds");
        LinkedHashSet<String> bounded = new LinkedHashSet<>();
        for (String workerId : workerIds) {
            requireNonBlank(workerId, "workerId");
            bounded.add(workerId);
        }
        return List.copyOf(bounded);
    }

    private static List<String> boundedWorkerIds(
            MatchPlan plan,
            Map<String, List<String>> workerIdsByCandidateId
    ) {
        LinkedHashSet<String> workerIds = new LinkedHashSet<>();
        for (String candidateId : plan.conditionsByCandidateId.keySet()) {
            workerIds.addAll(boundedWorkerIds(
                    workerIdsByCandidateId.getOrDefault(
                            candidateId,
                            List.of()
                    )
            ));
        }
        return List.copyOf(workerIds);
    }

    private static LinkedHashMap<String, List<WorkerDescriptor>> emptyMatches(
            MatchPlan plan
    ) {
        LinkedHashMap<String, List<WorkerDescriptor>> result =
                new LinkedHashMap<>();
        plan.candidateIds.forEach(id -> result.put(id, new ArrayList<>()));
        return result;
    }

    private static Map<String, List<WorkerDescriptor>> freeze(
            Map<String, List<WorkerDescriptor>> source
    ) {
        LinkedHashMap<String, List<WorkerDescriptor>> result =
                new LinkedHashMap<>();
        source.forEach((id, workers) -> result.put(
                id,
                List.copyOf(workers)
        ));
        return Collections.unmodifiableMap(result);
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }

    static final class MatchPlan {

        private final List<String> candidateIds;
        private final Map<String, List<Condition>> conditionsByCandidateId;

        MatchPlan(
                List<String> candidateIds,
                Map<String, List<Condition>> conditionsByCandidateId
        ) {
            this.candidateIds = List.copyOf(candidateIds);
            this.conditionsByCandidateId = Collections.unmodifiableMap(
                    new LinkedHashMap<>(conditionsByCandidateId)
            );
        }

        boolean hasValidCandidates() {
            return !conditionsByCandidateId.isEmpty();
        }

        boolean isValid(String candidateId) {
            return conditionsByCandidateId.containsKey(candidateId);
        }
    }
}
