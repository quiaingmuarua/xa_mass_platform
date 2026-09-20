package com.xa.mass.workermatching.pool;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;

import java.util.*;

/** Local inventory resource. Views partition entries; no business predicates or Redis calls. */
public final class CandidatePool {
    public enum SelectionKind { ALL, VIEW }
    public record Selection(SelectionKind kind, String view, List<String> values) {
        public Selection { values = List.copyOf(new TreeSet<>(values)); }
        public boolean matches(String id, Map<String, String> memberships) {
            return switch (kind) {
                case ALL -> true;
                case VIEW -> memberships.containsKey(view) && values.contains(memberships.get(view));
            };
        }
    }
    public static Selection all() { return new Selection(SelectionKind.ALL, "", List.of()); }
    public static Selection range(String view, List<String> values) { return new Selection(SelectionKind.VIEW, view, values); }

    public record Admission(String workerId, long score, Map<String, String> views) {
        public Admission {
            if (workerId == null || workerId.isBlank() || score == 0) throw new IllegalArgumentException("strict candidate required");
            views = Map.copyOf(views);
        }
    }
    static final class Entry {
        final String workerId;
        final long score, expiresAtMillis;
        final Map<String, String> views;
        final long order;
        Entry(Admission admission, long order, long expiresAtMillis) {
            workerId = admission.workerId(); score = admission.score();
            this.expiresAtMillis = expiresAtMillis; views = admission.views(); this.order = order;
        }
    }
    private record Expiry(long deadline, long order) implements Comparable<Expiry> {
        @Override public int compareTo(Expiry other) {
            int compared = Long.compare(deadline, other.deadline);
            return compared != 0 ? compared : Long.compare(order, other.order);
        }
    }
    private static final class Stock {
        final Map<String, Entry> identities = new HashMap<>();
        final NavigableMap<Long, Entry> all = new TreeMap<>();
        final Map<String, NavigableMap<String, NavigableMap<Long, Entry>>> views = new HashMap<>();
        final NavigableMap<Expiry, Entry> expiry = new TreeMap<>();
        long nextOrder;
    }
    public record Observation(Map<Selection, Integer> counts, Map<String, Long> present, int room) { }
    public record ViewObservation(Map<String, Integer> counts, int total, Map<String, Long> present, int room) { }
    /** One count snapshot of a view, without copying or visiting its entries. */
    public synchronized ViewObservation observeView(String group, String name, Collection<String> ids) {
        expire(group);
        Stock stock = groups.get(group);
        var counts = new LinkedHashMap<String, Integer>();
        if (stock != null) {
            var view = stock.views.get(name);
            if (view != null) view.forEach((value, bucket) -> { countBuckets++; counts.put(value, bucket.size()); });
        }
        var present = new LinkedHashMap<String, Long>();
        if (stock != null) for (String id : ids) if (stock.identities.containsKey(id)) present.put(id, stock.identities.get(id).score);
        return new ViewObservation(Collections.unmodifiableMap(counts), stock == null ? 0 : stock.identities.size(),
                Map.copyOf(present), budget.room(stock));
    }
    record Visits(long countBuckets, long selectedEntries, long expiredEntries) { }

    static final long CANDIDATE_TTL_MILLIS = 60_000;
    private final java.util.function.LongSupplier clock;
    private final CandidateBudget budget;
    private final Map<String, Stock> groups = new HashMap<>();
    private long countBuckets, selectedEntries, expiredEntries;

    public CandidatePool(java.util.function.LongSupplier clock, CandidateBudget budget) {
        this.clock = Objects.requireNonNull(clock);
        this.budget = Objects.requireNonNull(budget);
    }

    public synchronized Observation observe(String group, Collection<Selection> selections, Collection<String> ids) {
        expire(group);
        Stock stock = groups.get(group);
        var counts = new LinkedHashMap<Selection, Integer>();
        for (var selection : selections) counts.put(selection, count(stock, selection));
        var present = new LinkedHashMap<String, Long>();
        if (stock != null) for (String id : ids) if (stock.identities.containsKey(id)) present.put(id, stock.identities.get(id).score);
        return new Observation(Collections.unmodifiableMap(counts), Map.copyOf(present), budget.room(stock));
    }

    private int count(Stock stock, Selection selection) {
        if (stock == null) return 0;
        return switch (selection.kind()) {
            case ALL -> stock.identities.size();
            case VIEW -> {
                int size = 0;
                var view = stock.views.get(selection.view());
                // Each entry has exactly one value in a view, so these buckets are disjoint.
                for (String value : selection.values()) {
                    countBuckets++;
                    var bucket = view == null ? null : view.get(value);
                    if (bucket != null) size += bucket.size();
                }
                yield size;
            }
        };
    }

    public synchronized List<String> admit(String group, List<Admission> selected) {
        expire(group);
        Stock existing = groups.get(group);
        Stock stock = existing == null ? new Stock() : existing;
        long expiresAtMillis = Math.addExact(clock.getAsLong(), CANDIDATE_TTL_MILLIS);
        var accepted = new ArrayList<String>();
        for (Admission admission : selected) {
            String id = admission.workerId();
            Entry previous = stock.identities.get(id);
            if (previous != null && previous.score == admission.score()) continue;
            if (previous == null) {
                if (!budget.acquire(stock)) continue;
            } else {
                removeIndexes(stock, previous);
            }
            Entry entry = new Entry(admission, stock.nextOrder++, expiresAtMillis);
            stock.identities.put(id, entry);
            stock.all.put(entry.order, entry);
            stock.expiry.put(new Expiry(entry.expiresAtMillis, entry.order), entry);
            entry.views.forEach((view, value) -> stock.views.computeIfAbsent(view, ignored -> new TreeMap<>())
                    .computeIfAbsent(value, ignored -> new TreeMap<>()).put(entry.order, entry));
            groups.put(group, stock);
            accepted.add(id);
        }
        return List.copyOf(accepted);
    }

