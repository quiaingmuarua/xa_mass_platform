package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex;
import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import com.xa.mass.kernel.assignment.CandidateWorkerCache.CandidateWorkerEntry;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerDescriptor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Kernel-owned Task Dispatch candidate selection and description. */
final class WorkerCandidateSelectionPolicy {

    private static final int MAX_UNIQUE_WORKERS_PER_ROUND = 100;

    private final WorkerScoreCore workerScores;
    private final CandidateWorkerCache candidateCache;
    private final WorkerResourceCatalog workerCatalog;
    private final Long hotEligibilityFloorMillis;
    private final WorkerCandidateIndex candidateIndex;

    WorkerCandidateSelectionPolicy(
            WorkerScoreCore workerScores,
            CandidateWorkerCache candidateCache,
            WorkerResourceCatalog workerCatalog,
            Long hotEligibilityFloorMillis,
            WorkerCandidateIndex candidateIndex
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
        this.candidateIndex = Objects.requireNonNull(candidateIndex, "candidateIndex");
    }

    private Map<String, Long> observeDueCandidates(
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

    private Map<String, Long> holdObservedCandidates(
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
            String taskId,
            int limit
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        requireNonBlank(taskId, "taskId");
        if (limit < 1 || limit > MAX_UNIQUE_WORKERS_PER_ROUND) {
            throw new IllegalArgumentException(
                    "candidate limit must be in 1.."
                            + MAX_UNIQUE_WORKERS_PER_ROUND
            );
        }
        List<CandidateWorkerEntry> cached =
                candidateCache.consumeCandidateWorkers(taskId, limit);
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

    Map<String, HeldWorkerCandidate> acquireIndexedCandidates(
            String taskId, String workerGroupId,
            Map<String, TaskItemWorkerSelector> selectorsByMessageId,
            Set<String> excludedWorkerIds, long leaseUntilMillis
    ) {
        Map<String, TaskItemWorkerSelector> selectors = validateSelectors(selectorsByMessageId);
        WorkerCandidateIndex.TaskQuery query = candidateIndex.prepareTaskQuery(taskId, workerGroupId);
        if (query == null) return Map.of();
        var limits = new LinkedHashMap<TaskItemWorkerSelector, Integer>();
        selectors.values().forEach(selector -> limits.merge(selector, 1, Integer::sum));
        Map<TaskItemWorkerSelector, List<String>> candidates = query.take(limits);
        var ids = new LinkedHashSet<String>();
        candidates.values().forEach(ids::addAll);
        ids.removeAll(excludedWorkerIds);
        if (ids.isEmpty()) return Map.of();
        Map<String, Long> observed = workerScores.observeDueHotScores(workerGroupId, List.copyOf(ids), hotEligibilityFloorMillis);
        var selectedByMessage = new LinkedHashMap<String, String>();
        var selectedScores = new LinkedHashMap<String, Long>();
        selectors.forEach((message, selector) -> {
            for (String id : candidates.getOrDefault(selector, List.of())) {
                if (ids.contains(id) && observed.get(id) != null && !selectedScores.containsKey(id)) {
                    selectedByMessage.put(message, id);
                    selectedScores.put(id, observed.get(id));
                    break;
                }
            }
        });
        Map<String, Long> held = holdObservedCandidates(workerGroupId, selectedScores, leaseUntilMillis);
        if (held.isEmpty()) return Map.of();
        var byQuery = new LinkedHashMap<TaskItemWorkerSelector, List<String>>();
        selectedByMessage.forEach((message, id) -> {
            if (held.containsKey(id)) byQuery.computeIfAbsent(selectors.get(message), ignored -> new ArrayList<>()).add(id);
        });
        Map<TaskItemWorkerSelector, Set<String>> retained = query.retain(byQuery);
        var usable = new LinkedHashMap<String, Long>();
        selectedByMessage.forEach((message, id) -> {
            if (held.containsKey(id) && retained.getOrDefault(selectors.get(message), Set.of()).contains(id)) {
                usable.put(id, held.get(id));
            }
        });
        Map<String, HeldWorkerCandidate> described = describeById(workerGroupId, usable);
        var result = new LinkedHashMap<String, HeldWorkerCandidate>();
        selectedByMessage.forEach((message, id) -> {
            if (described.containsKey(id)) result.put(message, described.get(id));
        });
        return Collections.unmodifiableMap(result);
    }

    Map<String, HeldWorkerCandidate> acquireOnDemandCandidates(
            String workerGroupId,
            Map<String, TaskItemWorkerSelector> selectorsByMessageId,
            Set<String> excludedWorkerIds,
            long leaseUntilMillis
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        Map<String, TaskItemWorkerSelector> selectors = validateSelectors(selectorsByMessageId);
        Objects.requireNonNull(excludedWorkerIds, "excludedWorkerIds");

        LinkedHashMap<String, String> selectedByMessageId =
                new LinkedHashMap<>();
        LinkedHashMap<String, Long> selectedScores = new LinkedHashMap<>();
        LinkedHashSet<String> unavailableWorkerIds = new LinkedHashSet<>(
                excludedWorkerIds
        );

        for (Map.Entry<String, TaskItemWorkerSelector> item : selectors.entrySet()) {
            if (!item.getValue().hasExplicitWorkerIds()) {
                continue;
            }
            List<String> availableTargets = item.getValue().targetWorkerIds().stream()
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

        // One bounded query per distinct selector, in first Item appearance order.
        Map<TaskItemWorkerSelector, List<String>> indexedItems = new LinkedHashMap<>();
        selectors.forEach((messageId, selector) -> {
            if (!selector.isAny() && !selector.hasExplicitWorkerIds()) {
                indexedItems.computeIfAbsent(selector, ignored -> new ArrayList<>()).add(messageId);
            }
        });
        for (var entry : indexedItems.entrySet()) {
            // The complete input already has at most 100 Items. Its disjoint selector
            // groups therefore fit the same budget without equal-share caps or surplus takes.
            int takeLimit = entry.getValue().size();
            List<String> ids = candidateIndex.takeWorkerIds(workerGroupId, entry.getKey(), takeLimit).stream()
                    .filter(id -> !unavailableWorkerIds.contains(id)).toList();
            if (ids.isEmpty()) continue;
            Map<String, Long> observed = workerScores.observeDueHotScores(workerGroupId, ids, hotEligibilityFloorMillis);
            Map<String, Long> selected = new LinkedHashMap<>();
            for (String id : ids) {
                if (observed.get(id) != null) {
                    selected.put(id, observed.get(id));
                    if (selected.size() == entry.getValue().size()) break;
                }
            }
            Map<String, Long> indexHeld = holdObservedCandidates(workerGroupId, selected, leaseUntilMillis);
            unavailableWorkerIds.addAll(indexHeld.keySet());
            if (indexHeld.isEmpty()) continue;
            // Initial hold clears dirty. Recheck *after* it, without loading Properties.
            Set<String> retained = candidateIndex.retainWorkerIds(workerGroupId, entry.getKey(), List.copyOf(indexHeld.keySet()));
            int item = 0;
            for (var worker : indexHeld.entrySet()) {
                if (retained.contains(worker.getKey())) {
                    selectedByMessageId.put(entry.getValue().get(item++), worker.getKey());
                    held.put(worker.getKey(), worker.getValue());
                }
            }
        }

        List<String> anyMessageIds = selectors.entrySet().stream()
                .filter(entry -> entry.getValue().isAny())
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

    private static Map<String, TaskItemWorkerSelector> validateSelectors(
            Map<String, TaskItemWorkerSelector> source
    ) {
        Objects.requireNonNull(source, "selectorsByMessageId");
        if (source.isEmpty() || source.size() > MAX_UNIQUE_WORKERS_PER_ROUND) {
            throw new IllegalArgumentException("Item selectors must contain 1.."
                    + MAX_UNIQUE_WORKERS_PER_ROUND + " entries");
        }
        var result = new LinkedHashMap<String, TaskItemWorkerSelector>();
        source.forEach((messageId, selector) -> {
            requireNonBlank(messageId, "messageId");
            result.put(messageId, Objects.requireNonNull(selector, "workerSelector"));
        });
        return Collections.unmodifiableMap(result);
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }
}
