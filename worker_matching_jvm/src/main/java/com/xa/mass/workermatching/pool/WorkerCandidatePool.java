package com.xa.mass.workermatching.pool;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import java.util.*;
import java.util.function.LongSupplier;

/** Group-isolated queues of immutable candidate entries. No Worker identity or Score interpretation. */
public final class WorkerCandidatePool {
    static final long CANDIDATE_TTL_MILLIS = 60_000;

    private record Entry(WorkerCandidate candidate, long admittedAtMillis) { }
    private static final class Stock {
        final Map<String, ArrayDeque<Entry>> buckets = new LinkedHashMap<>();
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
            stock.buckets.computeIfAbsent(bucketKey, ignored -> new ArrayDeque<>()).addLast(new Entry(candidate, now));
            accepted.add(candidate);
        }
        if (!accepted.isEmpty()) groups.put(group, stock);
        return List.copyOf(accepted);
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
                pollBucket(stock, bucket, limit, now, result);
                if (bucket.isEmpty()) stock.buckets.remove(key);
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
        var buckets = stock.buckets.values().iterator();
        while (buckets.hasNext() && result.size() < limit) {
            var bucket = buckets.next();
            pollBucket(stock, bucket, limit, now, result);
            if (bucket.isEmpty()) buckets.remove();
        }
        if (stock.buckets.isEmpty()) groups.remove(group);
        return List.copyOf(result);
    }

    private void pollBucket(Stock stock, ArrayDeque<Entry> bucket, int limit, long now,
            List<WorkerCandidate> result) {
        int expired = 0, consumed = 0;
        while (!bucket.isEmpty() && result.size() < limit) {
            Entry entry = bucket.removeFirst();
            if (expired(entry, now)) expired++;
            else { result.add(entry.candidate()); consumed++; }
        }
        budget.release(stock, expired, true);
        budget.release(stock, consumed, false);
    }

    /** Resident entry counts, including entries not yet lazily checked for age. */
    public synchronized Map<String, Integer> countByKey(String group) {
        identity(group);
        var counts = new LinkedHashMap<String, Integer>();
        Stock stock = groups.get(group);
        if (stock != null) stock.buckets.forEach((key, bucket) -> counts.put(key, bucket.size()));
        return Collections.unmodifiableMap(counts);
    }

    public synchronized int remainingCapacity(String group) {
        identity(group);
        return budget.room(groups.get(group));
    }

    /** Called by Matching under capacity pressure; no timer or cross-Pool ownership. */
    public synchronized void discardExpired() {
        long now = clock.getAsLong();
        var stocks = groups.values().iterator();
        while (stocks.hasNext()) {
            Stock stock = stocks.next();
            var buckets = stock.buckets.values().iterator();
            int expired = 0;
            while (buckets.hasNext()) {
                var bucket = buckets.next();
                while (!bucket.isEmpty() && expired(bucket.getFirst(), now)) {
                    bucket.removeFirst(); expired++;
                }
                if (bucket.isEmpty()) buckets.remove();
            }
            budget.release(stock, expired, true);
            if (stock.buckets.isEmpty()) stocks.remove();
        }
    }

    private static boolean expired(Entry entry, long now) {
        return now - entry.admittedAtMillis() >= CANDIDATE_TTL_MILLIS;
    }
    private static void identity(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("non-blank identity required");
    }
    private static void limit(int value) {
        if (value < 0) throw new IllegalArgumentException("non-negative limit required");
    }
}
