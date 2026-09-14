package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex;
import com.xa.mass.kernel.score.WorkerScoreCore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Fixed refill policy over existing HOT observation and exact initial acquisition operations. */
final class WorkerInitialHoldPolicy implements WorkerCandidateIndex.InitialHold {
    static final long INITIAL_HOLD_MILLIS = 5_000;
    private final WorkerScoreCore scores;
    private final Long hotFloorMillis;
    private final LongSupplier clock;

    WorkerInitialHoldPolicy(WorkerScoreCore scores, Long hotFloorMillis) {
        this(scores, hotFloorMillis, System::currentTimeMillis);
    }

    WorkerInitialHoldPolicy(WorkerScoreCore scores, Long hotFloorMillis, LongSupplier clock) {
        this.scores = Objects.requireNonNull(scores);
        this.hotFloorMillis = hotFloorMillis;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public List<WorkerCandidateIndex.HeldCandidate> any(String group, int limit) {
        validateLimit(limit);
        return acquire(group, scores.observeDueHotScoreCandidates(group, hotFloorMillis, limit), limit);
    }

    @Override public List<WorkerCandidateIndex.HeldCandidate> identities(String group, List<String> ids, int limit) {
        validateLimit(limit);
        if (ids.isEmpty() || ids.size() > 100) throw new IllegalArgumentException("hold identities must contain 1..100 IDs");
        return acquire(group, scores.observeDueHotScores(group, ids, hotFloorMillis), limit);
    }

    private List<WorkerCandidateIndex.HeldCandidate> acquire(String group, Map<String,Long> observed, int limit) {
        if (observed.isEmpty()) return List.of();
        var bounded = new LinkedHashMap<String,Long>();
        observed.forEach((id, score) -> { if (bounded.size() < limit) bounded.put(id, score); });
        long expiresAt = Math.addExact(clock.getAsLong(), INITIAL_HOLD_MILLIS);
        long started=DispatchStageEvent.start();
        var acquired = scores.acquireObservedHotScoreLeases(group, bounded, expiresAt);
        var result = new ArrayList<WorkerCandidateIndex.HeldCandidate>();
        bounded.keySet().forEach(id -> {
            var transition = acquired.get(id);
            if (transition != null && transition.status() == WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED
                    && transition.score() != null) {
                result.add(new WorkerCandidateIndex.HeldCandidate(id, transition.score(), expiresAt));
            }
        });
        DispatchStageEvent.batch(started,"INITIAL_HOLD",bounded.size(),result.size(),false);
        return List.copyOf(result);
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("initial hold limit must be in 1..100");
    }
}
