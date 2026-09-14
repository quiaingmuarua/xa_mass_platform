package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex.HeldCandidate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Sole local candidate storage. Critical sections contain only bounded in-memory operations. */
final class SharedEligibilityInventory {
    static final int PER_ELIGIBILITY_CAPACITY = 1000;
    static final int PROCESS_CAPACITY = 10_000;
    static final int ELIGIBILITY_CAPACITY = 100;
    record Scope(String workerGroupId, String ruleId) { }
    record Entry(HeldCandidate held, RuleIndex.Projection projection) {
        boolean matches(RuleIndex.Criteria criteria) {
            if (projection != null) return projection.matches(held.workerId(), criteria);
            return criteria.partition().isEmpty() && (criteria.kind().equals("any")
                    || criteria.kind().equals("ids") && criteria.values().contains(held.workerId()));
        }
    }

    private final Map<Scope, LinkedHashMap<String, Entry>> pools = new LinkedHashMap<>();
    private final LongSupplier clock;
    private int size;
    private long held, expired, admitted, consumed, takeRequested, capacityLimited;

    SharedEligibilityInventory() { this(System::currentTimeMillis); }
    SharedEligibilityInventory(LongSupplier clock) { this.clock = clock; }

    synchronized void recordHeld(int count) { held += count; }

    synchronized int count(Scope scope, RuleIndex.Criteria criteria) {
        expire(scope);
        var pool = pools.get(scope);
        return pool == null ? 0 : (int) pool.values().stream().filter(entry -> entry.matches(criteria)).count();
    }

    synchronized int room(Scope scope) {
        // Only deadline cleanup, never invalidation or renewal of another Eligibility's fence.
        for (Scope resident : List.copyOf(pools.keySet())) expire(resident);
        var pool = pools.get(scope);
        int room = pool == null && pools.size() == ELIGIBILITY_CAPACITY ? 0
                : Math.min(PROCESS_CAPACITY - size, PER_ELIGIBILITY_CAPACITY - (pool == null ? 0 : pool.size()));
        if (room == 0) capacityLimited++;
        return room;
    }

    synchronized int add(Scope scope, List<Entry> entries) {
        int room = room(scope), added = 0;
        long now = clock.getAsLong();
        for (Entry entry : entries) {
            if (room == 0) break;
            if (entry.held().expiresAtMillis() <= now) continue;
            var pool = pools.computeIfAbsent(scope, ignored -> new LinkedHashMap<>());
            if (pool.putIfAbsent(entry.held().workerId(), entry) == null) {
                size++; admitted++; added++; room--;
            }
        }
        return added;
    }

    synchronized Map<EligibilityQuery, List<HeldCandidate>> take(
            Scope scope, Map<EligibilityQuery, RuleIndex.Criteria> queries) {
        expire(scope);
        var result = new LinkedHashMap<EligibilityQuery, List<HeldCandidate>>();
        var pool = pools.get(scope);
        queries.forEach((query, criteria) -> {
            takeRequested += query.count();
            var taken = new ArrayList<HeldCandidate>();
            if (pool != null) {
                var iterator = pool.values().iterator();
                while (iterator.hasNext() && taken.size() < query.count()) {
                    Entry entry = iterator.next();
                    if (entry.matches(criteria)) {
                        taken.add(entry.held()); iterator.remove(); size--; consumed++;
                    }
                }
            }
            result.put(query, List.copyOf(taken));
        });
        if (pool != null && pool.isEmpty()) pools.remove(scope);
        return result;
    }

    private void expire(Scope scope) {
        var pool = pools.get(scope);
        if (pool == null) return;
        long now = clock.getAsLong();
        var iterator = pool.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().held().expiresAtMillis() <= now) {
                iterator.remove(); size--; expired++;
            }
        }
        if (pool.isEmpty()) pools.remove(scope);
    }

    synchronized String diagnostics() {
        for (Scope resident : List.copyOf(pools.keySet())) expire(resident);
        return "resident=" + size + " eligibilities=" + pools.size() + " held=" + held + " admitted=" + admitted
                + " takeRequested=" + takeRequested + " consumed=" + consumed
                + " expiredUnused=" + expired + " capacityLimited=" + capacityLimited;
    }
}
