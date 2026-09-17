package com.xa.mass.workermatching.functions;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.QueryFunction;
import com.xa.mass.workermatching.RuleInputs;
import com.xa.mass.workermatching.pool.CandidatePool;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Fixed proof combinations over the injected Proof Pool. */
public final class ProofFactsQueryFunction implements QueryFunction {
    private final CandidatePool pool;

    public ProofFactsQueryFunction(CandidatePool pool) { this.pool = Objects.requireNonNull(pool); }

    @Override public Object normalizeInput(String workerGroupId, Object input) {
        var values = RuleInputs.object(input, Set.of("proofPool", "proofTarget", "proofEnabled", "convergenceSlot"));
        if (values.containsKey("convergenceSlot") && values.size() != 1)
            throw new IllegalArgumentException("convergenceSlot cannot combine with proof fields");
        values.replaceAll((key, value) -> RuleInputs.text(value));
        return Collections.unmodifiableMap(values);
    }

    @Override public Map<String, WorkerCandidate> apply(String workerGroupId, Map<String, Object> inputsByMessageId) {
        var selections = new LinkedHashMap<String, CandidatePool.Selection>();
        inputsByMessageId.forEach((id, input) -> {
            @SuppressWarnings("unchecked") var values = (Map<String, Object>) input;
            if (values.isEmpty()) {
                selections.put(id, CandidatePool.all());
            } else {
                String partition = values.containsKey("convergenceSlot") ? "slot:" + values.get("convergenceSlot")
                        : values.getOrDefault("proofPool", "*") + "|" + values.getOrDefault("proofTarget", "*")
                                + "|" + values.getOrDefault("proofEnabled", "*");
                selections.put(id, CandidatePool.range("partition:" + partition, List.of("1")));
            }
        });
        return PoolCandidates.take(pool, workerGroupId, selections);
    }
}
