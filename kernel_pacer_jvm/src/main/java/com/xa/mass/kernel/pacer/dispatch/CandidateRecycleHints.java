package com.xa.mass.kernel.pacer.dispatch;

import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.concurrent.TimeUnit;

/** Run-local lossy evidence, confined to the single-flight refill producer. */
final class CandidateRecycleHints {
    static final long DELAY_MILLIS = 10_000;
    static final int GROUP_CAPACITY = 1_000;
    static final int RUN_CAPACITY = 10_000;
    private record Hint(long fence, long dueNanos) { }
    private final Map<String, LinkedHashMap<String, Hint>> groups = new HashMap<>();
    private final LongSupplier nanoTime;
    private int pending, peak;
    private long accepted, dropped, attempted, recycled, stale, failed, retired;

    CandidateRecycleHints(LongSupplier nanoTime) { this.nanoTime = Objects.requireNonNull(nanoTime); }

    void retainGroups(Collection<String> roots) {
        var active = new HashSet<>(roots);
        groups.entrySet().removeIf(entry -> {
            if (active.contains(entry.getKey())) return false;
            pending -= entry.getValue().size(); retired += entry.getValue().size(); return true;
        });
    }

    void offer(String group, Map<String, Long> supplied, List<String> fullIds) {
        long due = nanoTime.getAsLong() + TimeUnit.MILLISECONDS.toNanos(DELAY_MILLIS);
        var entries = groups.get(group);
        for (String id : fullIds) {
            Long fence = supplied.get(id);
            if (fence == null) throw new IllegalArgumentException("full-deferred identity outside candidate batch");
            Hint previous = entries == null ? null : entries.get(id);
            if (previous != null && previous.fence() == fence) continue;
            if (previous == null && (pending == RUN_CAPACITY || entries != null && entries.size() == GROUP_CAPACITY)) {
                dropped++; continue;
            }
            if (entries == null) { entries = new LinkedHashMap<>(); groups.put(group, entries); }
            if (previous == null) pending++;
            else entries.remove(id);
            entries.put(id, new Hint(fence, due)); accepted++;
            peak = Math.max(peak, pending);
        }
    }

    Map<String, Long> pollDue(String group, int limit) {
        var result = new LinkedHashMap<String, Long>();
        var entries = groups.get(group);
        if (entries == null) return result;
        long now = nanoTime.getAsLong();
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext() && result.size() < limit) {
            var entry = iterator.next();
            if (now - entry.getValue().dueNanos() < 0) break;
            result.put(entry.getKey(), entry.getValue().fence()); iterator.remove(); pending--;
        }
        if (entries.isEmpty()) groups.remove(group);
        attempted += result.size();
        return result;
    }

    void completed(Map<String, Long> due, Map<String, Long> merged, Map<String, WorkerScoreTransitionResult> results) {
        due.forEach((id, fence) -> {
            var result = results.get(id);
            if (Objects.equals(fence, merged.get(id)) && result != null
                    && result.status() == WorkerScoreTransitionStatus.TRANSITIONED) recycled++;
            else stale++;
        });
    }
    void failed(int count) { failed += count; }
    int pending() { return pending; }
    int peak() { return peak; }
    long dropped() { return dropped; }
    void observe() {
        // Keep the failure boundary outside the Event class: JDK 21 instruments that class.
        try { CandidateRecycleEvent.emit(pending, peak, accepted, dropped, attempted, recycled, stale, failed, retired); }
        catch (RuntimeException | LinkageError ignored) { /* Diagnostics cannot affect scheduling. */ }
    }
}
