package com.xa.mass.workermatching.functions;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.QueryFunction;
import com.xa.mass.workermatching.RuleInputs;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;
import com.xa.mass.workermatching.buckets.ProofFactsBuckets;
import java.util.*;

/** Partial Proof queries resolve complete bucket keys before any consumption. */
public final class ProofFactsQueryFunction implements QueryFunction {
    private final WorkerCandidatePool pool;
    public ProofFactsQueryFunction(WorkerCandidatePool pool) { this.pool = Objects.requireNonNull(pool); }
    @Override public Object normalizeInput(String group, Object input) {
        var values = RuleInputs.object(input, Set.of("proofPool", "proofTarget", "proofEnabled", "convergenceSlot"));
        if (values.containsKey("convergenceSlot") && values.size() != 1)
            throw new IllegalArgumentException("convergenceSlot cannot combine with proof fields");
        values.replaceAll((key, value) -> RuleInputs.text(value));
        return Collections.unmodifiableMap(values);
    }
    @Override public Map<String, WorkerCandidate> apply(String group, Map<String, Object> inputs) {
        var keys = new HashMap<Object, Set<String>>();
        if (inputs.values().stream().anyMatch(input -> !((Map<?, ?>) input).isEmpty())) {
            var directory = ProofFactsBuckets.decodeKeys(pool.countByKey(group).keySet());
            for (Object input : inputs.values()) if (!((Map<?, ?>) input).isEmpty()) {
                @SuppressWarnings("unchecked") var values = (Map<String, Object>) input;
                keys.computeIfAbsent(input, ignored -> ProofFactsBuckets.matchingKeys(values, directory));
            }
        }
        return PoolCandidates.take(inputs, (input, limit) -> ((Map<?, ?>) input).isEmpty()
                ? pool.pollAnyBatch(group, limit) : pool.pollBatch(group, keys.get(input), limit));
    }
}
