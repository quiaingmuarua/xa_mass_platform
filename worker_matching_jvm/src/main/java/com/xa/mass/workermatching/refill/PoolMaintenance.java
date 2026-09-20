package com.xa.mass.workermatching.refill;

import com.xa.mass.workermatching.pool.CandidatePool;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.workermatching.pool.CandidatePool.Selection;
import static com.xa.mass.workermatching.pool.CandidatePool.*;
import com.xa.mass.workermatching.PoolRefillPolicy;
import java.util.*;
import org.jspecify.annotations.Nullable;

/** Existing Pool strategy support. Inventory is a separate range resource, never a predicate scan. */
public abstract class PoolMaintenance<P> implements PoolRefillPolicy {
    private final CandidatePool pool;
    protected PoolMaintenance(CandidatePool pool) {
        this.pool = Objects.requireNonNull(pool);
    }
    protected abstract EligibilityQuery normalize(String group, EligibilityQuery query);
    protected abstract Selection target(String group, EligibilityQuery normalized);
    /** Reads only offered identities; null qualification may be valid for unconditional Any admission. */
    protected abstract Map<String, P> readQualifications(String group, List<String> offered);
    /** Null means ineligible; a non-null map assigns at most one bucket per view. */
    protected abstract @Nullable Map<String, String> memberships(String group, String workerId, @Nullable P qualification);

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
            int requested = targets.get(query);
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
            Map<String, Long> offered, int maxAccepted) {
        return refill(group, targets, offered, maxAccepted, null);
    }
    @Override public final List<String> refillRetained(String group, Map<EligibilityQuery, Integer> targets,
            Map<String, RetainedCandidate> offered, int maxAccepted) {
        return refill(group, targets, CandidatePool.retainedScores(offered), maxAccepted, offered);
    }
    private List<String> refill(String group, Map<EligibilityQuery, Integer> targets,
            Map<String, Long> offered, int maxAccepted, Map<String, RetainedCandidate> retained) {
        var selections = targets(group, targets);
        Objects.requireNonNull(offered);
        if (offered.size() > 100 || maxAccepted < 0 || maxAccepted > 100)
            throw new IllegalArgumentException("at most 100 offers and maxAccepted in 0..100");
        offered.forEach((id, score) -> {
            identity(id);
            if (score == null || score == 0) throw new IllegalArgumentException("strict candidate required");
        });
        if (maxAccepted == 0 || offered.isEmpty() || selections.isEmpty()) return List.of();
        var observation = pool.observe(group, selections.values(), offered.keySet());
        if (retained != null && observation.present().keySet().containsAll(offered.keySet())) return List.of();
        var missing = missing(selections, targets, observation);
        boolean replacement = retained == null && offered.entrySet().stream().anyMatch(entry ->
                observation.present().containsKey(entry.getKey()) && !observation.present().get(entry.getKey()).equals(entry.getValue()));
        if (!replacement && (observation.room() == 0 || missing.values().stream().noneMatch(count -> count > 0))) return List.of();
        var values = readQualifications(group, List.copyOf(offered.keySet()));
        if (!offered.keySet().containsAll(values.keySet()))
            throw new IllegalStateException("Pool policy read an unoffered identity");
        // Finish all fallible interpretation before replacing or removing any stock.
        var prepared = new LinkedHashMap<String, CandidatePool.Admission>();
        offered.forEach((id, score) -> {
            var views = memberships(group, id, values.get(id));
            if (views != null && selections.values().stream().anyMatch(selection -> selection.matches(id, views))) {
                prepared.put(id, new CandidatePool.Admission(id, score, views));
            }
        });
        if (retained == null) pool.discardChanged(group, offered, prepared.keySet());
        var selected = new LinkedHashMap<String, CandidatePool.Admission>();
        // Requalification of an existing identity is independent of storage headroom or deficits.
        for (var entry : prepared.entrySet()) {
            if (selected.size() == maxAccepted) break;
            Long old = observation.present().get(entry.getKey());
            if (retained == null && old != null && old.longValue() != entry.getValue().score()) selected.put(entry.getKey(), entry.getValue());
        }
        for (boolean any : List.of(false, true)) {
            for (var entry : prepared.entrySet()) {
                if (selected.size() == maxAccepted) break;
                if (selected.containsKey(entry.getKey()) || observation.present().containsKey(entry.getKey())) continue;
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
        return retained == null ? pool.admit(group, List.copyOf(selected.values()))
                : pool.admitRetained(group, List.copyOf(selected.values()), retained);
    }

    private static void identity(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("non-blank identity required");
    }
}
