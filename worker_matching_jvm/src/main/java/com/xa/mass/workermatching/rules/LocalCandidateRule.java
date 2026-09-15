package com.xa.mass.workermatching.rules;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex.HeldCandidate;
import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import com.xa.mass.workermatching.EligibilityQuery;
import com.xa.mass.workermatching.RuleHandler;
import java.util.*;
import java.util.function.BiPredicate;

/**
 * Optional implementation for Rules retaining short-lived local candidates. Each instance owns its
 * pools and qualification values; Catalog never sees either. Other Rules may implement RuleHandler
 * directly. Redis reads and fallible matching run outside the state gate, before a versioned commit.
 */
public abstract class LocalCandidateRule<P> implements RuleHandler {
    protected final RedisRuleStorage storage;
    private record Entry<P>(HeldCandidate held, P qualification) { }
    private record Stock<P>(long revision, List<Entry<P>> entries, int room) { }
    private final Map<String, LinkedHashMap<String, Entry<P>>> pools = new LinkedHashMap<>();
    private long revision;

    protected LocalCandidateRule(RedisRuleStorage storage) {
        this.storage = Objects.requireNonNull(storage);
        storage.addCandidateOwner(this);
    }
    protected abstract EligibilityQuery normalize(String group, Map<String, ?> expression, int count, boolean selector);
    protected abstract BiPredicate<String, P> predicate(String group, EligibilityQuery target);
    /** Returns immutable qualification values for only the offered IDs. No identity discovery. */
    protected abstract Map<String, P> readQualifications(String group, List<String> offered);

    @Override public final EligibilityQuery normalizeTarget(String group, EligibilityQuery target) {
        group(group); Objects.requireNonNull(target, "target");
        return normalize(group, target.query(), target.count(), false);
    }
    @Override public final void validateSelector(String group, TaskItemWorkerSelector selector) {
        group(group); normalize(group, Objects.requireNonNull(selector).expression(), 1, true);
    }
    private Map<EligibilityQuery, BiPredicate<String, P>> targets(String group, List<EligibilityQuery> targets) {
        group(group); Objects.requireNonNull(targets);
        if (targets.size() > 100) throw new IllegalArgumentException("at most 100 targets");
        var result = new LinkedHashMap<EligibilityQuery, BiPredicate<String, P>>();
        for (var target : targets) result.put(target, predicate(group, normalizeTarget(group, target)));
        return result;
    }
    protected int targetCount(EligibilityQuery target) { return target.count(); }
    private Map<EligibilityQuery, Integer> missing(Map<EligibilityQuery, BiPredicate<String, P>> queries, List<Entry<P>> entries) {
        var result = new LinkedHashMap<EligibilityQuery, Integer>();
        queries.forEach((query, match) -> result.put(query, Math.max(0, targetCount(query)
                - (int) entries.stream().filter(e -> match.test(e.held().workerId(), e.qualification())).count())));
        return result;
    }
    @Override public final Map<EligibilityQuery, Integer> deficits(String group, List<EligibilityQuery> targets) {
        var queries = targets(group, targets);
        var current = snapshot(group);
        var result = missing(queries, current.entries());
        if (current.room() == 0) result.replaceAll((target, count) -> 0);
        return Collections.unmodifiableMap(result);
    }

