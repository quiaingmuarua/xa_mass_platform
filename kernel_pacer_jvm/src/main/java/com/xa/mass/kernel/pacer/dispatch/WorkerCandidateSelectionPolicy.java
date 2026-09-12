package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex;
import com.xa.mass.kernel.task.TaskItemWorkerSelector;
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
    private final WorkerResourceCatalog workerCatalog;
    private final Long hotEligibilityFloorMillis;
    private final WorkerCandidateIndex candidateIndex;

    WorkerCandidateSelectionPolicy(
            WorkerScoreCore workerScores,
            WorkerResourceCatalog workerCatalog,
            Long hotEligibilityFloorMillis,
            WorkerCandidateIndex candidateIndex
    ) {
        this.workerScores = Objects.requireNonNull(
                workerScores,
                "workerScores"
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
            long holdUntilMillis,
            Set<String> roundHeldWorkerIds
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
                roundHeldWorkerIds.add(workerId);
            }
        });
        return Collections.unmodifiableMap(held);
    }

    Map<String, WorkerCandidateIndex.TaskQuery> prepareQueries(Map<String, String> taskGroups) {
        return candidateIndex.prepareTaskQueries(taskGroups);
    }

    /** The caller owns the round set; register each confirmed initial hold before any later fallible operation. */
    Map<String, HeldWorkerCandidate> acquireCandidates(
            WorkerCandidateIndex.TaskQuery query,
            String workerGroupId,
            Map<String, TaskItemWorkerSelector> selectorsByMessageId,
            Set<String> roundHeldWorkerIds,
            long leaseUntilMillis
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        Map<String, TaskItemWorkerSelector> selectors = validateSelectors(selectorsByMessageId);
        Objects.requireNonNull(roundHeldWorkerIds, "roundHeldWorkerIds");
        if (query == null) return Map.of();
        selectors.values().forEach(query::validate);

        LinkedHashMap<String, String> selectedByMessageId =
                new LinkedHashMap<>();
        LinkedHashMap<String, Long> selectedScores = new LinkedHashMap<>();
        LinkedHashSet<String> unavailableWorkerIds = new LinkedHashSet<>(
                roundHeldWorkerIds
        );

        // At most 100 Items x 100 explicit targets; select at most 100 holds after one HOT read.
        var explicitTargets = new LinkedHashSet<String>();
        for (var selector : selectors.values()) {
            if (!query.usesIdentitySelection(selector) || !selector.hasExplicitWorkerIds()) continue;
            for (String id : selector.targetWorkerIds()) {
                if (!unavailableWorkerIds.contains(id)) {
                    explicitTargets.add(id);
                }
            }
        }
        Map<String, Long> explicitObserved = explicitTargets.isEmpty() ? Map.of()
                : workerScores.observeDueHotScores(workerGroupId,List.copyOf(explicitTargets),hotEligibilityFloorMillis);
        for (var item : selectors.entrySet()) {
            var selector = item.getValue();
            if (!query.usesIdentitySelection(selector) || !selector.hasExplicitWorkerIds()) continue;
            for (String id : selector.targetWorkerIds()) {
                Long score = explicitObserved.get(id);
                if (score != null && unavailableWorkerIds.add(id)) {
                    selectedByMessageId.put(item.getKey(),id); selectedScores.put(id,score); break;
                }
            }
        }

        LinkedHashMap<String, Long> held = new LinkedHashMap<>(
                holdObservedCandidates(
                        workerGroupId,
                        selectedScores,
                        leaseUntilMillis,
                        roundHeldWorkerIds
                )
        );
        selectedByMessageId.entrySet().removeIf(entry ->
                !held.containsKey(entry.getValue()));
        unavailableWorkerIds.addAll(held.keySet());

        // One batch for the complete indexed subset, preserving actual per-selector demand.
        Map<TaskItemWorkerSelector, List<String>> indexedItems = new LinkedHashMap<>();
        selectors.forEach((messageId, selector) -> {
            if (!query.usesIdentitySelection(selector)) {
                indexedItems.computeIfAbsent(selector, ignored -> new ArrayList<>()).add(messageId);
            }
        });
        if (!indexedItems.isEmpty()) {
            Map<TaskItemWorkerSelector, Integer> limits = new LinkedHashMap<>();
            indexedItems.forEach((selector, messages) -> limits.put(selector, messages.size()));
            Map<TaskItemWorkerSelector, List<String>> candidates = query.take(limits);
            Set<String> ids = new LinkedHashSet<>();
            indexedItems.keySet().forEach(selector -> ids.addAll(candidates.getOrDefault(selector, List.of())));
            ids.removeAll(unavailableWorkerIds);
            Map<String, Long> indexHeld = ids.isEmpty() ? Map.of() : holdObservedCandidates(
                    workerGroupId,
                    workerScores.observeDueHotScores(workerGroupId, List.copyOf(ids), hotEligibilityFloorMillis),
                    leaseUntilMillis,
                    roundHeldWorkerIds
            );
            // Rejected membership still consumes this round's hold; it expires naturally.
            unavailableWorkerIds.addAll(indexHeld.keySet());
            if (!indexHeld.isEmpty()) {
                Map<TaskItemWorkerSelector, List<String>> recheck = new LinkedHashMap<>();
                indexedItems.keySet().forEach(selector -> {
                    List<String> workers = candidates.getOrDefault(selector, List.of()).stream()
                            .filter(indexHeld::containsKey).toList();
                    if (!workers.isEmpty()) recheck.put(selector, workers);
                });
                // Initial hold clears dirty, so membership must be checked afterwards.
                Map<TaskItemWorkerSelector, Set<String>> retained = query.retain(recheck);
                recheck.forEach((selector, workers) -> {
                    int item = 0;
                    for (String workerId : workers) {
                        if (retained.getOrDefault(selector, Set.of()).contains(workerId)
                                && !held.containsKey(workerId)) {
                            selectedByMessageId.put(indexedItems.get(selector).get(item++), workerId);
                            held.put(workerId, indexHeld.get(workerId));
                        }
                    }
                });
            }
        }

        List<String> anyMessageIds = selectors.entrySet().stream()
                .filter(entry -> query.usesIdentitySelection(entry.getValue()) && entry.getValue().isAny())
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
                    leaseUntilMillis,
                    roundHeldWorkerIds
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
                    && workerGroupId.equals(descriptor.workerGroupId())
                    && workerId.equals(descriptor.workerId())) {
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
