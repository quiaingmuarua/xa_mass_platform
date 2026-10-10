package com.xa.mass.workermatching.pool;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import java.util.*;
import java.util.function.LongSupplier;

/** Group-isolated queues of immutable candidate entries. No Worker identity or Score interpretation. */
public final class WorkerCandidatePool {
    static final long CANDIDATE_TTL_MILLIS = 60_000;

    private static final class Entry {
        final WorkerCandidate candidate;
        final long admittedAtMillis;
        final Map<String, Long> availableAt;
        Entry(WorkerCandidate candidate, long admittedAtMillis, Map<String, Long> availableAt) {
            this.candidate = candidate; this.admittedAtMillis = admittedAtMillis; this.availableAt = new LinkedHashMap<>(availableAt);
        }
    }
    private static final class Stock {
        final Map<String, LinkedHashMap<Entry, Entry>> buckets = new LinkedHashMap<>();
    }

    private final Map<String, Stock> groups = new HashMap<>();
    private final LongSupplier clock;
    private final CandidateBudget budget;

    public WorkerCandidatePool(LongSupplier clock, CandidateBudget budget) {
        this.clock = Objects.requireNonNull(clock);
        this.budget = Objects.requireNonNull(budget);
    }

    /** Appends an independent entry for every accepted occurrence, including identical candidates. */
    public synchronized List<WorkerCandidate> offerBatch(String group, String bucketKey,
            List<WorkerCandidate> candidates) {
        identity(group); identity(bucketKey);
        var offered = List.copyOf(candidates);
        for (var candidate : offered) {
            identity(candidate.workerId());
            if (candidate.expectedScore() == 0) throw new IllegalArgumentException("strict candidate required");
        }
        if (offered.isEmpty()) return List.of();
        long now = clock.getAsLong();
        Stock stock = groups.getOrDefault(group, new Stock());
        var accepted = new ArrayList<WorkerCandidate>();
        for (var candidate : offered) {
            if (!budget.acquire(stock)) break;
            Entry entry = new Entry(candidate, now, Map.of(bucketKey, 0L));
            stock.buckets.computeIfAbsent(bucketKey, ignored -> new LinkedHashMap<>()).put(entry, entry);
            accepted.add(candidate);
        }
        if (!accepted.isEmpty()) groups.put(group, stock);
        return List.copyOf(accepted);
    }

