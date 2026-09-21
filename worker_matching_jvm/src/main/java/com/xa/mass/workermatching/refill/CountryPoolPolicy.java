package com.xa.mass.workermatching.refill;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.workermatching.RuleInputs;
import com.xa.mass.workermatching.pool.CandidatePool;
import com.xa.mass.workermatching.pool.CandidatePool.Selection;
import java.util.*;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/** Country supply over bounded Facts reads and one local bucket snapshot; no source index or cursor. */
public final class CountryPoolPolicy extends PoolMaintenance<Map<String, Object>> {
    private final BiFunction<String, List<String>, Map<String, Map<String, Object>>> readFacts;
    public CountryPoolPolicy(CandidatePool pool,
            BiFunction<String, List<String>, Map<String, Map<String, Object>>> readFacts) {
        super(pool); this.readFacts = Objects.requireNonNull(readFacts);
    }
    @Override public TargetBatching targetBatching() { return TargetBatching.ALL; }
    @Override protected EligibilityQuery normalize(String group, EligibilityQuery query) {
        if (query.query().isEmpty()) return query;
        if (!query.query().keySet().equals(Set.of("worker.country")))
            throw new IllegalArgumentException("country Pool only accepts worker.country");
        return new EligibilityQuery(Map.of("worker.country", RuleInputs.countries(query.query().get("worker.country"))));
    }
    @Override protected Selection target(String group, EligibilityQuery query) {
        return query.query().isEmpty() ? CandidatePool.all() : CandidatePool.range("country", query.query().get("worker.country"));
    }
    @Override protected Map<String, Map<String, Object>> readQualifications(String group, List<String> ids) {
        return readFacts.apply(group, ids);
    }
    @Override protected @Nullable Map<String, String> memberships(String group, String id, @Nullable Map<String, Object> facts) {
        return facts != null && facts.get("country") instanceof String country && RuleInputs.validCountry(country)
                ? Map.of("country", country) : null;
    }
    @Override protected CandidatePool.Observation observe(CandidatePool pool, String group,
            Collection<Selection> selections, Collection<String> ids) {
        var observed = pool.observeView(group, "country", ids);
        var counts = new LinkedHashMap<Selection, Integer>();
        for (var selection : selections) counts.put(selection,
                selection.kind() == CandidatePool.SelectionKind.ALL ? observed.total()
                        : selection.values().stream().mapToInt(country -> observed.counts().getOrDefault(country, 0)).sum());
        return new CandidatePool.Observation(counts, observed.present(), observed.room());
    }
    @Override protected Map<String, List<EligibilityQuery>> matchingTargets(Map<EligibilityQuery, Selection> selections,
            Map<String, CandidatePool.Admission> prepared) {
        var byCountry = new HashMap<String, List<EligibilityQuery>>();
        var anyTargets = new ArrayList<EligibilityQuery>();
        selections.forEach((query, selection) -> {
            if (selection.kind() == CandidatePool.SelectionKind.ALL) anyTargets.add(query);
            else selection.values().forEach(country -> byCountry.computeIfAbsent(country, ignored -> new ArrayList<>()).add(query));
        });
        var result = new LinkedHashMap<String, List<EligibilityQuery>>();
        prepared.forEach((id, admission) -> {
            var affected = new ArrayList<>(byCountry.getOrDefault(admission.views().get("country"), List.of()));
            affected.addAll(anyTargets);
            result.put(id, affected);
        });
        return result;
    }
}
