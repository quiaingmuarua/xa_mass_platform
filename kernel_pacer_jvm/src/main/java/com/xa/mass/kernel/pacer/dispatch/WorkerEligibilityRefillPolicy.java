package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.WorkerMatching;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.assignment.RefillTarget;
import java.util.Collections;
import java.util.LinkedHashMap;
import com.xa.mass.kernel.score.WorkerScoreCore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Pacer changes the candidate lane before Matching qualifies a bounded Group batch. */
final class WorkerEligibilityRefillPolicy {
    static final long CANDIDATE_RECYCLE_AFTER_MILLIS = 60_000;
    private static final int RECYCLE_GROUP_BUDGET = 100;
    private static final int ROUND_BUDGET = 1_000;

    private final WorkerScoreCore scores;
    private final WorkerMatching index;
    private final Long hotFloorMillis;
    private final LongSupplier clock;
    private final long recycleAfterMillis;
    private final int assignmentBatchLimit;
    private String lastAttemptedGroup;
    private String lastRecycledGroup;

    WorkerEligibilityRefillPolicy(WorkerScoreCore scores, WorkerMatching index,
            Long hotFloorMillis, int assignmentBatchLimit, LongSupplier clock) {
        this(scores, index, hotFloorMillis, assignmentBatchLimit, CANDIDATE_RECYCLE_AFTER_MILLIS, clock);
    }

    WorkerEligibilityRefillPolicy(WorkerScoreCore scores, WorkerMatching index,
            Long hotFloorMillis, int assignmentBatchLimit, long recycleAfterMillis, LongSupplier clock) {
        if (recycleAfterMillis <= 0) throw new IllegalArgumentException("recycleAfterMillis must be positive");
        this.recycleAfterMillis = recycleAfterMillis;
        this.assignmentBatchLimit = assignmentBatchLimit;
        this.scores = Objects.requireNonNull(scores);
        this.index = Objects.requireNonNull(index);
        this.hotFloorMillis = hotFloorMillis;
        this.clock = Objects.requireNonNull(clock);
    }

    int refill(List<String> rootGroups, List<TaskDescriptor> tasks) {
        var groups = new ArrayList<>(new LinkedHashSet<>(rootGroups));
        if (!groups.contains(lastAttemptedGroup)) lastAttemptedGroup = null;
        if (!groups.contains(lastRecycledGroup)) lastRecycledGroup = null;
        int start = lastAttemptedGroup == null ? 0 : (groups.indexOf(lastAttemptedGroup) + 1) % groups.size();
        var collected = new LinkedHashMap<String, List<RefillTarget>>();
        tasks.forEach(descriptor -> {
            if (!groups.contains(descriptor.workerGroupId())) throw new IllegalArgumentException("Task Group outside root input");
            if (!descriptor.refill().isEmpty())
                collected.computeIfAbsent(descriptor.workerGroupId(), ignored -> new ArrayList<>()).addAll(descriptor.refill());
        });
        var targets = new LinkedHashMap<String, List<RefillTarget>>();
        collected.forEach((group, declarations) -> targets.put(group, List.copyOf(declarations)));
        var deficits = index.observeRefillDeficits(Collections.unmodifiableMap(targets));
        int recycleStart = lastRecycledGroup == null ? 0 : (groups.indexOf(lastRecycledGroup) + 1) % groups.size();
        int budget = ROUND_BUDGET, recycleBudget = ROUND_BUDGET, admitted = 0;
        for (int n = 0; n < groups.size() && (budget > 0 || recycleBudget > 0); n++) {
            // Independent rotations, interleaved so a later failure preserves earlier supply.
            if (recycleBudget > 0) {
                String recycledGroup = groups.get((recycleStart + n) % groups.size());
                lastRecycledGroup = recycledGroup;
                recycleBudget -= RECYCLE_GROUP_BUDGET;
                long cutoff = Math.max(0, clock.getAsLong() - recycleAfterMillis);
                var old = scores.observeHotCandidateScoresBefore(recycledGroup, hotFloorMillis, cutoff, RECYCLE_GROUP_BUDGET);
                if (!old.isEmpty()) scores.recycleObservedHotCandidates(recycledGroup, old);
            }
            if (budget == 0) continue;
            String group = groups.get((start + n) % groups.size());
            int deficit = deficits.getOrDefault(group, 0);
            if (deficit <= 0) continue;
            // Advance on attempts, including empty observations and infrastructure failure.
            lastAttemptedGroup = group;
            int limit = Math.min(Math.min(deficit, assignmentBatchLimit), budget);
            // Charge requested raw rows, even when reads or qualification yield no candidates.
            budget -= limit;
            long started = DispatchStageEvent.start();
            // Lane movement advances the head, including candidates Matching may reject.
            var observed = scores.observeDueHotScoreCandidates(group, hotFloorMillis, limit);
            DispatchStageEvent.batch(started, "REFILL_OBSERVATION", limit, observed.size(), false);
            int added = 0;
            if (!observed.isEmpty()) {
                long acquiredAt = DispatchStageEvent.start();
                Map<String, Long> candidates = Map.of();
                boolean failed = true;
                try {
                    candidates = transitioned(observed, scores.candidateizeObservedHotScores(group, observed));
                    failed = false;
                } finally {
                    DispatchStageEvent.batch(acquiredAt, "CANDIDATEIZE", observed.size(), candidates.size(), failed);
                }
                if (!candidates.isEmpty()) added = index.refill(group, targets.get(group), candidates);
            }
            admitted += added;
        }
        return admitted;
    }

    private static Map<String, Long> transitioned(Map<String, Long> expected,
            Map<String, WorkerScoreCore.WorkerScoreTransitionResult> results) {
        var candidates = new LinkedHashMap<String, Long>();
        expected.forEach((id, score) -> {
            var result = results.get(id);
            if (result != null && result.status() == WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED
                    && result.score() != null && result.score().longValue() != score.longValue()) {
                candidates.put(id, result.score());
            }
        });
        return Collections.unmodifiableMap(candidates);
    }

}
