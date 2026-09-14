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

/** Fixed Pacer supply policy. Matching can renew only the batch issued by this invocation. */
final class WorkerEligibilityRefillPolicy {
    static final long HANDOFF_HOLD_MILLIS = 1_000;
    static final long INVENTORY_HOLD_MILLIS = 5_000;
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
        Map<String, Integer> deficits = index.deficits(tasks);
        int budget = ROUND_BUDGET, admitted = 0;
        for (int n = 0; n < groups.size() && budget > 0; n++) {
            String group = groups.get((start + n) % groups.size());
            if (deficits.getOrDefault(group, 0) <= 0) continue;
            // Advance on attempts, including empty observations and infrastructure failure.
            lastAttemptedGroup = group;
            budget -= GROUP_BUDGET;
            var observed = scores.observeDueHotScoreCandidates(group, hotFloorMillis, GROUP_BUDGET);
            if (observed.isEmpty()) continue;
            if (observed.size() > GROUP_BUDGET) throw new IllegalStateException("HOT observation exceeded its budget");
            long until = Math.addExact(clock.getAsLong(), HANDOFF_HOLD_MILLIS);
            long started = DispatchStageEvent.start();
            var held = transitioned(observed, scores.acquireObservedHotScoreLeases(group, observed, until), until);
            DispatchStageEvent.batch(started, "INITIAL_HOLD", observed.size(), held.size(), false);
            if (held.isEmpty()) continue;
            try (var issued = new IssuedBatch(group, held)) {
                admitted += index.refill(tasks, group, held, issued);
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
    private final class IssuedBatch implements WorkerCandidateIndex.CandidateRenewal, AutoCloseable {
        private final String group;
        private final Map<String, Long> original;
        private final Thread caller = Thread.currentThread();
        private boolean open = true;
        private boolean used;

        IssuedBatch(String group, List<HeldCandidate> offered) {
            this.group = group;
            var fences = new LinkedHashMap<String, Long>();
            offered.forEach(candidate -> fences.put(candidate.workerId(), candidate.score()));
            original = Collections.unmodifiableMap(fences);
        }

        @Override public List<HeldCandidate> renew(List<String> ids) {
            if (Thread.currentThread() != caller || !open || used) {
                throw new IllegalStateException("renewal is available once during its issuing refill call");
            }
            Objects.requireNonNull(ids, "acceptedWorkerIds");
            if (ids.isEmpty()) return List.of();
            var unique = new LinkedHashSet<>(ids);
            if (unique.size() != ids.size() || !original.keySet().containsAll(unique)) {
                throw new IllegalArgumentException("renewal requires a unique subset of the issued batch");
            }
            used = true;
            var expected = new LinkedHashMap<String, Long>();
            ids.forEach(id -> expected.put(id, original.get(id)));
            long until = Math.addExact(clock.getAsLong(), INVENTORY_HOLD_MILLIS);
            long started = DispatchStageEvent.start();
            var renewed = transitioned(expected, scores.extendActiveHotScoreLeases(group, expected, until), until);
            DispatchStageEvent.batch(started, "INVENTORY_HOLD", expected.size(), renewed.size(), false);
            return renewed;
        }

        @Override public void close() { open = false; }
    }
}
