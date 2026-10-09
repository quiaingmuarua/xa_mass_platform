package com.xa.mass.workermatching.functions;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.QueryFunction;
import com.xa.mass.workermatching.RuleInputs;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;
import java.util.*;

/** Empty-input consumption of the injected Pool's admitted stock; no qualification or fallback. */
public final class EmptyInputPoolQueryFunction implements QueryFunction {
    private final WorkerCandidatePool pool;
    public EmptyInputPoolQueryFunction(WorkerCandidatePool pool) { this.pool = Objects.requireNonNull(pool); }
    @Override public Object normalizeInput(String group, Object input) {
        return Collections.unmodifiableMap(RuleInputs.object(input, Set.of()));
    }
    @Override public Map<String, WorkerCandidate> apply(String group, Map<String, Object> inputs) {
        return PoolCandidates.take(inputs, (input, limit) -> pool.pollAnyBatch(group, limit));
    }
}
