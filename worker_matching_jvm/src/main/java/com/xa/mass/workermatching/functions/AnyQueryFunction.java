package com.xa.mass.workermatching.functions;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.QueryFunction;
import com.xa.mass.workermatching.RuleInputs;
import com.xa.mass.workermatching.pool.CandidatePool;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Unconditional consumption of the injected Any Pool. */
public final class AnyQueryFunction implements QueryFunction {
    private final CandidatePool pool;

    public AnyQueryFunction(CandidatePool pool) { this.pool = Objects.requireNonNull(pool); }

    @Override public Object normalizeInput(String workerGroupId, Object input) {
        return Collections.unmodifiableMap(RuleInputs.object(input, Set.of()));
    }

    @Override public Map<String, WorkerCandidate> apply(String workerGroupId, Map<String, Object> inputsByMessageId) {
        var selections = new LinkedHashMap<String, CandidatePool.Selection>();
        inputsByMessageId.keySet().forEach(id -> selections.put(id, CandidatePool.all()));
        return PoolCandidates.take(pool, workerGroupId, selections);
    }
}
