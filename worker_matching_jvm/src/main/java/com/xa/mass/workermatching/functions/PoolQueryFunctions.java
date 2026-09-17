package com.xa.mass.workermatching.functions;

import com.xa.mass.workermatching.pool.CandidatePool;
import com.xa.mass.workermatching.RuleInputs;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.QueryFunctions;
import com.xa.mass.workermatching.pool.CandidatePool.Selection;
import java.util.*;
import java.util.function.BiFunction;
import static com.xa.mass.workermatching.pool.CandidatePool.*;

/** Item interpretation over injected stock. Functions neither maintain nor create a Pool. */
public final class PoolQueryFunctions {
    private PoolQueryFunctions() { }
    public static QueryFunctions any(CandidatePool pool) {
        return create(pool, (group, input) -> Collections.unmodifiableMap(RuleInputs.object(input, Set.of())),
                (group, input) -> all());
    }
    public static QueryFunctions country(CandidatePool pool) {
        return create(pool, PoolQueryFunctions::normalizeCountry, PoolQueryFunctions::selectCountry);
    }
    public static QueryFunctions messaging(CandidatePool pool) {
        return create(pool, PoolQueryFunctions::normalizeMessaging, PoolQueryFunctions::selectMessaging);
    }
    public static QueryFunctions proofFacts(CandidatePool pool) {
        return create(pool, PoolQueryFunctions::normalizeProofFacts, PoolQueryFunctions::selectProofFacts);
    }
    public static QueryFunctions create(CandidatePool pool, QueryFunctions.Normalizer normalize,
            BiFunction<String, Object, Selection> select) {
        Objects.requireNonNull(pool); Objects.requireNonNull(normalize); Objects.requireNonNull(select);
        return new QueryFunctions(normalize, (group, inputs) -> execute(pool, normalize, select, group, inputs));
    }
    private static Object normalizeCountry(String group, Object input) {
        if (input instanceof Map<?, ?> map && map.isEmpty()) return Map.of();
        return RuleInputs.countries(input);
    }
    private static Selection selectCountry(String group, Object input) {
        return input instanceof Map<?, ?> ? all() : range("country", RuleInputs.countries(input));
    }

    private static Object normalizeMessaging(String group, Object input) {
        var values = RuleInputs.object(input, Set.of("country", "phone"));
        if (values.containsKey("country")) values.put("country", RuleInputs.countries(values.get("country")));
        if (values.containsKey("phone")) values.put("phone", RuleInputs.text(values.get("phone")));
        return Collections.unmodifiableMap(values);
    }
    private static Selection selectMessaging(String group, Object input) {
        var values = RuleInputs.object(input, Set.of("country", "phone"));
        String partition = values.containsKey("phone") ? "phone:" + values.get("phone") : "";
        if (partition.isEmpty()) return values.containsKey("country")
                ? range("country", RuleInputs.codes(values.get("country"))) : all();
        return values.containsKey("country")
                ? range("country-partition:" + partition, RuleInputs.codes(values.get("country")))
                : range("partition:" + partition, List.of("1"));
    }

    private static Object normalizeProofFacts(String group, Object input) {
        var values = RuleInputs.object(input, Set.of("proofPool", "proofTarget", "proofEnabled", "convergenceSlot"));
        if (values.containsKey("convergenceSlot") && values.size() != 1)
            throw new IllegalArgumentException("convergenceSlot cannot combine with proof fields");
        values.replaceAll((key, value) -> RuleInputs.text(value));
        return Collections.unmodifiableMap(values);
    }
    private static Selection selectProofFacts(String group, Object input) {
        var values = RuleInputs.object(input, Set.of("proofPool", "proofTarget", "proofEnabled", "convergenceSlot"));
        if (values.isEmpty()) return all();
        String partition = values.containsKey("convergenceSlot") ? "slot:" + values.get("convergenceSlot")
                : values.getOrDefault("proofPool", "*") + "|" + values.getOrDefault("proofTarget", "*")
                        + "|" + values.getOrDefault("proofEnabled", "*");
        return range("partition:" + partition, List.of("1"));
    }
    private static Map<String, WorkerCandidate> execute(CandidatePool pool, QueryFunctions.Normalizer normalize,
            BiFunction<String, Object, Selection> select, String group, Map<String, Object> inputs) {
        identity(group); Objects.requireNonNull(inputs);
        if (inputs.size() > 100) throw new IllegalArgumentException("at most 100 requests");
        var groups = new LinkedHashMap<Selection, List<String>>();
        inputs.forEach((id, input) -> {
            identity(id);
            var selection = select.apply(group, normalize.apply(group, input));
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
    private static void identity(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("non-blank identity required");
    }
}
