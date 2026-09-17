package com.xa.mass.workermatching.functions;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.QueryFunction;
import com.xa.mass.workermatching.RuleInputs;
import com.xa.mass.workermatching.pool.CandidatePool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Selects countries from the injected in-memory Country Pool. */
public final class CountryQueryFunction implements QueryFunction {
    private final CandidatePool pool;

    public CountryQueryFunction(CandidatePool pool) { this.pool = Objects.requireNonNull(pool); }

    @Override public Object normalizeInput(String workerGroupId, Object input) {
        if (input instanceof Map<?, ?> map && map.isEmpty()) return Map.of();
        return RuleInputs.countries(input);
    }

    @Override public Map<String, WorkerCandidate> apply(String workerGroupId, Map<String, Object> inputsByMessageId) {
        var selections = new LinkedHashMap<String, CandidatePool.Selection>();
        inputsByMessageId.forEach((id, input) -> {
            @SuppressWarnings("unchecked")
            var selection = input instanceof Map<?, ?> ? CandidatePool.all()
                    : CandidatePool.range("country", (List<String>) input);
            selections.put(id, selection);
        });
        return PoolCandidates.take(pool, workerGroupId, selections);
    }
}
