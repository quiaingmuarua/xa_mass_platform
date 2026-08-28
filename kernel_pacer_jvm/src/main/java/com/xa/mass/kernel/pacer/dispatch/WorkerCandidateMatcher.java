package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.pacer.dispatch.WorkerCandidateMechanism.WorkerCandidateObservation;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class WorkerCandidateMatcher {

    private static final System.Logger LOGGER = System.getLogger(
            WorkerCandidateMatcher.class.getName()
    );

    Map<String, List<WorkerCandidateObservation>> match(
            String workerGroupId,
            Map<String, List<WorkerCandidateObservation>> candidates,
            Map<String, WorkerCandidateRequest> requests,
            boolean limitMatches,
            boolean uniqueMatches
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        List<PreparedCandidate> prepared = prepare(requests, workerGroupId);
        LinkedHashMap<String, List<WorkerCandidateObservation>> matched =
                empty(requests);
        Set<String> used = new LinkedHashSet<>();
        for (PreparedCandidate candidate : prepared) {
            LinkedHashMap<String, WorkerCandidateObservation> ordered =
                    new LinkedHashMap<>();
            for (WorkerCandidateObservation worker : candidates.getOrDefault(
                    candidate.candidateId(),
                    List.of()
            )) {
                if (workerGroupId.equals(worker.workerGroupId())) {
                    ordered.putIfAbsent(worker.workerId(), worker);
                }
            }
            for (WorkerCandidateObservation worker : ordered.values()) {
                if (uniqueMatches && used.contains(worker.workerId())) {
                    continue;
                }
                if (!ConstraintEvaluator.evaluateMatchRules(
                        context(worker),
                        candidate.compiledRule()
                )) {
                    continue;
                }
                matched.get(candidate.candidateId()).add(worker);
                if (uniqueMatches) {
                    used.add(worker.workerId());
                }
                if (limitMatches
                        && matched.get(candidate.candidateId()).size()
                        >= candidate.request().requestedCount()) {
                    break;
                }
            }
        }
        return freeze(matched);
    }

    private static List<PreparedCandidate> prepare(
            Map<String, WorkerCandidateRequest> requests,
            String workerGroupId
    ) {
        List<Map.Entry<String, WorkerCandidateRequest>> ordered =
                new ArrayList<>(requests.entrySet());
        ordered.sort(Comparator
                .comparingInt((Map.Entry<String, WorkerCandidateRequest> e)
                        -> e.getValue().priority())
                .thenComparing(Map.Entry::getKey));
        List<PreparedCandidate> candidates = new ArrayList<>();
        int invalid = 0;
        for (Map.Entry<String, WorkerCandidateRequest> entry : ordered) {
            requireNonBlank(entry.getKey(), "candidateId");
            try {
                Map<String, Map<String, Object>> rule =
                        ConstraintEvaluator.compileMatchRules(
                                entry.getValue().allocationRule()
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
                        entry.getValue(),
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

    private static boolean validAllocationField(String field) {
        return "workerId".equals(field)
                || field.startsWith("worker.")
                && field.length() > "worker.".length()
                || field.startsWith("platform.")
                && field.length() > "platform.".length();
    }

    private static Map<String, Object> context(
            WorkerCandidateObservation worker
    ) {
        WorkerDescriptor descriptor = worker.descriptor();
        LinkedHashMap<String, Object> context = new LinkedHashMap<>();
        context.put("workerId", worker.workerId());
        context.put("worker", descriptor.workerProperties());
        context.put("platform", descriptor.platformProperties());
        return context;
    }

    private static LinkedHashMap<String, List<WorkerCandidateObservation>>
            empty(Map<String, WorkerCandidateRequest> requests) {
        LinkedHashMap<String, List<WorkerCandidateObservation>> result =
                new LinkedHashMap<>();
        requests.keySet().forEach(id -> result.put(id, new ArrayList<>()));
        return result;
    }

    private static Map<String, List<WorkerCandidateObservation>> freeze(
            Map<String, List<WorkerCandidateObservation>> source
    ) {
        LinkedHashMap<String, List<WorkerCandidateObservation>> result =
                new LinkedHashMap<>();
        source.forEach((id, workers) -> result.put(id, List.copyOf(workers)));
        return Collections.unmodifiableMap(result);
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }

    private record PreparedCandidate(
            String candidateId,
            WorkerCandidateRequest request,
            Map<String, Map<String, Object>> compiledRule
    ) {
    }
}
