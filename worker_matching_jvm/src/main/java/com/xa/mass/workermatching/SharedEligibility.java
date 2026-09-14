package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.WorkerCandidateIndex.HeldCandidate;
import com.xa.mass.kernel.assignment.WorkerCandidateIndex.InitialHold;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** One Rule's supply, post-hold projection and local query interpretation are composed together. */
final class SharedEligibility implements EligibilityIndex {
    private final SharedEligibilityInventory inventory;
    private final SharedEligibilityInventory.Scope scope;
    private final @Nullable RuleHandler handler;
    private final @Nullable RuleIndex source;
    private final int refillCursor;

    SharedEligibility(SharedEligibilityInventory inventory, SharedEligibilityInventory.Scope scope,
                      @Nullable RuleHandler handler, @Nullable RuleIndex source, int refillCursor) {
        this.inventory = inventory; this.scope = scope; this.handler = handler; this.source = source;
        this.refillCursor = refillCursor;
    }

    RuleIndex.Criteria criteria(EligibilityQuery query) {
        if (handler != null) return handler.criteria(query.query());
        if (query.query().isEmpty() || query.query().containsKey("workerId")) {
            return RuleHandler.identities(query.query());
        }
        if (source == null) throw new IllegalArgumentException("country index unavailable");
        return RuleHandler.COUNTRY.criteria(query.query());
    }

    private Map<EligibilityQuery, RuleIndex.Criteria> prepare(List<EligibilityQuery> queries) {
        if (queries.size() > 100) throw new IllegalArgumentException("at most 100 queries");
        var result = new LinkedHashMap<EligibilityQuery, RuleIndex.Criteria>();
        queries.forEach(query -> result.put(query, criteria(query)));
        return result;
    }

    @Override public Map<EligibilityQuery, Integer> deficits(List<EligibilityQuery> targets) {
        var result = new LinkedHashMap<EligibilityQuery, Integer>();
        prepare(targets).forEach((query, criteria) -> result.put(query,
                Math.max(0, query.count() - inventory.count(scope, criteria))));
        return result;
    }

