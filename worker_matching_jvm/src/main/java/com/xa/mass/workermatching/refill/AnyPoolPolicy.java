package com.xa.mass.workermatching.refill;

import com.xa.mass.workermatching.pool.WorkerCandidatePool;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import java.util.*;

/** Explicit unconditional stock, with one ordinary bucket key. */
public final class AnyPoolPolicy extends PoolMaintenance<Void> {
    public AnyPoolPolicy(WorkerCandidatePool pool) { super(pool); }
    @Override protected EligibilityQuery normalize(String group, EligibilityQuery query) {
        if (!query.query().isEmpty()) throw new IllegalArgumentException("any Pool requires an empty target");
        return query;
    }
    @Override protected Map<String, Void> readQualifications(String group, List<String> ids) { return Map.of(); }
    @Override protected String bucketKey(String group, String id, Void ignored) { return "any"; }
    @Override protected Map<EligibilityQuery, Set<String>> matchingKeys(String group,
            Collection<EligibilityQuery> queries, Set<String> keys) {
        var result = new LinkedHashMap<EligibilityQuery, Set<String>>();
        queries.forEach(query -> result.put(query, keys));
        return result;
    }
}
