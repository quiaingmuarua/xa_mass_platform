package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex.HeldCandidate;
import com.xa.mass.kernel.score.WorkerScoreCore;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Pacer leases a closed Group batch before Matching qualifies it; unused leases expire. */
final class WorkerEligibilityRefillPolicy {
    static final long CANDIDATE_HOLD_MILLIS = 1_000;
    private static final int GROUP_BUDGET = 100;
    private static final int ROUND_BUDGET = 1_000;

    private final WorkerScoreCore scores;
    private final WorkerCandidateIndex index;
    private final Long hotFloorMillis;
    private final LongSupplier clock;
    private String lastAttemptedGroup;

    WorkerEligibilityRefillPolicy(WorkerScoreCore scores, WorkerCandidateIndex index, Long hotFloorMillis) {
        this(scores, index, hotFloorMillis, System::currentTimeMillis);
    }

    WorkerEligibilityRefillPolicy(WorkerScoreCore scores, WorkerCandidateIndex index,
            Long hotFloorMillis, LongSupplier clock) {
        this.scores = Objects.requireNonNull(scores);
        this.index = Objects.requireNonNull(index);
        this.hotFloorMillis = hotFloorMillis;
        this.clock = Objects.requireNonNull(clock);
    }

    int refill(List<String> rootGroups, Map<String, WorkerCandidateIndex.TaskQuery> tasks) {
        if (rootGroups.size() > 100 || tasks.size() > 100) throw new IllegalArgumentException("at most 100 root coordinates");
        var groups = new ArrayList<>(new LinkedHashSet<>(rootGroups));
        if (!groups.contains(lastAttemptedGroup)) lastAttemptedGroup = null;
        int start = lastAttemptedGroup == null ? 0 : (groups.indexOf(lastAttemptedGroup) + 1) % groups.size();
        var batch = index.prepareRefill(tasks);
        var neededGroups = batch.groupsNeedingRefill();
        int budget = ROUND_BUDGET, admitted = 0;
        for (int n = 0; n < groups.size() && budget > 0; n++) {
            String group = groups.get((start + n) % groups.size());
            if (!neededGroups.contains(group)) continue;
            // Advance on attempts, including empty observations and infrastructure failure.
            lastAttemptedGroup = group;
            budget -= GROUP_BUDGET;
            long started = DispatchStageEvent.start();
            // Acquisition advances the due head, including candidates Matching will reject.
            var observed = scores.observeDueHotScoreCandidates(group, hotFloorMillis, GROUP_BUDGET);
            DispatchStageEvent.batch(started, "REFILL_OBSERVATION", GROUP_BUDGET, observed.size(), false);
            if (observed.isEmpty()) continue;
            long until = Math.addExact(clock.getAsLong(), CANDIDATE_HOLD_MILLIS);
            long acquiredAt = DispatchStageEvent.start();
            List<HeldCandidate> held = List.of();
            boolean failed = true;
            try {
                held = transitioned(observed, scores.acquireObservedHotScoreLeases(group, observed, until), until);
                failed = false;
            } finally {
                DispatchStageEvent.batch(acquiredAt, "INITIAL_HOLD", observed.size(), held.size(), failed);
            }
            if (!held.isEmpty()) admitted += batch.refill(group, held);
        }
        return admitted;
    }

    private static List<HeldCandidate> transitioned(Map<String, Long> expected,
            Map<String, WorkerScoreCore.WorkerScoreTransitionResult> results, long until) {
        var held = new ArrayList<HeldCandidate>();
        expected.forEach((id, score) -> {
            var result = results.get(id);
            if (result != null && result.status() == WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED
                    && result.score() != null && result.score().longValue() != score.longValue()) {
                held.add(new HeldCandidate(id, result.score(), until));
            }
        });
        return List.copyOf(held);
    }

}
