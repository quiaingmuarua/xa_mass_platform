package com.xa.mass.workermatching.rules;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.WorkerMatching.HeldCandidate;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.QueryFunctions;
import com.xa.mass.workermatching.RuleHandler;
import java.util.*;
import org.jspecify.annotations.Nullable;

/** Existing Pool strategy support. Inventory is a separate range resource, never a predicate scan. */
public abstract class PoolRule<P> implements RuleHandler {
    protected final RedisRuleStorage storage;
    private final CandidatePool pool;
    protected enum SelectionKind { ALL, VIEW, IDS }
    protected record Selection(SelectionKind kind, String view, List<String> values) {
        protected Selection { values = List.copyOf(new TreeSet<>(values)); }
        boolean matches(String id, Map<String, String> memberships) {
            return switch (kind) {
                case ALL -> true;
                case IDS -> values.contains(id);
                case VIEW -> memberships.containsKey(view) && values.contains(memberships.get(view));
            };
        }
    }
    protected static Selection all() { return new Selection(SelectionKind.ALL, "", List.of()); }
    protected static Selection range(String view, List<String> values) { return new Selection(SelectionKind.VIEW, view, values); }
    protected static Selection identities(List<String> ids) { return new Selection(SelectionKind.IDS, "", ids); }

    protected PoolRule(RedisRuleStorage storage) {
        this.storage = Objects.requireNonNull(storage);
        pool = new CandidatePool(storage::now, storage.budget);
        storage.addCandidateOwner(pool);
    }
    protected abstract EligibilityQuery normalize(String group, EligibilityQuery query);
    protected abstract Selection target(String group, EligibilityQuery normalized);
    protected abstract Object normalizeLocalInput(String group, Object input);
    protected abstract Selection select(String group, Object normalizedInput);
    /** Reads only offered identities; null qualification may be valid for Default identity admission. */
    protected abstract Map<String, P> readQualifications(String group, List<String> offered);
    /** Null means ineligible; a non-null map assigns at most one bucket per view. */
    protected abstract @Nullable Map<String, String> memberships(String group, String workerId, @Nullable P qualification);

