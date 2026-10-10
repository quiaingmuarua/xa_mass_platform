package com.xa.mass.workermatching.functions;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.*;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;
import java.util.*;

public final class PartitionedLeaseQueryFunction implements QueryFunction {
    private final WorkerCandidatePool pool;
    private final PartitionedLeasePoolDefinition definition;
    public PartitionedLeaseQueryFunction(WorkerCandidatePool pool, PartitionedLeasePoolDefinition definition) {
        this.pool = Objects.requireNonNull(pool); this.definition = Objects.requireNonNull(definition);
    }
    @Override public Object normalizeInput(String group, Object input) {
        var values = RuleInputs.object(input, Set.of("partition", "country"));
        String partition = RuleInputs.text(values.get("partition")), country = RuleInputs.text(values.get("country"));
        definition.bucket(partition, country);
        return Map.of("partition", partition, "country", country);
    }
    @Override public Map<String, WorkerCandidate> apply(String group, Map<String, Object> inputs) {
        return PoolCandidates.take(inputs, (input, limit) -> {
            var query = (Map<?, ?>) input;
            return pool.pollBatch(group, Set.of(definition.bucket((String) query.get("partition"),
                    (String) query.get("country"))), limit);
        });
    }
}