    @Override public int refill(List<EligibilityQuery> targets, int budget, InitialHold kernelHold) {
        if (budget < 1 || budget > 100) throw new IllegalArgumentException("refill budget must be in 1..100");
        var queries = new ArrayList<>(prepare(targets).entrySet());
        queries.sort(Comparator.comparing(entry -> entry.getKey().query().isEmpty()));
        var missing = new LinkedHashMap<RuleIndex.Criteria, Integer>();
        for (var entry : queries) missing.merge(entry.getValue(),
                Math.max(0, entry.getKey().count() - inventory.count(scope, entry.getValue())), Math::max);
        if (missing.values().stream().noneMatch(count -> count > 0)) return 0;
        budget = Math.min(budget, inventory.room(scope));
        if (budget == 0) return 0;

        // Share each bounded attempt batch between constrained targets; ANY uses the remainder.
        var limits = new LinkedHashMap<RuleIndex.Criteria, Integer>();
        for (boolean any : List.of(false, true)) {
            boolean progress = true;
            while (progress && limits.values().stream().mapToInt(Integer::intValue).sum() < budget) {
                progress = false;
                for (var entry : queries) {
                    var criteria = entry.getValue();
                    if (entry.getKey().query().isEmpty() != any) continue;
                    int limit = limits.getOrDefault(criteria, 0);
                    if (limit < missing.get(criteria)) {
                        limits.put(criteria, limit + 1); progress = true;
                        if (limits.values().stream().mapToInt(Integer::intValue).sum() == budget) break;
                    }
                }
            }
        }
        var sourceLimits = new LinkedHashMap<RuleIndex.Criteria, Integer>();
        limits.forEach((criteria, limit) -> { if (!identity(criteria)) sourceLimits.put(criteria, limit); });
        // Explicit identity lists also share the 100-identity observation page. Rotate their
        // windows so a permanently occupied prefix cannot hide later compatible identities.
        var explicitIds = new java.util.LinkedHashSet<String>();
        int identityLimit = 0;
        for (var entry : limits.entrySet()) {
            var criteria = entry.getKey();
            if (!identity(criteria) || !criteria.kind().equals("ids")) continue;
            identityLimit += entry.getValue();
            int window = Math.min(criteria.values().size(), Math.max(1,100 / limits.size()));
            int start = Math.floorMod((long)refillCursor * window, criteria.values().size());
            for (int n=0;n<window;n++) explicitIds.add(criteria.values().get((start+n)%criteria.values().size()));
        }
        int sourceBudget = 100 - explicitIds.size();
        while (sourceLimits.values().stream().mapToInt(Integer::intValue).sum() > sourceBudget) {
            var largest = sourceLimits.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
            if (largest.getValue()==1) sourceLimits.remove(largest.getKey());
            else largest.setValue(largest.getValue()-1);
        }
        List<RuleIndex.Member> supplied = sourceLimits.isEmpty() ? List.of() : source.take(sourceLimits);
        var selected = new LinkedHashMap<String, RuleIndex.Projection>();
        // Source projections let overlapping targets share the planned holds, too. They are not
        // authoritative after acquisition: the entire admitted batch is rechecked below.
        for (var member : supplied) {
            if (needed(member.workerId(), member.projection(), missing, selected)) {
                selected.put(member.workerId(), member.projection());
            }
        }
        var ids = new java.util.LinkedHashSet<>(selected.keySet());
        ids.addAll(explicitIds);
        int holdLimit = Math.min(budget, selected.size() + identityLimit);
        var held = new ArrayList<HeldCandidate>();
        if (holdLimit > 0 && !ids.isEmpty()) {
            held.addAll(kernelHold.identities(scope.workerGroupId(), List.copyOf(ids), holdLimit));
        }
        for (var entry : missing.entrySet()) {
            if (identity(entry.getKey()) && entry.getKey().kind().equals("any")) {
                int limit = Math.min(budget - holdLimit, Math.max(0, entry.getValue() - held.size()));
                if (limit > 0) held.addAll(kernelHold.any(scope.workerGroupId(), limit));
            }
        }
        if (held.isEmpty()) return 0;
        inventory.recordHeld(held.size());
        // Acquisition clears dirty, so only this post-hold batch can establish current eligibility.
        Map<String, RuleIndex.Projection> projections = source == null ? Map.of()
                : source.snapshot(held.stream().map(HeldCandidate::workerId).toList());
        var accepted = new ArrayList<SharedEligibilityInventory.Entry>();
        selected.clear();
        for (HeldCandidate candidate : held) {
            var projection = projections.get(candidate.workerId());
            if ((handler == null || projection != null)
                    && needed(candidate.workerId(), projection, missing, selected)) {
                selected.put(candidate.workerId(), projection);
                accepted.add(new SharedEligibilityInventory.Entry(candidate, projection));
            }
        }
        return inventory.add(scope, accepted);
    }

    private boolean identity(RuleIndex.Criteria criteria) {
        return handler == null && criteria.partition().isEmpty() && !criteria.kind().equals("countries");
    }

    private boolean needed(String id, RuleIndex.@Nullable Projection projection,
                           Map<RuleIndex.Criteria, Integer> missing,
                           Map<String, RuleIndex.Projection> selected) {
        if (selected.containsKey(id)) return false;
        for (var entry : missing.entrySet()) {
            var criteria = entry.getKey();
            if (!matches(id, projection, criteria)) continue;
            long planned = selected.entrySet().stream()
                    .filter(member -> matches(member.getKey(), member.getValue(), criteria)).count();
            if (planned < entry.getValue()) return true;
        }
        return false;
    }

    private boolean matches(String id, RuleIndex.@Nullable Projection projection, RuleIndex.Criteria criteria) {
        if (projection != null) return projection.matches(id, criteria);
        return identity(criteria) && (criteria.kind().equals("any") || criteria.values().contains(id));
    }

    @Override public Map<EligibilityQuery, List<HeldCandidate>> take(List<EligibilityQuery> requests) {
        if (requests.stream().mapToInt(EligibilityQuery::count).sum() > 100) {
            throw new IllegalArgumentException("at most 100 candidates per take");
        }
        return inventory.take(scope, prepare(requests));
    }
}
