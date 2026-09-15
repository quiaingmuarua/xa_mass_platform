package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex.HeldCandidate;
import com.xa.mass.kernel.score.WorkerScoreCore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Fixed Pacer observation policy. Matching can acquire only this invocation's accepted subset. */
final class WorkerEligibilityRefillPolicy {
    static final long CANDIDATE_HOLD_MILLIS = 1_000;
    private static final int GROUP_BUDGET = 100;
    private static final int ROUND_BUDGET = 1_000;

    private final WorkerScoreCore scores;
    private final WorkerCandidateIndex index;
    private final Long hotFloorMillis;
    private final LongSupplier clock;
    private final Map<String, Long> groupOffsets = new LinkedHashMap<>();
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
        groupOffsets.keySet().retainAll(groups);
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
            var page = scores.observeDueHotScoreCandidates(group, hotFloorMillis,
                    groupOffsets.getOrDefault(group, 0L), GROUP_BUDGET);
            groupOffsets.put(group, page.nextOffset());
            var observed = page.observedScores();
            DispatchStageEvent.batch(started, "REFILL_OBSERVATION", GROUP_BUDGET, observed.size(), false);
            if (observed.isEmpty()) continue;
            try (var issued = new IssuedBatch(group, observed)) {
                admitted += batch.refill(group, observed, issued);
            }
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

    /** Invocation-local capability; no reservation directory or cross-call lifetime. */
    private final class IssuedBatch implements WorkerCandidateIndex.CandidateLease, AutoCloseable {
        private final String group;
        private final Map<String, Long> original;
        private final Thread caller = Thread.currentThread();
        private boolean open = true;
        private boolean used;

        IssuedBatch(String group, Map<String, Long> observed) {
            this.group = group;
            original = Collections.unmodifiableMap(new LinkedHashMap<>(observed));
        }

        @Override public List<HeldCandidate> acquire(List<String> ids) {
            if (Thread.currentThread() != caller || !open || used) {
                throw new IllegalStateException("acquisition is available once during its issuing refill call");
            }
            Objects.requireNonNull(ids, "acceptedWorkerIds");
            if (ids.isEmpty()) return List.of();
            var unique = new LinkedHashSet<>(ids);
            if (unique.size() != ids.size() || !original.keySet().containsAll(unique)) {
                throw new IllegalArgumentException("acquisition requires a unique subset of the issued batch");
            }
            used = true;
            var expected = new LinkedHashMap<String, Long>();
            ids.forEach(id -> expected.put(id, original.get(id)));
            long until = Math.addExact(clock.getAsLong(), CANDIDATE_HOLD_MILLIS);
            long started = DispatchStageEvent.start();
            var held = transitioned(expected, scores.acquireObservedHotScoreLeases(group, expected, until), until);
            DispatchStageEvent.batch(started, "INITIAL_HOLD", expected.size(), held.size(), false);
            return held;
        }

        @Override public void close() { open = false; }
    }
}
