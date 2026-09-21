package com.xa.mass.workermatching.refill;

import com.xa.mass.workermatching.pool.CandidatePool;
import com.xa.mass.workermatching.RuleInputs;
import java.util.function.BiFunction;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.workermatching.PoolRefillPolicy;
import java.util.*;

/** Country supply over bounded Facts reads and local counts; no source index or cursor. */
public final class CountryPoolPolicy implements PoolRefillPolicy {
    private final BiFunction<String, List<String>, Map<String, Map<String, Object>>> readFacts;
    private final CandidatePool pool;
    public CountryPoolPolicy(CandidatePool pool,
            BiFunction<String, List<String>, Map<String, Map<String, Object>>> readFacts) {
        this.readFacts = Objects.requireNonNull(readFacts);
        this.pool = Objects.requireNonNull(pool);
    }
    @Override public EligibilityQuery normalizeQuery(String group, EligibilityQuery query) {
        identity(group); Objects.requireNonNull(query, "query");
        if (query.query().isEmpty()) return query;
        if (!query.query().keySet().equals(Set.of("worker.country")))
            throw new IllegalArgumentException("country Pool only accepts worker.country");
        return new EligibilityQuery(Map.of("worker.country", RuleInputs.countries(query.query().get("worker.country"))));
    }
    private Map<EligibilityQuery, List<String>> targets(String group, Map<EligibilityQuery, Integer> targets) {
        identity(group); Objects.requireNonNull(targets, "targets");
        if (targets.size() > 10_000) throw new IllegalArgumentException("at most 10,000 country targets");
        var result = new LinkedHashMap<EligibilityQuery, List<String>>();
        targets.forEach((query, count) -> {
            if (count == null || count < 1 || count > 1000) throw new IllegalArgumentException("target count requires 1..1000");
            result.put(query, normalizeQuery(group, query).query().getOrDefault("worker.country", List.of()));
        });
        return result;
    }
    private Map<EligibilityQuery, Integer> missing(Map<EligibilityQuery, List<String>> countries,
            Map<EligibilityQuery, Integer> targets, CandidatePool.ViewObservation observed) {
        var result = new LinkedHashMap<EligibilityQuery, Integer>();
        countries.forEach((query, values) -> {
            int size = values.isEmpty() ? observed.total()
                    : values.stream().mapToInt(country -> observed.counts().getOrDefault(country, 0)).sum();
            result.put(query, Math.max(0, targets.get(query) - size));
        });
        return result;
    }
    @Override public Map<EligibilityQuery, Integer> deficits(String group, Map<EligibilityQuery, Integer> targets) {
        var countries = targets(group, targets);
        var observed = pool.observeView(group, "country", List.of());
        var result = missing(countries, targets, observed);
        if (observed.room() == 0) result.replaceAll((query, count) -> 0);
        return Collections.unmodifiableMap(result);
    }
    @Override public List<String> refill(String group, Map<EligibilityQuery, Integer> targets,
            Map<String, Long> offered, int maxAccepted) {
        var countries = targets(group, targets);
        Objects.requireNonNull(offered, "offered");
        if (offered.size() > 100 || maxAccepted < 0 || maxAccepted > 100)
            throw new IllegalArgumentException("at most 100 offers and maxAccepted in 0..100");
        offered.forEach((id, score) -> {
            identity(id);
            if (score == null || score == 0) throw new IllegalArgumentException("strict candidate required");
        });
        if (maxAccepted == 0 || offered.isEmpty() || countries.isEmpty()) return List.of();
        var observed = pool.observeView(group, "country", offered.keySet());
        var missing = missing(countries, targets, observed);
        boolean replacement = offered.entrySet().stream().anyMatch(entry ->
                observed.present().containsKey(entry.getKey()) && !observed.present().get(entry.getKey()).equals(entry.getValue()));
        if (!replacement && (observed.room() == 0 || missing.values().stream().noneMatch(count -> count > 0))) return List.of();
        var facts = readFacts.apply(group, List.copyOf(offered.keySet()));
        if (!offered.keySet().containsAll(facts.keySet())) throw new IllegalStateException("unoffered Facts identity");
        var prepared = new LinkedHashMap<String, CandidatePool.Admission>();
        offered.forEach((id, score) -> {
            Object value = facts.getOrDefault(id, Map.of()).get("country");
            if (value instanceof String country && RuleInputs.validCountry(country)
                    && countries.values().stream().anyMatch(target -> target.isEmpty() || target.contains(country))) {
                prepared.put(id, new CandidatePool.Admission(id, score, Map.of("country", country)));
            }
        });
        pool.discardChanged(group, offered, prepared.keySet());
        // Only invocation-local target references, never a second inventory or query cache.
        var affected = new HashMap<String, List<EligibilityQuery>>();
        var anyTargets = new ArrayList<EligibilityQuery>();
        countries.forEach((query, values) -> {
            if (values.isEmpty()) anyTargets.add(query);
            else values.forEach(country -> affected.computeIfAbsent(country, ignored -> new ArrayList<>()).add(query));
        });
        var selected = new LinkedHashMap<String, CandidatePool.Admission>();
        for (var entry : prepared.entrySet()) {
            if (selected.size() == maxAccepted) break;
            Long old = observed.present().get(entry.getKey());
            if (old != null && old.longValue() != entry.getValue().score()) selected.put(entry.getKey(), entry.getValue());
        }
        for (boolean any : List.of(false, true)) {
            for (var entry : prepared.entrySet()) {
                if (selected.size() == maxAccepted) break;
                if (selected.containsKey(entry.getKey()) || observed.present().containsKey(entry.getKey())) continue;
                var countryTargets = affected.getOrDefault(entry.getValue().views().get("country"), List.of());
                var priority = any ? anyTargets : countryTargets;
                if (priority.stream().noneMatch(query -> missing.get(query) > 0)) continue;
                selected.put(entry.getKey(), entry.getValue());
                for (var query : countryTargets) missing.compute(query, (ignored, count) -> count - 1);
                for (var query : anyTargets) missing.compute(query, (ignored, count) -> count - 1);
            }
        }
        return pool.admit(group, List.copyOf(selected.values()));
    }
    private static void identity(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("non-blank identity required");
    }
}
