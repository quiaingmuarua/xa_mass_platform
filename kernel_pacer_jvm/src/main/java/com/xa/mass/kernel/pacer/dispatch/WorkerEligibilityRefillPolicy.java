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
    private static final int GROUP_BUDGET = 100;
    private static final int ROUND_BUDGET = 1_000;

    private final WorkerScoreCore scores;
    private final WorkerMatching index;
    private final Long hotFloorMillis;
    private final LongSupplier clock;
    private final long recycleAfterMillis;
    private String lastAttemptedGroup;

    WorkerEligibilityRefillPolicy(WorkerScoreCore scores, WorkerMatching index, Long hotFloorMillis) {
        this(scores, index, hotFloorMillis, System::currentTimeMillis);
    }

    WorkerEligibilityRefillPolicy(WorkerScoreCore scores, WorkerMatching index,
            Long hotFloorMillis, LongSupplier clock) {
        this(scores, index, hotFloorMillis, CANDIDATE_RECYCLE_AFTER_MILLIS, clock);
    }

    WorkerEligibilityRefillPolicy(WorkerScoreCore scores, WorkerMatching index,
            Long hotFloorMillis, long recycleAfterMillis, LongSupplier clock) {
        if (recycleAfterMillis <= 0) throw new IllegalArgumentException("recycleAfterMillis must be positive");
        this.recycleAfterMillis = recycleAfterMillis;
        this.scores = Objects.requireNonNull(scores);
        this.index = Objects.requireNonNull(index);
        this.hotFloorMillis = hotFloorMillis;
        this.clock = Objects.requireNonNull(clock);
    }

    int refill(List<String> rootGroups, List<TaskDescriptor> tasks) {
        if (rootGroups.size() > 100 || tasks.size() > 100) throw new IllegalArgumentException("at most 100 root coordinates");
        var groups = new ArrayList<>(new LinkedHashSet<>(rootGroups));
        if (!groups.contains(lastAttemptedGroup)) lastAttemptedGroup = null;
        int start = lastAttemptedGroup == null ? 0 : (groups.indexOf(lastAttemptedGroup) + 1) % groups.size();
        var collected = new LinkedHashMap<String, List<RefillTarget>>();
        tasks.forEach(descriptor -> {
            if (!groups.contains(descriptor.workerGroupId())) throw new IllegalArgumentException("Task Group outside root input");
            if (!descriptor.refill().isEmpty())
                collected.computeIfAbsent(descriptor.workerGroupId(), ignored -> new ArrayList<>()).addAll(descriptor.refill());
        });
        var targets = new LinkedHashMap<String, List<RefillTarget>>();
        collected.forEach((group, declarations) -> targets.put(group, List.copyOf(declarations)));
        var neededGroups = index.groupsNeedingRefill(Collections.unmodifiableMap(targets));
        int budget = ROUND_BUDGET, recycleBudget = ROUND_BUDGET, admitted = 0;
        for (int n = 0; n < groups.size() && (budget > 0 || recycleBudget > 0); n++) {
            String group = groups.get((start + n) % groups.size());
            boolean refill = budget > 0 && neededGroups.contains(group);
            if (recycleBudget == 0 && !refill) continue;
            // Advance on attempts, including empty observations and infrastructure failure.
            lastAttemptedGroup = group;
            if (recycleBudget > 0) {
                recycleBudget -= GROUP_BUDGET;
                long cutoff = Math.max(0, clock.getAsLong() - recycleAfterMillis);
                var old = scores.observeHotCandidateScoresBefore(group, hotFloorMillis, cutoff, GROUP_BUDGET);
                if (!old.isEmpty()) scores.recycleObservedHotCandidates(group, old);
            }
            if (!refill) continue;
            budget -= GROUP_BUDGET;
            long started = DispatchStageEvent.start();
            // Lane movement advances the head, including candidates Matching may reject.
            var observed = scores.observeDueHotScoreCandidates(group, hotFloorMillis, GROUP_BUDGET);
            DispatchStageEvent.batch(started, "REFILL_OBSERVATION", GROUP_BUDGET, observed.size(), false);
            if (observed.isEmpty()) continue;
            long acquiredAt = DispatchStageEvent.start();
            Map<String, Long> candidates = Map.of();
            boolean failed = true;
            try {
                candidates = transitioned(observed, scores.candidateizeObservedHotScores(group, observed));
                failed = false;
            } finally {
                DispatchStageEvent.batch(acquiredAt, "CANDIDATEIZE", observed.size(), candidates.size(), failed);
            }
            if (!candidates.isEmpty()) admitted += index.refill(group, targets.get(group), candidates);
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
