package com.xa.mass.workermatching.functions;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.QueryFunction;
import com.xa.mass.workermatching.RuleInputs;
import com.xa.mass.workermatching.views.MessagingViews;
import com.xa.mass.workermatching.pool.CandidatePool;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** ANY or country selection within the injected Messaging Pool. */
public final class MessagingQueryFunction implements QueryFunction {
    private final CandidatePool pool;

    public MessagingQueryFunction(CandidatePool pool) { this.pool = Objects.requireNonNull(pool); }

    @Override public Object normalizeInput(String workerGroupId, Object input) {
        var values = RuleInputs.object(input, Set.of("country"));
        if (values.containsKey("country")) values.put("country", RuleInputs.countries(values.get("country")));
        return Collections.unmodifiableMap(values);
    }

    @Override public Map<String, WorkerCandidate> apply(String workerGroupId, Map<String, Object> inputsByMessageId) {
        var selections = new LinkedHashMap<String, CandidatePool.Selection>();
        inputsByMessageId.forEach((id, input) -> selections.put(id, select((Map<?, ?>) input)));
        return PoolCandidates.take(pool, workerGroupId, selections);
    }

    private static CandidatePool.Selection select(Map<?, ?> values) {
        @SuppressWarnings("unchecked") var countries = (List<String>) values.get("country");
        return MessagingViews.select(countries == null ? List.of() : countries);
    }
}