    public final QueryFunctions queryFunctions() { return new QueryFunctions(this::normalizeInput, this::execute); }
    public final Object normalizeInput(String group, Object input) {
        identity(group); return normalizeLocalInput(group, Objects.requireNonNull(input, "input"));
    }
    @Override public final EligibilityQuery normalizeQuery(String group, EligibilityQuery query) {
        identity(group); return normalize(group, Objects.requireNonNull(query, "query"));
    }
    private Map<EligibilityQuery, Selection> targets(String group, Map<EligibilityQuery, Integer> targets) {
        identity(group); Objects.requireNonNull(targets);
        if (targets.size() > 100) throw new IllegalArgumentException("at most 100 targets");
        var result = new LinkedHashMap<EligibilityQuery, Selection>();
        targets.forEach((query, count) -> {
            if (count == null || count < 1 || count > 1000) throw new IllegalArgumentException("target count requires 1..1000");
            result.put(query, target(group, normalizeQuery(group, query)));
        });
        return result;
    }
    private Map<EligibilityQuery, Integer> missing(Map<EligibilityQuery, Selection> selections,
            Map<EligibilityQuery, Integer> targets, CandidatePool.Observation observation) {
        var result = new LinkedHashMap<EligibilityQuery, Integer>();
        selections.forEach((query, selection) -> {
            int requested = selection.kind() == SelectionKind.IDS
                    ? Math.min(targets.get(query), selection.values().size()) : targets.get(query);
            result.put(query, Math.max(0, requested - observation.counts().get(selection)));
        });
        return result;
    }
    @Override public final Map<EligibilityQuery, Integer> deficits(String group, Map<EligibilityQuery, Integer> targets) {
        var selections = targets(group, targets);
        var observation = pool.observe(group, selections.values(), List.of());
        var result = missing(selections, targets, observation);
        if (observation.room() == 0) result.replaceAll((query, count) -> 0);
        return Collections.unmodifiableMap(result);
    }
    @Override public final List<String> refill(String group, Map<EligibilityQuery, Integer> targets,
            List<HeldCandidate> offered, int maxAccepted) {
        var selections = targets(group, targets);
        Objects.requireNonNull(offered);
        if (offered.size() > 100 || maxAccepted < 0 || maxAccepted > 100)
            throw new IllegalArgumentException("at most 100 offers and maxAccepted in 0..100");
        var ids = new LinkedHashSet<String>();
        for (var held : offered) {
            Objects.requireNonNull(held); identity(held.workerId());
            if (!ids.add(held.workerId())) throw new IllegalArgumentException("held identities must be unique");
        }
        if (maxAccepted == 0 || offered.isEmpty() || selections.isEmpty()) return List.of();
        var observation = pool.observe(group, selections.values(), ids);
        if (observation.room() == 0) return List.of();
        var missing = missing(selections, targets, observation);
        if (missing.values().stream().noneMatch(value -> value > 0)) return List.of();
        long now = storage.now();
        var live = offered.stream().filter(held -> held.expiresAtMillis() > now).toList();
        if (live.isEmpty()) return List.of();
        var values = readQualifications(group, live.stream().map(HeldCandidate::workerId).toList());
        if (!new HashSet<>(live.stream().map(HeldCandidate::workerId).toList()).containsAll(values.keySet()))
            throw new IllegalStateException("Rule read an unoffered identity");
        // All fallible business interpretation finishes before the resource commits any entry.
        var prepared = new LinkedHashMap<String, CandidatePool.Admission>();
        for (var held : live) {
            var views = memberships(group, held.workerId(), values.get(held.workerId()));
            if (views != null) prepared.put(held.workerId(), new CandidatePool.Admission(held, views));
        }
        var selected = new LinkedHashMap<String, CandidatePool.Admission>();
        for (boolean any : List.of(false, true)) {
            for (var entry : prepared.entrySet()) {
                if (selected.size() == maxAccepted) break;
                if (selected.containsKey(entry.getKey()) || observation.present().contains(entry.getKey())) continue;
                boolean needed = false;
                for (var query : selections.entrySet()) {
                    if (query.getKey().query().isEmpty() == any && missing.get(query.getKey()) > 0
                            && query.getValue().matches(entry.getKey(), entry.getValue().views())) needed = true;
                }
                if (!needed) continue;
                selected.put(entry.getKey(), entry.getValue());
                selections.forEach((query, selection) -> {
                    if (selection.matches(entry.getKey(), entry.getValue().views())) missing.compute(query, (ignored, count) -> count - 1);
                });
            }
        }
        return pool.admit(group, List.copyOf(selected.values()));
    }

    public final Map<String, WorkerCandidate> execute(String group, Map<String, Object> inputs) {
        identity(group); Objects.requireNonNull(inputs);
        if (inputs.size() > 100) throw new IllegalArgumentException("at most 100 requests");
        var groups = new LinkedHashMap<Selection, List<String>>();
        inputs.forEach((id, input) -> {
            identity(id);
            var selection = select(group, normalizeInput(group, input));
            groups.computeIfAbsent(selection, ignored -> new ArrayList<>()).add(id);
        });
        var limits = new LinkedHashMap<Selection, Integer>();
        groups.forEach((selection, ids) -> limits.put(selection, ids.size()));
        var taken = pool.take(group, limits);
        var assigned = new HashMap<String, WorkerCandidate>();
        groups.forEach((selection, ids) -> {
            var candidates = taken.get(selection);
            for (int i = 0; i < candidates.size(); i++) assigned.put(ids.get(i), candidates.get(i));
        });
        var result = new LinkedHashMap<String, WorkerCandidate>();
        inputs.keySet().forEach(id -> { if (assigned.containsKey(id)) result.put(id, assigned.get(id)); });
        return Collections.unmodifiableMap(result);
    }
    private static void identity(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("non-blank identity required");
    }
}
