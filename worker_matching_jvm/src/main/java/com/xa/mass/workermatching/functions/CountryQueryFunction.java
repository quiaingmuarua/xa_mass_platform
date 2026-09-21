package com.xa.mass.workermatching.functions;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.QueryFunction;
import com.xa.mass.workermatching.RuleInputs;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;
import java.util.*;

/** Selects countries from the injected in-memory Country Pool. */
public final class CountryQueryFunction implements QueryFunction {
    private final WorkerCandidatePool pool;
    public CountryQueryFunction(WorkerCandidatePool pool) { this.pool = Objects.requireNonNull(pool); }

    @Override public Object normalizeInput(String workerGroupId, Object input) {
        if (input instanceof Map<?, ?> map && map.isEmpty()) return Map.of();
        return RuleInputs.countries(input);
    }

    @Override public Map<String, WorkerCandidate> apply(String group, Map<String, Object> inputs) {
        var keys = new HashMap<Object, Set<String>>();
        for (Object input : inputs.values()) if (!(input instanceof Map<?, ?>)) {
            @SuppressWarnings("unchecked") var countries = (List<String>) input;
            keys.put(input, Set.copyOf(countries));
        }
        return PoolCandidates.take(inputs, (input, limit) -> input instanceof Map<?, ?>
                ? pool.pollAnyBatch(group, limit) : pool.pollBatch(group, keys.get(input), limit));
    }
}
