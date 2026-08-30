package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class WorkerCandidateMatcher {

    private static final System.Logger LOGGER = System.getLogger(
            WorkerCandidateMatcher.class.getName()
    );

    private final WorkerResourceCatalog workerCatalog;

    WorkerCandidateMatcher(WorkerResourceCatalog workerCatalog) {
        this.workerCatalog = Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
    }

    Map<String, List<WorkerDescriptor>> matchSharedWorkerPool(
            String workerGroupId,
            List<String> workerIds,
            Map<String, Map<String, Object>> rulesByCandidateId
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        List<String> boundedWorkerIds = boundedWorkerIds(workerIds);
        List<PreparedCandidate> candidates = prepare(
                rulesByCandidateId,
                workerGroupId
        );
        Map<String, WorkerDescriptor> descriptors = loadDescriptors(
                workerGroupId,
                boundedWorkerIds
        );
        LinkedHashMap<String, List<WorkerDescriptor>> matches = emptyMatches(
                rulesByCandidateId
        );
        for (PreparedCandidate candidate : candidates) {
            List<WorkerDescriptor> candidateMatches = matches.get(
                    candidate.candidateId()
            );
            for (String workerId : boundedWorkerIds) {
                addIfMatched(
                        workerId,
                        descriptors,
                        candidate,
                        candidateMatches
                );
            }
        }
        return freeze(matches);
    }

    Map<String, List<WorkerDescriptor>> matchCandidateScopedWorkerIds(
            String workerGroupId,
            Map<String, List<String>> workerIdsByCandidateId,
            Map<String, Map<String, Object>> rulesByCandidateId
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        Objects.requireNonNull(
                workerIdsByCandidateId,
                "workerIdsByCandidateId"
        );
        List<PreparedCandidate> candidates = prepare(
                rulesByCandidateId,
                workerGroupId
        );
        Map<String, WorkerDescriptor> descriptors = loadDescriptors(
                workerGroupId,
                boundedWorkerIds(candidates, workerIdsByCandidateId)
        );
        LinkedHashMap<String, List<WorkerDescriptor>> matches = emptyMatches(
                rulesByCandidateId
        );
        for (PreparedCandidate candidate : candidates) {
            List<WorkerDescriptor> candidateMatches = matches.get(
                    candidate.candidateId()
            );
            for (String workerId : boundedWorkerIds(
                    workerIdsByCandidateId.getOrDefault(
                            candidate.candidateId(),
                            List.of()
                    )
            )) {
                addIfMatched(
                        workerId,
                        descriptors,
                        candidate,
                        candidateMatches
                );
            }
        }
        return freeze(matches);
    }

    private static void addIfMatched(
            String workerId,
            Map<String, WorkerDescriptor> descriptors,
            PreparedCandidate candidate,
            List<WorkerDescriptor> matches
    ) {
        WorkerDescriptor descriptor = descriptors.get(workerId);
        if (descriptor != null && matches(
                workerId,
                descriptor,
                candidate.compiledRule()
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
            List<PreparedCandidate> candidates,
            Map<String, List<String>> workerIdsByCandidateId
    ) {
        LinkedHashSet<String> workerIds = new LinkedHashSet<>();
        for (PreparedCandidate candidate : candidates) {
            workerIds.addAll(boundedWorkerIds(
                    workerIdsByCandidateId.getOrDefault(
                            candidate.candidateId(),
                            List.of()
                    )
            ));
        }
        return List.copyOf(workerIds);
    }

    private static List<PreparedCandidate> prepare(
            Map<String, Map<String, Object>> rulesByCandidateId,
            String workerGroupId
    ) {
        Objects.requireNonNull(rulesByCandidateId, "rulesByCandidateId");
        List<PreparedCandidate> candidates = new ArrayList<>();
        int invalid = 0;
        for (Map.Entry<String, Map<String, Object>> entry
                : rulesByCandidateId.entrySet()) {
            requireNonBlank(entry.getKey(), "candidateId");
            try {
                Map<String, Map<String, Object>> rule =
                        ConstraintEvaluator.compileMatchRules(
                                Objects.requireNonNull(
                                        entry.getValue(),
                                        "allocationRule"
                                )
                        );
                if (rule.keySet().stream().anyMatch(
                        field -> !validAllocationField(field)
                )) {
                    throw new IllegalArgumentException(
                            "unsupported Worker allocation field"
                    );
                }
                candidates.add(new PreparedCandidate(
                        entry.getKey(),
                        rule
                ));
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
        return List.copyOf(candidates);
    }

    private static boolean matches(
            String workerId,
            WorkerDescriptor descriptor,
            Map<String, Map<String, Object>> compiledRule
    ) {
        LinkedHashMap<String, Object> context = new LinkedHashMap<>();
        context.put("workerId", workerId);
        context.put("worker", descriptor.workerProperties());
        context.put("platform", descriptor.platformProperties());
        return ConstraintEvaluator.evaluateMatchRules(context, compiledRule);
    }

    private static boolean validAllocationField(String field) {
        return "workerId".equals(field)
                || field.startsWith("worker.")
                && field.length() > "worker.".length()
                || field.startsWith("platform.")
                && field.length() > "platform.".length();
    }

    private static LinkedHashMap<String, List<WorkerDescriptor>> emptyMatches(
            Map<String, Map<String, Object>> rulesByCandidateId
    ) {
        Objects.requireNonNull(rulesByCandidateId, "rulesByCandidateId");
        LinkedHashMap<String, List<WorkerDescriptor>> result =
                new LinkedHashMap<>();
        rulesByCandidateId.keySet().forEach(id -> result.put(
                id,
                new ArrayList<>()
        ));
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

    private record PreparedCandidate(
            String candidateId,
            Map<String, Map<String, Object>> compiledRule
    ) {
    }
}
