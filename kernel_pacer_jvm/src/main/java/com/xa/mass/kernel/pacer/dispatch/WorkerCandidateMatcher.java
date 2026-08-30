package com.xa.mass.kernel.pacer.dispatch;

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
import java.util.Set;

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

    Map<String, List<String>> filterCandidateWorkerIds(
            String workerGroupId,
            Map<String, List<String>> candidateWorkerIds,
            Map<String, WorkerCandidateRequest> requests
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        Objects.requireNonNull(candidateWorkerIds, "candidateWorkerIds");
        List<PreparedCandidate> prepared = prepare(requests, workerGroupId);
        LinkedHashMap<String, List<String>> filtered = emptyIds(requests);
        Map<String, WorkerDescriptor> descriptors = loadDescriptors(
                workerGroupId,
                boundedWorkerIds(prepared, candidateWorkerIds)
        );
        for (PreparedCandidate candidate : prepared) {
            LinkedHashSet<String> ordered = new LinkedHashSet<>(
                    candidateWorkerIds.getOrDefault(
                            candidate.candidateId(),
                            List.of()
                    )
            );
            List<String> matches = filtered.get(candidate.candidateId());
            for (String workerId : ordered) {
                WorkerDescriptor descriptor = descriptors.get(workerId);
                if (descriptor != null && matches(
                        workerId,
                        descriptor,
                        candidate.compiledRule()
                )) {
                    matches.add(workerId);
                }
            }
        }
        return freezeIds(filtered);
    }

    Map<String, List<AcquiredWorkerCandidate>> matchLeasedWorkerCandidates(
            String workerGroupId,
            Map<String, Long> leasedWorkers,
            Map<String, List<String>> selectedWorkerIds,
            Map<String, WorkerCandidateRequest> requests
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        Map<String, Long> leased = validateScores(leasedWorkers);
        Objects.requireNonNull(selectedWorkerIds, "selectedWorkerIds");
        List<PreparedCandidate> prepared = prepare(requests, workerGroupId);
        LinkedHashMap<String, List<AcquiredWorkerCandidate>> matched =
                emptyCandidates(requests);
        Map<String, WorkerDescriptor> descriptors = loadDescriptors(
                workerGroupId,
                boundedLeasedWorkerIds(
                        prepared,
                        selectedWorkerIds,
                        leased.keySet()
                )
        );
        Set<String> used = new LinkedHashSet<>();
        for (PreparedCandidate candidate : prepared) {
            LinkedHashSet<String> ordered = new LinkedHashSet<>(
                    selectedWorkerIds.getOrDefault(
                            candidate.candidateId(),
                            List.of()
                    )
            );
            List<AcquiredWorkerCandidate> matches = matched.get(
                    candidate.candidateId()
            );
            for (String workerId : ordered) {
                if (used.contains(workerId)) {
                    continue;
                }
                Long workerLeaseScore = leased.get(workerId);
                WorkerDescriptor descriptor = descriptors.get(workerId);
                if (workerLeaseScore == null
                        || descriptor == null
                        || !matches(
                                workerId,
                                descriptor,
                                candidate.compiledRule()
                        )) {
                    continue;
                }
                matches.add(new AcquiredWorkerCandidate(
                        workerId,
                        workerGroupId,
                        descriptor.endpointManagerId(),
                        workerLeaseScore
                ));
                used.add(workerId);
                if (matches.size()
                        >= candidate.request().requestedCount()) {
                    break;
                }
            }
        }
        return freezeCandidates(matched);
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

    private static List<String> boundedWorkerIds(
            List<PreparedCandidate> candidates,
            Map<String, List<String>> candidateWorkerIds
    ) {
        LinkedHashSet<String> workerIds = new LinkedHashSet<>();
        for (PreparedCandidate candidate : candidates) {
            for (String workerId : candidateWorkerIds.getOrDefault(
                    candidate.candidateId(),
                    List.of()
            )) {
                requireNonBlank(workerId, "workerId");
                workerIds.add(workerId);
            }
        }
        return List.copyOf(workerIds);
    }

    private static List<String> boundedLeasedWorkerIds(
            List<PreparedCandidate> candidates,
            Map<String, List<String>> selectedWorkerIds,
            Set<String> leasedWorkerIds
    ) {
        LinkedHashSet<String> workerIds = new LinkedHashSet<>();
        for (PreparedCandidate candidate : candidates) {
            for (String workerId : selectedWorkerIds.getOrDefault(
                    candidate.candidateId(),
                    List.of()
            )) {
                requireNonBlank(workerId, "workerId");
                if (leasedWorkerIds.contains(workerId)) {
                    workerIds.add(workerId);
                }
            }
        }
        return List.copyOf(workerIds);
    }

    private static Map<String, Long> validateScores(
            Map<String, Long> source
    ) {
        Objects.requireNonNull(source, "leasedWorkers");
        LinkedHashMap<String, Long> scores =
                new LinkedHashMap<>();
        source.forEach((workerId, score) -> {
            requireNonBlank(workerId, "workerId");
            scores.put(workerId, Objects.requireNonNull(score, "score"));
        });
        return Collections.unmodifiableMap(scores);
    }

    private static List<PreparedCandidate> prepare(
            Map<String, WorkerCandidateRequest> requests,
            String workerGroupId
    ) {
        Objects.requireNonNull(requests, "requests");
        List<Map.Entry<String, WorkerCandidateRequest>> ordered =
                new ArrayList<>(requests.entrySet());
        ordered.sort(Comparator
                .comparingInt((Map.Entry<String, WorkerCandidateRequest> e)
                        -> Objects.requireNonNull(
                                e.getValue(),
                                "request"
                        ).priority())
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

    private static LinkedHashMap<String, List<String>> emptyIds(
            Map<String, WorkerCandidateRequest> requests
    ) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        requests.keySet().forEach(id -> result.put(id, new ArrayList<>()));
        return result;
    }

    private static LinkedHashMap<String, List<AcquiredWorkerCandidate>>
            emptyCandidates(Map<String, WorkerCandidateRequest> requests) {
        LinkedHashMap<String, List<AcquiredWorkerCandidate>> result =
                new LinkedHashMap<>();
        requests.keySet().forEach(id -> result.put(id, new ArrayList<>()));
        return result;
    }

    private static Map<String, List<String>> freezeIds(
            Map<String, List<String>> source
    ) {
        LinkedHashMap<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((id, workers) -> result.put(id, List.copyOf(workers)));
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, List<AcquiredWorkerCandidate>>
            freezeCandidates(
                    Map<String, List<AcquiredWorkerCandidate>> source
            ) {
        LinkedHashMap<String, List<AcquiredWorkerCandidate>> result =
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