    public Map<Selection, List<WorkerCandidate>> take(String group, Map<Selection, Integer> limits) {
        return commit(group, select(group, limits));
    }

    /** Bounded entry references, not a stock snapshot or executable transaction. */
    synchronized Map<Selection, List<Entry>> select(String group, Map<Selection, Integer> limits) {
        expire(group);
        Stock stock = groups.get(group);
        var selected = new LinkedHashMap<Selection, List<Entry>>();
        var seen = new HashSet<String>();
        for (var request : limits.entrySet()) {
            var rows = new ArrayList<Entry>();
            if (stock != null) {
                var streams = iterators(stock, request.getKey());
                record Head(Entry entry, Iterator<Entry> remaining) { }
                var heads = new PriorityQueue<Head>(Comparator.comparingLong(head -> head.entry().order));
                for (var iterator : streams) if (iterator.hasNext()) {
                    selectedEntries++; heads.add(new Head(iterator.next(), iterator));
                }
                while (!heads.isEmpty() && rows.size() < request.getValue()) {
                    Head head = heads.remove();
                    if (seen.add(head.entry().workerId)) rows.add(head.entry());
                    if (rows.size() < request.getValue() && head.remaining().hasNext()) {
                        selectedEntries++; heads.add(new Head(head.remaining().next(), head.remaining()));
                    }
                }
            }
            selected.put(request.getKey(), List.copyOf(rows));
        }
        return Collections.unmodifiableMap(selected);
    }

    private List<Iterator<Entry>> iterators(Stock stock, Selection selection) {
        var result = new ArrayList<Iterator<Entry>>();
        switch (selection.kind()) {
            case ALL -> result.add(stock.all.values().iterator());
            case VIEW -> {
                var view = stock.views.get(selection.view());
                if (view != null) for (String value : selection.values()) {
                    var bucket = view.get(value);
                    if (bucket != null) result.add(bucket.values().iterator());
                }
            }
        }
        return result;
    }

    synchronized Map<Selection, List<WorkerCandidate>> commit(String group, Map<Selection, List<Entry>> selected) {
        expire(group);
        Stock stock = groups.get(group);
        long now = clock.getAsLong();
        var result = new LinkedHashMap<Selection, List<WorkerCandidate>>();
        for (var row : selected.entrySet()) {
            var committed = new ArrayList<WorkerCandidate>();
            for (Entry entry : row.getValue()) {
                if (stock == null || stock.identities.get(entry.workerId) != entry
                        || entry.expiresAtMillis <= now) continue;
                remove(stock, entry, false);
                committed.add(new WorkerCandidate(entry.workerId, entry.score));
            }
            result.put(row.getKey(), List.copyOf(committed));
        }
        if (stock != null && stock.identities.isEmpty()) groups.remove(group);
        return Collections.unmodifiableMap(result);
    }

    /** A new unqualified generation removes only an older fence, never an equal offer. */
    public synchronized void discardChanged(String group, Map<String, Long> offered, Set<String> qualified) {
        Stock stock = groups.get(group);
        if (stock == null) return;
        offered.forEach((id, score) -> {
            Entry previous = stock.identities.get(id);
            if (previous != null && previous.score != score && !qualified.contains(id)) remove(stock, previous, false);
        });
        if (stock.identities.isEmpty()) groups.remove(group);
    }

    private void remove(Stock stock, Entry entry, boolean expiration) {
        removeIndexes(stock, entry);
        budget.release(stock, 1, expiration);
    }

    private void removeIndexes(Stock stock, Entry entry) {
        stock.identities.remove(entry.workerId); stock.all.remove(entry.order);
        stock.expiry.remove(new Expiry(entry.expiresAtMillis, entry.order));
        entry.views.forEach((name, value) -> {
            var view = stock.views.get(name); var bucket = view.get(value);
            bucket.remove(entry.order);
            if (bucket.isEmpty()) view.remove(value);
            if (view.isEmpty()) stock.views.remove(name);
        });
    }

    private void expire(String group) {
        Stock stock = groups.get(group);
        if (stock == null) return;
        long now = clock.getAsLong();
        while (!stock.expiry.isEmpty() && stock.expiry.firstKey().deadline() <= now) {
            remove(stock, stock.expiry.firstEntry().getValue(), true); expiredEntries++;
        }
        if (stock.identities.isEmpty()) groups.remove(group);
    }

    public synchronized void expireAll() { for (String group : List.copyOf(groups.keySet())) expire(group); }
    synchronized Visits visits() { return new Visits(countBuckets, selectedEntries, expiredEntries); }
    synchronized int viewBuckets(String group) {
        var stock = groups.get(group);
        return stock == null ? 0 : stock.views.values().stream().mapToInt(Map::size).sum();
    }
}