    @Override public final List<String> refill(String group, List<EligibilityQuery> targets,
            List<HeldCandidate> offered, int maxAccepted) {
        var queries = targets(group, targets);
        Objects.requireNonNull(offered);
        if (offered.size() > 100 || maxAccepted < 0 || maxAccepted > 100)
            throw new IllegalArgumentException("refill requires at most 100 offers and maxAccepted in 0..100");
        var ids = new LinkedHashSet<String>();
        for (var held : offered) {
            Objects.requireNonNull(held); group(held.workerId());
            if (!ids.add(held.workerId())) throw new IllegalArgumentException("held Worker IDs must be unique");
        }
        if (maxAccepted == 0 || offered.isEmpty() || queries.isEmpty()) return List.of();
        var current = snapshot(group);
        if (current.room() == 0) return List.of();
        if (missing(queries, current.entries()).values().stream().noneMatch(n -> n > 0)) return List.of();
        long now = storage.now();
        var live = offered.stream().filter(h -> h.expiresAtMillis() > now).toList();
        if (live.isEmpty()) return List.of();
        var values = readQualifications(group, live.stream().map(HeldCandidate::workerId).toList());
        if (!new HashSet<>(live.stream().map(HeldCandidate::workerId).toList()).containsAll(values.keySet()))
            throw new IllegalStateException("Rule read an unoffered identity");
        var ordered = List.copyOf(queries.keySet());
        // Evaluate every potentially failing match before any admission by this Rule.
        var matches = new ArrayList<boolean[]>();
        for (var held : live) {
            var row = new boolean[ordered.size()];
            for (int i = 0; i < row.length; i++)
                row[i] = queries.get(ordered.get(i)).test(held.workerId(), values.get(held.workerId()));
            matches.add(row);
        }
        while (true) {
            current = snapshot(group);
            var missing = missing(queries, current.entries());
            synchronized (this) {
                expire(group);
                if (revision != current.revision()) continue;
                var pool = pools.getOrDefault(group, new LinkedHashMap<>());
                var accepted = new ArrayList<String>();
                long commitTime = storage.now();
                // Constrained targets get their offered candidates before ANY consumes the budget.
                for (boolean any : List.of(false, true)) {
                    for (int n = 0; n < live.size() && accepted.size() < maxAccepted; n++) {
                        var held = live.get(n);
                        if (held.expiresAtMillis() <= commitTime || pool.containsKey(held.workerId())) continue;
                        boolean needed = false;
                        for (int q = 0; q < ordered.size(); q++)
                            if (ordered.get(q).query().isEmpty() == any && matches.get(n)[q] && missing.get(ordered.get(q)) > 0)
                                needed = true;
                        if (!needed) continue;
                        if (!storage.budget.acquire(pool)) break;
                        pool.put(held.workerId(), new Entry<>(held, values.get(held.workerId())));
                        pools.put(group, pool); revision++; accepted.add(held.workerId());
                        for (int q = 0; q < ordered.size(); q++)
                            if (matches.get(n)[q]) missing.computeIfPresent(ordered.get(q), (ignored, count) -> count - 1);
                    }
                }
                return List.copyOf(accepted);
            }
        }
    }

    @Override public final Map<TaskItemWorkerSelector, List<HeldCandidate>> take(String group,
            Map<TaskItemWorkerSelector, Integer> limits) {
        group(group); Objects.requireNonNull(limits);
        if (limits.size() > 100) throw new IllegalArgumentException("at most 100 selectors");
        long total = 0;
        var queries = new LinkedHashMap<TaskItemWorkerSelector, BiPredicate<String, P>>();
        for (var request : limits.entrySet()) {
            int count = Objects.requireNonNull(request.getValue());
            if (count < 1 || count > 100) throw new IllegalArgumentException("take count requires 1..100");
            total += count;
            var target = normalize(group, Objects.requireNonNull(request.getKey()).expression(), count, true);
            queries.put(request.getKey(), predicate(group, target));
        }
        if (total > 100) throw new IllegalArgumentException("at most 100 candidates per take");
        while (true) {
            var current = snapshot(group);
            var selected = new LinkedHashMap<TaskItemWorkerSelector, List<HeldCandidate>>();
            var seen = new HashSet<String>();
            queries.forEach((selector, match) -> {
                var entries = new ArrayList<HeldCandidate>();
                for (var entry : current.entries()) {
                    if (entries.size() == limits.get(selector)) break;
                    if (!seen.contains(entry.held().workerId()) && match.test(entry.held().workerId(), entry.qualification())) {
                        seen.add(entry.held().workerId()); entries.add(entry.held());
                    }
                }
                selected.put(selector, List.copyOf(entries));
            });
            synchronized (this) {
                expire(group);
                if (revision != current.revision()) continue;
                var pool = pools.get(group);
                if (pool != null && !seen.isEmpty()) {
                    seen.forEach(pool::remove); storage.budget.release(pool, seen.size(), false); revision++;
                    if (pool.isEmpty()) pools.remove(group);
                }
                return Collections.unmodifiableMap(selected);
            }
        }
    }
    private synchronized Stock<P> snapshot(String group) {
        expire(group);
        var pool = pools.get(group);
        return new Stock<>(revision, pool == null ? List.of() : List.copyOf(pool.values()), storage.budget.room(pool));
    }
    final synchronized void expireAll() {
        for (String group : List.copyOf(pools.keySet())) expire(group);
    }
    private void expire(String group) {
        var pool = pools.get(group);
        if (pool == null) return;
        long now = storage.now();
        int before = pool.size();
        pool.values().removeIf(e -> e.held().expiresAtMillis() <= now);
        int count = before - pool.size();
        if (count > 0) { storage.budget.release(pool, count, true); revision++; }
        if (pool.isEmpty()) pools.remove(group);
    }
    private static void group(String group) {
        if (group == null || group.isBlank()) throw new IllegalArgumentException("non-blank identity required");
    }
}
