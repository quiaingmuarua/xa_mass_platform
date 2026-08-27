package com.xa.mass.kernel.pacer;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.CandidateWorkerCache.CandidateWorkerEntry;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
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
    private final WorkerResourceCatalog catalog;

    public WorkerCandidateMatcher(WorkerResourceCatalog catalog) {
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
    }

    public Map<String, List<CandidateWorkerEntry>> matchWorkerCandidates(
            String workerGroupId,
            Map<String, Long> workerLeaseScores,
            Map<String, WorkerCandidateRequest> requests
    ) {
        LinkedHashMap<String, List<String>> ids = new LinkedHashMap<>();
        requests.keySet().forEach(candidateId -> ids.put(
                candidateId,
                List.copyOf(workerLeaseScores.keySet())
        ));
        return matchExplicitWorkerCandidates(
                workerGroupId,
                workerLeaseScores,
                ids,
                requests
        );
    }

    public Map<String, List<String>> filterCandidateWorkerIds(
            String workerGroupId,
            Map<String, List<String>> candidateWorkerIds,
            Map<String, WorkerCandidateRequest> requests
    ) {
        MatchResult result = matchBounded(
                workerGroupId,
                candidateWorkerIds,
                requests,
                false,
                false
        );
        return freezeIds(requests, result.workerIds());
    }

    public Map<String, List<CandidateWorkerEntry>>
            matchExplicitWorkerCandidates(
                    String workerGroupId,
                    Map<String, Long> workerLeaseScores,
                    Map<String, List<String>> candidateWorkerIds,
                    Map<String, WorkerCandidateRequest> requests
            ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        LinkedHashMap<String, List<String>> bounded = new LinkedHashMap<>();
        requests.keySet().forEach(candidateId -> {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (String workerId : candidateWorkerIds.getOrDefault(
                    candidateId,
                    List.of()
            )) {
                if (workerLeaseScores.containsKey(workerId)) {
                    ids.add(workerId);
                }
            }
            bounded.put(candidateId, List.copyOf(ids));
        });
        MatchResult matched = matchBounded(
                workerGroupId,
                bounded,
                requests,
                true,
                true
        );
        LinkedHashMap<String, List<CandidateWorkerEntry>> result =
                emptyAcquisition(requests);
        matched.workerIds().forEach((candidateId, ids) -> {
            List<CandidateWorkerEntry> entries = result.get(candidateId);
            for (String workerId : ids) {
                WorkerDescriptor descriptor = matched.descriptors().get(
                        workerId
                );
                Long leaseScore = workerLeaseScores.get(workerId);
                if (descriptor != null && leaseScore != null) {
                    entries.add(new CandidateWorkerEntry(
                            workerId,
                            workerGroupId,
                            descriptor.endpointManagerId(),
                            leaseScore
                    ));
                }
            }
        });
        return freezeEntries(result);
    }

    private MatchResult matchBounded(
            String workerGroupId,
            Map<String, List<String>> candidateWorkerIds,
            Map<String, WorkerCandidateRequest> requests,
            boolean limitMatches,
            boolean uniqueMatches
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        List<PreparedCandidate> candidates = prepare(requests, workerGroupId);
        LinkedHashMap<String, List<String>> matches = new LinkedHashMap<>();
        requests.keySet().forEach(id -> matches.put(id, new ArrayList<>()));
        if (candidates.isEmpty()) {
            return new MatchResult(matches, Map.of());
        }
        LinkedHashSet<String> allWorkerIds = new LinkedHashSet<>();
        candidates.forEach(candidate -> allWorkerIds.addAll(
                candidateWorkerIds.getOrDefault(
                        candidate.candidateId(),
                        List.of()
                )
        ));
        if (allWorkerIds.isEmpty()) {
            return new MatchResult(matches, Map.of());
        }
        Map<String, WorkerDescriptor> loaded = catalog.getWorkerDescriptors(
                workerGroupId,
                List.copyOf(allWorkerIds)
        );
        LinkedHashMap<String, WorkerDescriptor> descriptors =
                new LinkedHashMap<>();
        loaded.forEach((workerId, descriptor) -> {
            if (descriptor != null
                    && workerGroupId.equals(descriptor.workerGroupId())) {
                descriptors.put(workerId, descriptor);
            }
        });
        Set<String> used = new LinkedHashSet<>();
        for (PreparedCandidate candidate : candidates) {
            LinkedHashSet<String> ordered = new LinkedHashSet<>(
                    candidateWorkerIds.getOrDefault(
                            candidate.candidateId(),
                            List.of()
                    )
            );
            for (String workerId : ordered) {
                if (uniqueMatches && used.contains(workerId)) {
                    continue;
                }
                WorkerDescriptor descriptor = descriptors.get(workerId);
                if (descriptor == null
                        || !ConstraintEvaluator.evaluateMatchRules(
                        context(workerId, descriptor),
                        candidate.compiledRule()
                )) {
                    continue;
                }
                matches.get(candidate.candidateId()).add(workerId);
                if (uniqueMatches) {
                    used.add(workerId);
                }
                if (limitMatches
                        && matches.get(candidate.candidateId()).size()
                        >= candidate.request().requestedCount()) {
                    break;
                }
            }
        }
        return new MatchResult(matches, descriptors);
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
            String workerId,
            WorkerDescriptor descriptor
    ) {
        LinkedHashMap<String, Object> context = new LinkedHashMap<>();
        context.put("workerId", workerId);
        context.put("worker", descriptor.workerProperties());
        context.put("platform", descriptor.platformProperties());
        return context;
    }

    private static LinkedHashMap<String, List<CandidateWorkerEntry>>
            emptyAcquisition(Map<String, WorkerCandidateRequest> requests) {
        LinkedHashMap<String, List<CandidateWorkerEntry>> result =
                new LinkedHashMap<>();
        requests.keySet().forEach(id -> result.put(id, new ArrayList<>()));
        return result;
    }

    private static Map<String, List<CandidateWorkerEntry>> freezeEntries(
            Map<String, List<CandidateWorkerEntry>> source
    ) {
        LinkedHashMap<String, List<CandidateWorkerEntry>> frozen =
                new LinkedHashMap<>();
        source.forEach((id, entries) -> frozen.put(id, List.copyOf(entries)));
        return Collections.unmodifiableMap(frozen);
    }

    private static Map<String, List<String>> freezeIds(
            Map<String, WorkerCandidateRequest> requests,
            Map<String, List<String>> source
    ) {
        LinkedHashMap<String, List<String>> frozen = new LinkedHashMap<>();
        requests.keySet().forEach(id -> frozen.put(
                id,
                List.copyOf(source.getOrDefault(id, List.of()))
        ));
        return Collections.unmodifiableMap(frozen);
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

    private record MatchResult(
            Map<String, List<String>> workerIds,
            Map<String, WorkerDescriptor> descriptors
    ) {
    }
}