    /** Shared time-qualified snapshots. Deferred views are replaceable and do not count as ready stock. */
    public synchronized List<WorkerCandidate> offerSharedBatch(String group,
            Map<WorkerCandidate, Map<String, Long>> candidates, Map<String, Integer> targets) {
        identity(group);
        var captured = new LinkedHashMap<WorkerCandidate, Map<String, Long>>();
        var limits = Map.copyOf(targets);
        candidates.forEach((candidate, availability) -> {
            identity(candidate.workerId());
            if (candidate.expectedScore() == 0) throw new IllegalArgumentException("strict candidate required");
            var values = Collections.unmodifiableMap(new LinkedHashMap<>(availability));
            values.forEach((key, at) -> {
                identity(key);
                if (limits.getOrDefault(key, 0) < 1 || at < 0) throw new IllegalArgumentException("invalid timed bucket");
            });
            captured.put(candidate, values);
        });
        if (captured.values().stream().mapToInt(Map::size).sum() > 1000)
            throw new IllegalArgumentException("at most 1000 shared references per admission");
        discardExpired(group);
        Stock stock = groups.getOrDefault(group, new Stock());
        var admitted = new LinkedHashMap<WorkerCandidate, Entry>();
        long now = clock.getAsLong();
        // Ready references always get first use of the bounded capacity.
        for (boolean readyPass : new boolean[]{true, false}) for (var row : captured.entrySet()) {
            for (var view : row.getValue().entrySet()) {
                boolean ready = view.getValue() <= now;
                if (ready != readyPass) continue;
                String key = view.getKey();
                var bucket = stock.buckets.get(key);
                if (bucket != null && bucket.size() >= limits.get(key)) {
                    if (!ready) continue;
                    // Targets are refill watermarks, not reservations. Keep the qualified
                    // ready batch under the resource budget instead of discarding its fences.
                    replaceDeferred(stock, List.of(key), now);
                }
                if (budget.room(stock) == 0 && (!ready || !replaceDeferred(stock, List.copyOf(stock.buckets.keySet()), now))) continue;
                if (!budget.acquire(stock)) continue;
                Entry entry = admitted.computeIfAbsent(row.getKey(), candidate -> new Entry(candidate, now, Map.of()));
                entry.availableAt.put(key, view.getValue());
                stock.buckets.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).put(entry, entry);
            }
        }
        if (!stock.buckets.isEmpty()) groups.put(group, stock);
        return List.copyOf(admitted.keySet());
    }

    private boolean replaceDeferred(Stock stock, List<String> keys, long now) {
        for (String key : keys) {
            var bucket = stock.buckets.get(key);
            if (bucket == null) continue;
            Entry found = null;
            for (Entry entry : bucket.values()) if (entry.availableAt.get(key) > now) { found = entry; break; }
            if (found == null) continue;
            bucket.remove(found); found.availableAt.remove(key);
            if (bucket.isEmpty()) stock.buckets.remove(key);
            budget.replaceDeferred(stock);
            return true;
        }
        return false;
    }

    /** Deferred references can make room for fresh eligible stock without a Worker/Facts scan. */
    public synchronized int leaseSupplyCapacity(String group) {
        identity(group);
        Stock stock = groups.get(group);
        if (stock == null) return budget.room(null);
        long now = clock.getAsLong();
        int deferred = 0;
        for (var bucket : stock.buckets.entrySet())
            for (Entry entry : bucket.getValue().values()) if (entry.availableAt.get(bucket.getKey()) > now) deferred++;
        return budget.room(stock) + deferred;
    }

    /** Capacity pressure may discard deferred hints; ready stock is preserved. */
    public synchronized void reclaimDeferred(String group) {
        Stock stock = groups.get(group);
        if (stock == null || budget.room(stock) != 0) return;
        long now = clock.getAsLong();
        var keys = List.copyOf(stock.buckets.keySet());
        while (replaceDeferred(stock, keys, now)) { /* At most the resident reference budget. */ }
        if (stock.buckets.isEmpty()) groups.remove(group);
    }

    /** Lexicographic bucket order, FIFO within each bucket; an empty set matches nothing. */
    public synchronized List<WorkerCandidate> pollBatch(String group, Set<String> bucketKeys, int limit) {
        identity(group); limit(limit); Objects.requireNonNull(bucketKeys);
        var keys = new TreeSet<String>();
        for (String key : bucketKeys) { identity(key); keys.add(key); }
        Stock stock = groups.get(group);
        if (limit == 0 || keys.isEmpty() || stock == null) return List.of();
        long now = clock.getAsLong();
        var result = new ArrayList<WorkerCandidate>();
        for (String key : keys) {
            var bucket = stock.buckets.get(key);
            if (bucket != null) {
                pollBucket(stock, key, bucket, limit, now, result);
            }
            if (result.size() == limit) break;
        }
        if (stock.buckets.isEmpty()) groups.remove(group);
        return List.copyOf(result);
    }

    /** Visits buckets in creation order without a global entry order or fairness cursor. */
    public synchronized List<WorkerCandidate> pollAnyBatch(String group, int limit) {
        identity(group); limit(limit);
        Stock stock = groups.get(group);
        if (limit == 0 || stock == null) return List.of();
        long now = clock.getAsLong();
        var result = new ArrayList<WorkerCandidate>();
        for (String key : List.copyOf(stock.buckets.keySet())) {
            var bucket = stock.buckets.get(key);
            if (bucket != null) pollBucket(stock, key, bucket, limit, now, result);
            if (result.size() == limit) break;
        }
        if (stock.buckets.isEmpty()) groups.remove(group);
        return List.copyOf(result);
    }

    private void pollBucket(Stock stock, String key, LinkedHashMap<Entry, Entry> bucket, int limit, long now,
            List<WorkerCandidate> result) {
        while (!bucket.isEmpty() && expired(bucket.firstEntry().getValue(), now)) remove(stock, bucket.firstEntry().getValue(), true);
        var selected = new ArrayList<Entry>();
        for (Entry entry : bucket.values()) {
            if (result.size() + selected.size() == limit) break;
            if (entry.availableAt.get(key) <= now) selected.add(entry);
        }
        for (Entry entry : selected) { remove(stock, entry, false); result.add(entry.candidate); }
    }

    private void remove(Stock stock, Entry entry, boolean expiration) {
        int removed = 0;
        for (String key : entry.availableAt.keySet()) {
            var bucket = stock.buckets.get(key);
            if (bucket != null && bucket.remove(entry) != null) {
                removed++;
                if (bucket.isEmpty()) stock.buckets.remove(key);
            }
        }
        budget.release(stock, removed, expiration);
    }

    /** Resident entry counts, including entries not yet lazily checked for age. */
    public synchronized Map<String, Integer> countByKey(String group) {
        identity(group);
        var counts = new LinkedHashMap<String, Integer>();
        Stock stock = groups.get(group);
        if (stock != null) stock.buckets.forEach((key, bucket) -> counts.put(key, bucket.size()));
        return Collections.unmodifiableMap(counts);
    }

    /** Partitioned pools count live local references before deciding whether a target is full. */
    public synchronized Map<String, Integer> liveCountByKey(String group) {
        discardExpired(group);
        var counts = new LinkedHashMap<String, Integer>();
        Stock stock = groups.get(group);
        long now = clock.getAsLong();
        if (stock != null) stock.buckets.forEach((key, bucket) -> {
            int ready = (int) bucket.values().stream().filter(entry -> entry.availableAt.get(key) <= now).count();
            if (ready > 0) counts.put(key, ready);
        });
        return Collections.unmodifiableMap(counts);
    }

    public synchronized int remainingCapacity(String group) {
        identity(group);
        return budget.room(groups.get(group));
    }

    /** Called by Matching under capacity pressure; no timer or cross-Pool ownership. */
    public synchronized void discardExpired() {
        for (String group : List.copyOf(groups.keySet())) discardExpired(group);
    }

    private void discardExpired(String group) {
        long now = clock.getAsLong();
        Stock stock = groups.get(group);
        if (stock == null) return;
        for (String key : List.copyOf(stock.buckets.keySet())) {
            var bucket = stock.buckets.get(key);
            if (bucket == null) continue;
            while (!bucket.isEmpty() && expired(bucket.firstEntry().getValue(), now)) {
                remove(stock, bucket.firstEntry().getValue(), true);
            }
        }
        if (stock.buckets.isEmpty()) groups.remove(group);
    }

    private static boolean expired(Entry entry, long now) {
        return now - entry.admittedAtMillis >= CANDIDATE_TTL_MILLIS;
    }
    private static void identity(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("non-blank identity required");
    }
    private static void limit(int value) {
        if (value < 0) throw new IllegalArgumentException("non-negative limit required");
    }
}
