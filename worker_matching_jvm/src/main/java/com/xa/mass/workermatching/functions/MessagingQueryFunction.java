package com.xa.mass.workermatching.functions;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.QueryFunction;
import com.xa.mass.workermatching.RuleInputs;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;
import java.util.*;

/** ANY or country selection within the injected Messaging Pool. */
public final class MessagingQueryFunction implements QueryFunction {
    private final WorkerCandidatePool pool;
    public MessagingQueryFunction(WorkerCandidatePool pool) { this.pool = Objects.requireNonNull(pool); }
    @Override public Object normalizeInput(String group, Object input) {
        var values = RuleInputs.object(input, Set.of("country"));
        if (values.containsKey("country")) values.put("country", RuleInputs.countries(values.get("country")));
        return Collections.unmodifiableMap(values);
    }
    @Override public Map<String, WorkerCandidate> apply(String group, Map<String, Object> inputs) {
        var keys = new HashMap<Object, Set<String>>();
        for (Object input : inputs.values()) {
            @SuppressWarnings("unchecked") var countries = (List<String>) ((Map<?, ?>) input).get("country");
            if (countries != null) keys.put(input, Set.copyOf(countries));
        }
        return PoolCandidates.take(inputs, (input, limit) -> ((Map<?, ?>) input).containsKey("country")
                ? pool.pollBatch(group, keys.get(input), limit) : pool.pollAnyBatch(group, limit));
    }
}
