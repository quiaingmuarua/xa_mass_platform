package com.xa.mass.workermatching.rules;

import com.xa.mass.kernel.assignment.WorkerMatching.HeldCandidate;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.workermatching.RuleHandler;
import java.util.*;
import java.util.function.BiPredicate;

/**
 * Optional implementation for Rules retaining short-lived local candidates. Each instance owns its
 * pools and qualification values; Catalog never sees either. Other Rules may implement RuleHandler
 * directly. Reads and fallible matching run once outside the state gate. Commit checks only the
 * affected entries, their deadlines and hard capacity; concurrent changes wait for the next round.
 */
public abstract class LocalCandidateRule<P> implements RuleHandler {
    protected final RedisRuleStorage storage;
    private record Entry<P>(HeldCandidate held, P qualification) { }
    private record Stock<P>(List<Entry<P>> entries, int room) { }
    private final Map<String, LinkedHashMap<String, Entry<P>>> pools = new LinkedHashMap<>();

    protected LocalCandidateRule(RedisRuleStorage storage) {
        this.storage = Objects.requireNonNull(storage);
        storage.addCandidateOwner(this);
    }
    protected abstract EligibilityQuery normalize(String group, EligibilityQuery query);
    protected abstract BiPredicate<String, P> predicate(String group, EligibilityQuery target);
    /** Returns immutable qualification values for only the offered IDs. No identity discovery. */
    protected abstract Map<String, P> readQualifications(String group, List<String> offered);

    @Override public final EligibilityQuery normalizeQuery(String group, EligibilityQuery query) {
        group(group);
        return normalize(group, Objects.requireNonNull(query, "query"));
    }
    private Map<EligibilityQuery, BiPredicate<String, P>> targets(String group, Map<EligibilityQuery, Integer> targets) {
        group(group); Objects.requireNonNull(targets);
        if (targets.size() > 100) throw new IllegalArgumentException("at most 100 targets");
        var result = new LinkedHashMap<EligibilityQuery, BiPredicate<String, P>>();
        targets.forEach((query, count) -> {
            if (count == null || count < 1 || count > 1000)
                throw new IllegalArgumentException("target count requires 1..1000");
            result.put(query, predicate(group, normalizeQuery(group, query)));
        });
        return result;
    }
    protected int targetCount(EligibilityQuery query, int count) { return count; }
    private Map<EligibilityQuery, Integer> missing(Map<EligibilityQuery, BiPredicate<String, P>> queries,
            Map<EligibilityQuery, Integer> targets, List<Entry<P>> entries) {
        var result = new LinkedHashMap<EligibilityQuery, Integer>();
        queries.forEach((query, match) -> result.put(query, Math.max(0, targetCount(query, targets.get(query))
                - (int) entries.stream().filter(e -> match.test(e.held().workerId(), e.qualification())).count())));
        return result;
    }
    @Override public final Map<EligibilityQuery, Integer> deficits(String group, Map<EligibilityQuery, Integer> targets) {
        var queries = targets(group, targets);
        var current = snapshot(group);
        var result = missing(queries, targets, current.entries());
        if (current.room() == 0) result.replaceAll((target, count) -> 0);
        return Collections.unmodifiableMap(result);
    }

    @Override public final List<String> refill(String group, Map<EligibilityQuery, Integer> targets,
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
        var missing = missing(queries, targets, current.entries());
        if (missing.values().stream().noneMatch(n -> n > 0)) return List.of();
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
        synchronized (this) {
            expire(group);
            var pool = pools.getOrDefault(group, new LinkedHashMap<>());
            var accepted = new ArrayList<String>();
            long commitTime = storage.now();
            // Targets are observations, not reservations. Only entry admission and capacity commit.
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
                    pools.put(group, pool); accepted.add(held.workerId());
                    for (int q = 0; q < ordered.size(); q++)
                        if (matches.get(n)[q]) missing.computeIfPresent(ordered.get(q), (ignored, count) -> count - 1);
                }
            }
            return List.copyOf(accepted);
        }
    }

    @Override public final Map<EligibilityQuery, List<WorkerCandidate>> take(String group,
            Map<EligibilityQuery, Integer> limits) {
        group(group); Objects.requireNonNull(limits);
        if (limits.size() > 100) throw new IllegalArgumentException("at most 100 selectors");
        long total = 0;
        var queries = new LinkedHashMap<EligibilityQuery, BiPredicate<String, P>>();
        for (var request : limits.entrySet()) {
            int count = Objects.requireNonNull(request.getValue());
            if (count < 1 || count > 100) throw new IllegalArgumentException("take count requires 1..100");
            total += count;
            var target = normalizeQuery(group, request.getKey());
            queries.put(request.getKey(), predicate(group, target));
        }
        if (total > 100) throw new IllegalArgumentException("at most 100 candidates per take");
        var current = snapshot(group);
        var selected = new LinkedHashMap<EligibilityQuery, List<Entry<P>>>();
        var seen = new HashSet<String>();
        queries.forEach((selector, match) -> {
            var entries = new ArrayList<Entry<P>>();
            for (var entry : current.entries()) {
                if (entries.size() == limits.get(selector)) break;
                if (!seen.contains(entry.held().workerId()) && match.test(entry.held().workerId(), entry.qualification())) {
                    seen.add(entry.held().workerId()); entries.add(entry);
                }
            }
            selected.put(selector, entries);
        });
        synchronized (this) {
            expire(group);
            var pool = pools.get(group);
            long commitTime = storage.now();
            var taken = new LinkedHashMap<EligibilityQuery, List<WorkerCandidate>>();
            selected.forEach((selector, entries) -> {
                var committed = new ArrayList<WorkerCandidate>();
                for (var entry : entries) {
                    var held = entry.held();
                    // Reference identity also rejects removal/reinsertion with an equal held value.
                    if (pool == null || pool.get(held.workerId()) != entry || held.expiresAtMillis() <= commitTime) continue;
                    pool.remove(held.workerId()); committed.add(new WorkerCandidate(held.workerId(), held.score()));
                }
                taken.put(selector, List.copyOf(committed));
            });
            if (pool != null) {
                storage.budget.release(pool, taken.values().stream().mapToInt(List::size).sum(), false);
                if (pool.isEmpty()) pools.remove(group);
            }
            return Collections.unmodifiableMap(taken);
        }
    }
    private synchronized Stock<P> snapshot(String group) {
        expire(group);
        var pool = pools.get(group);
        return new Stock<>(pool == null ? List.of() : List.copyOf(pool.values()), storage.budget.room(pool));
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
        if (count > 0) storage.budget.release(pool, count, true);
        if (pool.isEmpty()) pools.remove(group);
    }
    private static void group(String group) {
        if (group == null || group.isBlank()) throw new IllegalArgumentException("non-blank identity required");
    }
}
